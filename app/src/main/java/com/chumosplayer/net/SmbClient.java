package com.chumosplayer.net;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.share.Share;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SMB 远程音乐客户端（基于 smbj，SMB2/3）。只做三件事：列目录、取大小、按偏移随机读。
 *
 * 两种用法：
 * - 浏览：一次性 {@link #list(Target, String)}（内部开会话、用完即关）
 * - 串流：{@link #open(Target)} 拿长连接会话，一次 HTTP 请求内复用（代理每请求开一次、读完关闭）
 *
 * smb://host[:port]/share/path 的解析与组装见 {@link #parseUrl(String)} / {@link #buildUrl}。
 */
public final class SmbClient {

    /** 文件夹属性位：FILE_ATTRIBUTE_DIRECTORY */
    private static final long ATTR_DIR = 0x10L;

    /** SMB 连接描述：服务器/共享/根路径/账号 */
    public static class Target {
        public final String host;
        public final int port;
        public final String share;
        /** 共享内根路径，形如 Music 或空串 */
        public final String root;
        public final String user;
        public final String pass;
        public final String domain;

        public Target(String host, int port, String share, String root,
                      String user, String pass, String domain) {
            this.host = host;
            this.port = port <= 0 ? 445 : port;
            this.share = share == null ? "" : share;
            this.root = normalize(root);
            this.user = user == null ? "" : user;
            this.pass = pass == null ? "" : pass;
            this.domain = domain == null ? "" : domain;
        }

        private static String normalize(String p) {
            String s = p == null ? "" : p.trim();
            while (s.startsWith("/")) s = s.substring(1);
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
        }

        /** 相对 root 的路径 → share 内真实路径 */
        public String inShare(String path) {
            String p = path == null ? "" : path.trim();
            while (p.startsWith("/")) p = p.substring(1);
            if (root.isEmpty()) return p;
            return p.isEmpty() ? root : root + "/" + p;
        }
    }

    /** 目录项：path 为相对 root 的路径（/ 开头） */
    public static class Entry {
        public final String name;
        public final String path;
        public final boolean isDir;
        public final long size;

        public Entry(String name, String path, boolean isDir, long size) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private SmbClient() {}

    // ---- smb:// 地址（纯函数，可 JVM 单测）----

    /** smb://host[:port]/share/path 解析结果 */
    public static final class Url {
        public final String host;
        public final int port;
        public final String share;
        /** share 内路径（不带前导 /） */
        public final String path;

        Url(String host, int port, String share, String path) {
            this.host = host;
            this.port = port;
            this.share = share;
            this.path = path;
        }
    }

    /** 解析 smb:// 地址（纯函数，可在 JVM 单测） */
    public static Url parseUrl(String url) throws IOException {
        if (url == null || !url.startsWith("smb://")) {
            throw new IOException("非法 SMB 地址：" + url);
        }
        String rest = url.substring("smb://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) throw new IOException("缺少共享名：" + url);
        String hostPort = rest.substring(0, slash);
        String after = rest.substring(slash + 1);
        String host = hostPort;
        int port = 445;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IOException("端口号非法：" + url);
            }
        }
        if (host.isEmpty()) throw new IOException("缺少主机名：" + url);
        int slash2 = after.indexOf('/');
        String share = slash2 < 0 ? after : after.substring(0, slash2);
        String path = slash2 < 0 ? "" : after.substring(slash2 + 1);
        if (share.isEmpty()) throw new IOException("缺少共享名：" + url);
        return new Url(host, port, share, path);
    }

    /** 组装 smb:// 地址（与 parseUrl 互逆） */
    public static String buildUrl(String host, int port, String share, String pathInShare) {
        StringBuilder sb = new StringBuilder("smb://").append(host);
        if (port > 0 && port != 445) sb.append(':').append(port);
        sb.append('/').append(share);
        String p = pathInShare == null ? "" : pathInShare;
        while (p.startsWith("/")) p = p.substring(1);
        if (!p.isEmpty()) sb.append('/').append(p);
        return sb.toString();
    }

    // ---- 会话 ----

    /** 一条长连接会话：代理在一次 HTTP 请求内复用，用完 close() */
    public static final class Session implements Closeable {
        private final SMBClient client;
        private final Connection conn;
        private final Share share;
        private final Target target;

        private Session(SMBClient client, Connection conn, Share share, Target target) {
            this.client = client;
            this.conn = conn;
            this.share = share;
            this.target = target;
        }

        private DiskShare disk() throws IOException {
            if (!(share instanceof DiskShare)) {
                throw new IOException("该共享不是磁盘共享：" + target.share);
            }
            return (DiskShare) share;
        }

        /** 列目录（path 相对 root，可为 ""） */
        public List<Entry> list(String path) throws IOException {
            List<Entry> out = new ArrayList<>();
            String dir = path == null ? "" : path;
            List<FileIdBothDirectoryInformation> items;
            try {
                items = disk().list(target.inShare(dir));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("列目录失败：" + e.getMessage(), e);
            }
            for (FileIdBothDirectoryInformation info : items) {
                String name = info.getFileName();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                boolean isDir = (info.getFileAttributes() & ATTR_DIR) != 0;
                String child = dir.endsWith("/") ? dir + name : dir + "/" + name;
                if (child.startsWith("//")) child = child.substring(1);
                out.add(new Entry(name, child, isDir, isDir ? 0 : info.getEndOfFile()));
            }
            return out;
        }

        /** 文件大小（字节） */
        public long size(String path) throws IOException {
            try {
                return disk().getFileInformation(target.inShare(path))
                        .getStandardInformation().getEndOfFile();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("取文件大小失败：" + e.getMessage(), e);
            }
        }

        /** 随机读：从 fileOffset 读至多 len 字节到 buf[0..]，返回读取数 */
        public int read(String path, long fileOffset, byte[] buf, int len) throws IOException {
            File f = null;
            try {
                f = disk().openFile(target.inShare(path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ,
                                SMB2ShareAccess.FILE_SHARE_WRITE,
                                SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf, 0, len);
                long n = f.read(bb, fileOffset);
                return n <= 0 ? 0 : (int) n;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("读取失败：" + e.getMessage(), e);
            } finally {
                if (f != null) {
                    try { f.close(); } catch (Exception ignore) { }
                }
            }
        }

        @Override
        public void close() {
            try { share.close(); } catch (Exception ignore) { }
            try { conn.close(); } catch (Exception ignore) { }
            try { client.close(); } catch (Exception ignore) { }
        }
    }

    /** 建立一条会话（连接 + 认证 + 接共享） */
    public static Session open(Target t) throws IOException {
        if (t == null || t.host == null || t.host.trim().isEmpty()) {
            throw new IOException("请先配置 SMB 服务器");
        }
        if (t.share == null || t.share.trim().isEmpty()) {
            throw new IOException("请填写共享名");
        }
        SMBClient client = new SMBClient();
        try {
            Connection conn = client.connect(t.host.trim(), t.port);
            try {
                AuthenticationContext auth;
                if (t.user == null || t.user.trim().isEmpty()) {
                    auth = AuthenticationContext.anonymous();
                } else {
                    auth = new AuthenticationContext(t.user, t.pass.toCharArray(),
                            t.domain == null ? "" : t.domain);
                }
                // 注意：本类也有个嵌套 Session，这里必须用全限定名指 smbj 的会话
                com.hierynomus.smbj.session.Session session = conn.authenticate(auth);
                Share share = session.connectShare(t.share.trim());
                return new Session(client, conn, share, t);
            } catch (Exception e) {
                try { conn.close(); } catch (Exception ignore) { }
                throw new IOException("SMB 连接失败：" + e.getMessage(), e);
            }
        } catch (IOException e) {
            try { client.close(); } catch (Exception ignore) { }
            throw e;
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignore) { }
            throw new IOException("SMB 连接失败：" + e.getMessage(), e);
        }
    }

    // ---- 一次性便捷方法（浏览用）----

    public static List<Entry> list(Target t, String path) throws IOException {
        Session s = open(t);
        try {
            return s.list(path);
        } finally {
            s.close();
        }
    }

    public static long size(Target t, String path) throws IOException {
        Session s = open(t);
        try {
            return s.size(path);
        } finally {
            s.close();
        }
    }

    public static int read(Target t, String path, long fileOffset, byte[] buf, int len)
            throws IOException {
        Session s = open(t);
        try {
            return s.read(path, fileOffset, buf, len);
        } finally {
            s.close();
        }
    }
}

package com.chumosplayer.net;

import android.content.Context;
import android.util.Base64;

import com.chumosplayer.util.SettingsManager;
import com.chumosplayer.util.MusicLoader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * WebDAV 远程音乐客户端（APlayer 的 WebDAV 串流）：
 * - PROPFIND Depth:1 列目录，DOM 解析 multistatus
 * - Basic 认证（存于设置，随备份导出）
 * - 流式播放时把 Authorization 头交给 MediaPlayer.setDataSource(url, headers)
 *
 * 纯解析部分（parseList / resolve / decodePath）不依赖 Android API，
 * 可直接在 JVM 上单测。
 */
public final class WebDavClient {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile";

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:resourcetype/><d:getcontentlength/><d:displayname/>"
                    + "</d:prop></d:propfind>";

    /** 目录/文件条目：path 为解码后的绝对路径（如 /Music/周杰伦） */
    public static class Entry {
        public String name;
        public String path;
        public boolean isDir;
        public long size;

        public Entry(String name, String path, boolean isDir, long size) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private WebDavClient() {}

    // ---- 配置 ----

    /** 若 url 属于已配置的 WebDAV 服务器，返回带头部的请求头；否则返回 null */
    public static Map<String, String> headersIfWebDav(Context ctx, String url) {
        try {
            if (ctx == null || url == null) return null;
            if (!SettingsManager.isWebDavConfigured(ctx)) return null;
            String base = SettingsManager.getWebDavUrl(ctx).trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.isEmpty() || !url.startsWith(base)) return null;
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", authHeader(ctx));
            h.put("User-Agent", UA);
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    /** Basic 认证头（账号密码为空时也返回 Basic，便于匿名服务器） */
    public static String authHeader(Context ctx) {
        String u = SettingsManager.getWebDavUser(ctx);
        String p = SettingsManager.getWebDavPass(ctx);
        String raw = (u == null ? "" : u) + ":" + (p == null ? "" : p);
        return "Basic " + Base64.encodeToString(
                raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    // ---- 网络 ----

    /** 列出 path 目录（path 形如 "/Music"，根为 "/"，均相对服务器地址里的路径） */
    public static List<Entry> list(Context ctx, String path) throws IOException {
        if (ctx == null || !SettingsManager.isWebDavConfigured(ctx)) {
            throw new IOException("请先配置 WebDAV 服务器");
        }
        String base = SettingsManager.getWebDavUrl(ctx);
        String url = resolve(base, path);
        String xml = propFind(url, authHeader(ctx));
        // href 是「主机根」路径（含 /dav 这类前缀），要减掉 base 的路径部分
        // 才和我们内部以 base 为根的 path 对齐
        return parseList(xml, normalize(path), basePathOf(base));
    }

    private static String propFind(String url, String auth) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("PROPFIND");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Depth", "1");
        conn.setRequestProperty("Authorization", auth);
        conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
        conn.setRequestProperty("User-Agent", UA);
        byte[] body = PROPFIND_BODY.getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        try {
            os.write(body);
        } finally {
            os.close();
        }
        int code = conn.getResponseCode();
        if (code == 401) {
            conn.disconnect();
            throw new IOException("认证失败：请检查账号密码");
        }
        if (code == 404) {
            conn.disconnect();
            throw new IOException("路径不存在");
        }
        if (code != 207 && code != 200) {
            conn.disconnect();
            throw new IOException("服务器返回 HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
            conn.disconnect();
        }
        return bos.toString("UTF-8");
    }

    // ---- 解析（可在 JVM 单测）----

    /**
     * 解析 multistatus XML。
     * @param selfPath 请求目录本身（内部 path），会被排除
     * @param basePath 服务器地址的路径部分（如 /dav），用于把 href 折算成内部 path
     */
    public static List<Entry> parseList(String xml, String selfPath, String basePath)
            throws IOException {
        List<Entry> out = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) return out;
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("目录列表解析失败");
        }
        String self = normalize(selfPath);
        for (Element resp : byLocal(doc, "response")) {
            // 细节字段在 propstat/prop 里（response 的孙子层），必须按后代查找
            String href = textOf(firstDesc(resp, "href"));
            if (href.isEmpty()) continue;
            Element resType = firstDesc(resp, "resourcetype");
            Element length = firstDesc(resp, "getcontentlength");
            Element display = firstDesc(resp, "displayname");
            String path = stripBase(decodePath(href), basePath);
            if (path.equals(self)) continue; // 跳过自身
            boolean isDir = resType != null && !byLocal(resType, "collection").isEmpty();
            long size = 0;
            if (length != null) {
                try {
                    size = Long.parseLong(textOf(length));
                } catch (Exception ignore) { }
            }
            String name = !textOf(display).isEmpty() ? textOf(display) : lastSegment(path);
            if (name == null || name.isEmpty()) continue;
            out.add(new Entry(name, path, isDir, size));
        }
        return out;
    }

    /** 第一个匹配 localName 的后代元素 */
    private static Element firstDesc(Element parent, String local) {
        List<Element> list = byLocal(parent, local);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 服务器地址的路径部分（https://host/dav/ → /dav；无路径 → /） */
    public static String basePathOf(String base) {
        try {
            java.net.URL u = new java.net.URL((base == null ? "" : base).trim());
            String p = u.getPath();
            if (p == null || p.isEmpty()) p = "/";
            while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
            return p;
        } catch (Exception e) {
            return "/";
        }
    }

    /** 把 href 得到的主机根路径折算成相对 base 的内部路径 */
    public static String stripBase(String path, String base) {
        String p = normalize(path);
        String b = (base == null || base.isEmpty()) ? "/" : normalize(base);
        if (!"/".equals(b) && (p.equals(b) || p.startsWith(b + "/"))) {
            p = p.substring(b.length());
            if (p.isEmpty()) p = "/";
        }
        return p;
    }

    /** 拼 URL：base + 逐段百分号编码的 path */
    public static String resolve(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String p = path == null ? "/" : path;
        if (!p.startsWith("/")) p = "/" + p;
        StringBuilder sb = new StringBuilder(b);
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(encodeSegment(seg));
        }
        if (p.length() > 1 && p.endsWith("/")) sb.append('/');
        return sb.toString();
    }

    /** href → 解码后的路径（去掉协议与域名、去掉查询串、百分号解码） */
    public static String decodePath(String href) {
        if (href == null) return "/";
        String p = href.trim();
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int slash = p.indexOf('/', scheme + 3);
            p = slash >= 0 ? p.substring(slash) : "/";
        }
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.isEmpty()) p = "/";
        if (!p.startsWith("/")) p = "/" + p;
        return normalize(percentDecode(p));
    }

    /** 规范化：确保以 / 开头、去掉重复斜杠与末尾斜杠（根除外） */
    public static String normalize(String path) {
        String p = path == null ? "/" : path.trim();
        if (p.isEmpty()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    // ---- 小工具 ----

    private static String encodeSegment(String seg) {
        try {
            return URLEncoder.encode(seg, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return seg;
        }
    }

    /** 百分号解码（路径用，保留字面 + 号） */
    public static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    try {
                        int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                        bos.write(v);
                        i += 3;
                        continue;
                    } catch (NumberFormatException ignore) { }
                }
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bos.write(b, 0, b.length);
                i++;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String localName(Element e) {
        String ln = e.getLocalName();
        if (ln == null || ln.isEmpty()) ln = e.getTagName();
        int dot = ln.lastIndexOf(':');
        return dot >= 0 ? ln.substring(dot + 1) : ln;
    }

    private static String textOf(Element e) {
        if (e == null) return "";
        return e.getTextContent() == null ? "" : e.getTextContent().trim();
    }

    /** 按 localName 收集元素（兼容带/不带命名空间前缀的服务器） */
    private static List<Element> byLocal(Node root, String local) {
        List<Element> out = new ArrayList<>();
        NodeList all = root instanceof Document
                ? ((Document) root).getElementsByTagName("*")
                : ((Element) root).getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element && localName((Element) n).equalsIgnoreCase(local)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** 是否音频文件名（与本地扫描同一套判断） */
    public static boolean isAudio(Entry e) {
        return e != null && !e.isDir && MusicLoader.isAudioName(e.name);
    }
}

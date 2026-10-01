package com.chumosplayer.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 HTTP 串流代理：把任意 {@link Source} 暴露成 http://127.0.0.1:&lt;port&gt;/s/&lt;path&gt;，
 * 供 MediaPlayer 串流播放并支持拖动进度（HTTP Range → 206）。
 *
 * 只依赖 java.*，因此可在 JVM 上直接单测 Range 行为。
 *
 * 用法：
 *   LocalStreamProxy proxy = new LocalStreamProxy(opener);   // opener 按 path 打开数据源
 *   int port = proxy.start();
 *   String mediaUrl = "http://127.0.0.1:" + port + "/s/" + encodedPath;
 */
public final class LocalStreamProxy {

    /** 一次请求对应的数据源：打开 → 按需随机读 → 关闭 */
    public interface Source {
        /** 总字节数；<=0 表示无法提供（代理会拒绝服务） */
        long size();

        /** 从 offset 读至多 len 字节到 buf，返回读取数；0 表示已到尾 */
        int read(long offset, byte[] buf, int len) throws IOException;

        /** MIME 类型；null 用 application/octet-stream */
        String contentType();

        void close();
    }

    /** 按请求路径打开数据源（path 为 URL 解码后的原始描述串） */
    public interface SourceOpener {
        Source open(String path) throws IOException;
    }

    private static final int MAX_CONCURRENT = 4;
    private static final int BUF = 64 * 1024;

    private final SourceOpener opener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private volatile int active = 0;

    public LocalStreamProxy(SourceOpener opener) {
        this.opener = opener;
    }

    /** 启动（或返回已在监听的端口）；只监听 127.0.0.1 */
    public synchronized int start() throws IOException {
        if (server != null && server.isBound() && !server.isClosed()) {
            return server.getLocalPort();
        }
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        Thread t = new Thread(this::acceptLoop, "stream-proxy");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running.set(false);
        if (server != null) {
            try { server.close(); } catch (Exception ignore) { }
            server = null;
        }
    }

    public boolean isRunning() {
        return running.get() && server != null && !server.isClosed();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket sock;
            try {
                sock = server.accept();
            } catch (IOException e) {
                return; // server 关闭
            }
            if (active >= MAX_CONCURRENT) {
                try {
                    respond(sock.getOutputStream(), 503, "busy", null, 0, 0, 0, null, false);
                } catch (Exception ignore) { }
                try { sock.close(); } catch (Exception ignore) { }
                continue;
            }
            active++;
            final Socket s = sock;
            Thread worker = new Thread(() -> handle(s), "stream-proxy-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket sock) {
        Source src = null;
        boolean headerSent = false;
        try {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String uri = parts.length > 1 ? parts[1] : "/";
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int ci = line.indexOf(':');
                if (ci > 0 && line.substring(0, ci).trim().equalsIgnoreCase("Range")) {
                    range = line.substring(ci + 1).trim();
                }
            }

            if (!uri.startsWith("/s/")) {
                respond(sock.getOutputStream(), 404, "not found",
                        null, 0, 0, 0, null, false);
                headerSent = true;
                return;
            }
            String path = URLDecoder.decode(uri.substring(3), "UTF-8");

            src = opener.open(path);
            long total = src.size();
            if (total <= 0) {
                respond(sock.getOutputStream(), 404, "unknown size",
                        null, 0, 0, 0, null, false);
                headerSent = true;
                return;
            }

            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring("bytes=".length()).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String a = spec.substring(0, dash).trim();
                    String b = spec.substring(dash + 1).trim();
                    try {
                        if (a.isEmpty()) {
                            // bytes=-N ：最后 N 字节
                            long n = Long.parseLong(b);
                            start = Math.max(0, total - n);
                            end = total - 1;
                        } else {
                            start = Long.parseLong(a);
                            end = b.isEmpty() ? total - 1 : Long.parseLong(b);
                        }
                        if (start > end || start >= total) {
                            respond(sock.getOutputStream(), 416, "range not satisfiable",
                                    null, 0, 0, total, null, false);
                            headerSent = true;
                            return;
                        }
                        end = Math.min(end, total - 1);
                        partial = true;
                    } catch (NumberFormatException ignore) {
                        partial = false; // 解析不了就整段返回
                    }
                }
            }

            long contentLen = end - start + 1;
            String ctype = src.contentType() != null ? src.contentType() : "application/octet-stream";
            OutputStream out = sock.getOutputStream();
            respond(out, partial ? 206 : 200, partial ? "partial" : "ok",
                    ctype, contentLen, start, end, total > 0 ? Long.valueOf(total) : null, true);
            headerSent = true;

            if (!"HEAD".equalsIgnoreCase(method) && contentLen > 0) {
                byte[] buf = new byte[BUF];
                long pos = start;
                long remaining = contentLen;
                while (remaining > 0 && !sock.isClosed()) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = src.read(pos, buf, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    pos += n;
                    remaining -= n;
                }
                out.flush();
            }
        } catch (Exception e) {
            if (!headerSent) {
                try {
                    respond(sock.getOutputStream(), 500, "error", null, 0, 0, 0, null, false);
                } catch (Exception ignore) { }
            }
        } finally {
            try { if (src != null) src.close(); } catch (Exception ignore) { }
            try { sock.close(); } catch (Exception ignore) { }
            active--;
        }
    }

    /** 写响应头（bodyLength >= 0 时带 Content-Length） */
    private static void respond(OutputStream out, int code, String reason, String ctype,
                                long bodyLength, long start, long end, Long total,
                                boolean withBodyHeaders) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (withBodyHeaders) {
            if (ctype != null) sb.append("Content-Type: ").append(ctype).append("\r\n");
            sb.append("Content-Length: ").append(bodyLength).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            if (code == 206 && total != null) {
                sb.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(total).append("\r\n");
            }
            if (code == 416 && total != null) {
                sb.append("Content-Range: bytes */").append(total).append("\r\n");
            }
        } else {
            byte[] msg = ("{" + code + "} " + reason).getBytes(StandardCharsets.UTF_8);
            sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            sb.append("Content-Length: ").append(msg.length).append("\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}

package com.chumosplayer.net;

import android.content.Context;

import com.chumosplayer.util.SettingsManager;

import java.io.IOException;
import java.net.URLEncoder;

/**
 * SMB 串流胶水：启动本地 HTTP 代理，把 smb:// 地址换算成
 * http://127.0.0.1:&lt;port&gt;/s/&lt;编码后的 smb 地址&gt;，交给 MediaPlayer 串流播放。
 *
 * 代理每收到一个请求开一条 SMB 会话（size 只查一次，后续 Range 全靠随机读），读完关闭。
 */
public final class SmbStreamer {

    private static LocalStreamProxy proxy;
    private static int port;

    private SmbStreamer() {}

    /** 是否为 SMB 地址 */
    public static boolean isSmb(String url) {
        return url != null && url.startsWith("smb://");
    }

    /** 把 smb:// 地址转成本地可播放地址；代理未启动则先启动 */
    public static synchronized String mediaUrl(Context ctx, String smbUrl) throws IOException {
        if (ctx == null) throw new IOException("缺少上下文");
        if (!isSmb(smbUrl)) throw new IOException("不是 SMB 地址");
        ensure(ctx.getApplicationContext());
        return "http://127.0.0.1:" + port + "/s/" + URLEncoder.encode(smbUrl, "UTF-8");
    }

    private static void ensure(Context app) throws IOException {
        if (proxy != null && proxy.isRunning()) return;
        LocalStreamProxy p = new LocalStreamProxy(path -> openSource(app, path));
        port = p.start();
        proxy = p;
    }

    /** 代理回调：按路径开 SMB 会话并包成 Source */
    private static LocalStreamProxy.Source openSource(Context ctx, final String path)
            throws IOException {
        final SmbClient.Url u = SmbClient.parseUrl(path);
        // URL 里已含共享内完整路径，root 置空
        SmbClient.Target target = new SmbClient.Target(u.host, u.port, u.share, "",
                SettingsManager.getSmbUser(ctx), SettingsManager.getSmbPass(ctx), "");
        final SmbClient.Session session = SmbClient.open(target);
        long size;
        try {
            size = session.size(u.path);
        } catch (IOException e) {
            session.close();
            throw e;
        }
        if (size <= 0) {
            session.close();
            throw new IOException("文件大小未知：" + u.path);
        }
        final long total = size;
        return new LocalStreamProxy.Source() {
            @Override
            public long size() {
                return total;
            }

            @Override
            public int read(long offset, byte[] buf, int len) throws IOException {
                return session.read(u.path, offset, buf, len);
            }

            @Override
            public String contentType() {
                return "audio/mpeg";
            }

            @Override
            public void close() {
                session.close();
            }
        };
    }
}

package com.chumosplayer.net;

import android.content.Context;
import android.content.SharedPreferences;

import com.chumosplayer.model.Song;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 网易云直链解析 + 持久化缓存。
 *
 * - 解析：网易云外链 http://music.163.com/song/media/outer/url?id=<id>.mp3 会 302 跳转
 *   到真实 CDN 地址，解析一次后把 (songId -> 直链+文件后缀) 存入 SharedPreferences，
 *   下次播放/下载直接用缓存，不再重复请求。
 * - 已下载过的歌曲（Music/MyFreeMP3/<歌手> - <歌名>.mp3 存在）直接返回本地路径，零流量。
 */
public class NeteaseCache {

    private static final String PREFS = "netease_cache";
    private static final String KEY_PREFIX = "url_";
    private static final String KEY_EXT_PREFIX = "ext_";

    /** 解析结果：本地文件优先 */
    public static class Resolved {
        public final String playUrl;   // 本地路径或 http 直链
        public final boolean isLocal;

        public Resolved(String playUrl, boolean isLocal) {
            this.playUrl = playUrl;
            this.isLocal = isLocal;
        }
    }

    /**
     * 获取可播放/下载地址：本地已下载文件 > 缓存直链 > 现场解析并缓存。
     * 需在子线程调用。
     */
    public static Resolved resolve(Context ctx, Song s) throws IOException {
        // 1) 已下载过 → 直接用本地文件（对应 DownloadUtil 的保存命名）
        File local = localFileFor(s);
        if (local != null && local.exists() && local.length() > 0) {
            return new Resolved(local.getAbsolutePath(), true);
        }
        // 2) 缓存的直链
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = sp.getString(KEY_PREFIX + s.id, null);
        if (cached != null && !cached.isEmpty()) {
            return new Resolved(cached, false);
        }
        // 3) 现场解析 302 并缓存
        String direct = resolve302("http://music.163.com/song/media/outer/url?id=" + s.id + ".mp3");
        if (direct == null || direct.isEmpty()) {
            throw new IOException("网易云直链解析失败");
        }
        sp.edit().putString(KEY_PREFIX + s.id, direct).apply();
        return new Resolved(direct, false);
    }

    /** 跟随 302 取真实直链（复用 MyFreeMP3Client 的手动跳转逻辑） */
    private static String resolve302(String url) throws IOException {
        HttpURLConnection conn = MyFreeMP3Client.openForResolve(url);
        try {
            int code = conn.getResponseCode();
            if (code == 301 || code == 302) {
                String loc = conn.getHeaderField("Location");
                return loc != null ? loc : url;
            }
            if (code == 200) return url; // 未跳转，外链本身可用
            throw new IOException("HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    /** 推断直链文件后缀（默认 mp3），用于下载命名 */
    public static String extOf(String url) {
        if (url == null) return "mp3";
        String lower = url.toLowerCase();
        String[] exts = {".mp3", ".m4a", ".flac", ".aac", ".wav"};
        for (String e : exts) {
            int idx = lower.indexOf(e);
            if (idx >= 0 && idx + e.length() <= lower.length()
                    && (idx + e.length() == lower.length()
                        || !Character.isLetterOrDigit(lower.charAt(idx + e.length())))) {
                return e.substring(1);
            }
        }
        return "mp3";
    }

    /** 与 DownloadUtil 相同规则的本地目标文件（用于已下载判断） */
    public static File localFileFor(Song s) {
        String filename = (s.artist == null || s.artist.isEmpty())
                ? s.title : s.artist + " - " + s.title;
        String safe = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = new File(android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                "MyFreeMP3");
        return new File(dir, safe.endsWith(".mp3") ? safe : safe + ".mp3");
    }

    /** 清空直链缓存（下载地址失效时可调用重新解析） */
    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}

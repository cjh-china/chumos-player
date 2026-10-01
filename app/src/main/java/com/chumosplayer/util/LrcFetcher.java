package com.chumosplayer.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.chumosplayer.model.Song;
import com.chumosplayer.net.KugouLyricClient;
import com.chumosplayer.net.MyFreeMP3Client;
import com.chumosplayer.net.NeteaseLyricClient;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 在线歌词抓取：按设置里的「在线歌词音源」优先级依次尝试，取到即返回。
 * 可用音源：网易云（官方 lyric 接口 / MyFreeMP3 搜索）、酷狗（搜索→候选→下载三步）。
 * 本地歌曲取回后写入同名 .lrc，下次直接由 LocalLrcLoader 读取，无需再联网。
 */
public final class LrcFetcher {

    private LrcFetcher() {}

    public interface Callback {
        /** 取到歌词时回调（主线程）；未取到不回调 */
        void onFetched(String lrc);
    }

    /** 异步抓取歌词；本地歌曲会顺手落盘 */
    public static void fetch(final Context ctx, final Song song, final Callback cb) {
        if (song == null || song.title == null || song.title.trim().isEmpty()) return;
        if (!song.online && song.path == null) return;
        final boolean saveLocal = !song.online && song.path != null;
        new Thread(() -> {
            String lrc = fetchBlocking(ctx, song);
            if (!valid(lrc)) return;
            if (saveLocal) saveToLocal(song, lrc);
            final String result = lrc;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (cb != null) cb.onFetched(result);
            });
        }, "lrc-fetch").start();
    }

    /** 同步抓取：按用户选择的音源优先，失败自动落到备选音源 */
    public static String fetchBlocking(Context ctx, Song song) {
        if (song == null) return null;
        boolean kugouFirst = ctx != null
                && SettingsManager.getLyricSource(ctx) == SettingsManager.LYRIC_SRC_KUGOU;
        if (kugouFirst) {
            String l = tryKugou(song);
            if (valid(l)) return l;
            l = tryNetease(song);
            if (valid(l)) return l;
        } else {
            String l = tryNetease(song);
            if (valid(l)) return l;
            l = tryKugou(song);
            if (valid(l)) return l;
        }
        return null;
    }

    // ---- 各音源实现（全部 try/catch，失败即返回 null 交给下一个）----

    /** 网易云：线上歌曲直接按 id 走官方接口；其余用 MyFreeMP3 搜索 */
    private static String tryNetease(Song s) {
        try {
            if (s.online && s.id > 0 && s.url != null && s.url.contains("music.163.com")) {
                String l = NeteaseLyricClient.fetch(s.id);
                if (valid(l)) return l;
            }
            String l = MyFreeMP3Client.fetchLyrics(s.title, s.artist);
            return valid(l) ? l : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 酷狗：关键词 = 歌手 + 歌名，带时长提高候选准确度 */
    private static String tryKugou(Song s) {
        try {
            String artist = s.artist == null ? "" : s.artist.trim();
            String keyword = artist.isEmpty() || "未知歌手".equals(artist)
                    ? s.title : artist + " " + s.title;
            return KugouLyricClient.fetch(keyword, s.duration);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean valid(String lrc) {
        return lrc != null && !lrc.trim().isEmpty();
    }

    /** 把歌词写成与音频同目录、同名的 .lrc（UTF-8） */
    public static void saveToLocal(Song song, String lrc) {
        try {
            File audio = new File(song.path);
            File dir = audio.getParentFile();
            if (dir == null) return;
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File out = new File(dir, base + ".lrc");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                fos.write(lrc.getBytes(StandardCharsets.UTF_8));
                android.util.Log.d("LrcFetch", "saved: " + out.getAbsolutePath());
            } finally {
                fos.close();
            }
        } catch (Exception e) {
            android.util.Log.e("LrcFetch", "save failed", e);
        }
    }
}

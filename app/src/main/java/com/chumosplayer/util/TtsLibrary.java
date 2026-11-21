package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文本转音频的生成记录（最近 30 条）。
 *
 * WAV 落在 filesDir/tts_audio（应用私有，无需权限、卸载自动清理），
 * 元数据存 SharedPreferences，已纳入 BackupManager 备份。
 * 跨设备恢复后文件不存在的条目会在读取时自动过滤，不会留下死链。
 */
public final class TtsLibrary {

    public static final String PREFS = "tts_library";
    private static final String KEY_ITEMS = "items";
    private static final int LIMIT = 30;

    public static class Entry {
        public String title;
        public String text;
        public String path;
        public long time;
        public int chars;
    }

    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<ArrayList<Entry>>() { }.getType();

    private TtsLibrary() { }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "tts_audio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File newFile(Context c) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(new Date());
        return new File(dir(c),
                "tts_" + stamp + "_" + (System.currentTimeMillis() % 100000L) + ".wav");
    }

    public static List<Entry> getAll(Context c) {
        String raw = prefs(c).getString(KEY_ITEMS, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            ArrayList<Entry> list = GSON.fromJson(raw, TYPE);
            if (list == null) return new ArrayList<>();
            List<Entry> out = new ArrayList<>();
            for (Entry e : list) {
                if (e == null || e.path == null) continue;
                if (!new File(e.path).exists()) continue;
                out.add(e);
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void save(Context c, List<Entry> list) {
        prefs(c).edit().putString(KEY_ITEMS, GSON.toJson(list)).apply();
    }

    public static void add(Context c, Entry e) {
        List<Entry> list = getAll(c);
        list.add(0, e);
        while (list.size() > LIMIT) {
            deleteFile(list.remove(list.size() - 1));
        }
        save(c, list);
    }

    public static void remove(Context c, String path) {
        List<Entry> list = getAll(c);
        for (int i = 0; i < list.size(); i++) {
            if (path.equals(list.get(i).path)) {
                deleteFile(list.remove(i));
                break;
            }
        }
        save(c, list);
    }

    public static void clear(Context c) {
        for (Entry e : getAll(c)) deleteFile(e);
        save(c, new ArrayList<Entry>());
    }

    private static void deleteFile(Entry e) {
        if (e == null || e.path == null) return;
        try {
            new File(e.path).delete();
        } catch (Exception ignore) { }
    }

    /** 优先用外部 Intent 指定的标题，否则截取正文首行 */
    public static String makeTitle(String text, String suggested) {
        if (suggested != null && !suggested.trim().isEmpty()) {
            String s = suggested.trim().replaceAll("[\\r\\n]+", " ");
            return s.length() > 24 ? s.substring(0, 24) + "…" : s;
        }
        String t = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").trim();
        if (t.isEmpty()) return "文本朗读";
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    public static Song toSong(Context c, Entry e) {
        Song s = new Song();
        s.title = e.title == null ? "文本朗读" : e.title;
        s.artist = c.getString(R.string.tts_artist);
        s.album = c.getString(R.string.tts_album);
        s.path = e.path;
        s.online = false;
        s.duration = wavDurationMs(e.path);
        return s;
    }

    /** 从标准 44 字节 WAV 头估算时长；解析失败返回 0，播放时 MediaPlayer 会自行读取真实时长 */
    private static long wavDurationMs(String path) {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            if (f.length() < 44) return 0;
            f.seek(22);
            int channels = Short.reverseBytes(f.readShort()) & 0xFFFF;
            f.seek(24);
            long sampleRate = Integer.reverseBytes(f.readInt()) & 0xFFFFFFFFL;
            f.seek(34);
            int bits = Short.reverseBytes(f.readShort()) & 0xFFFF;
            long bytesPerSec = sampleRate * channels * (bits / 8);
            if (bytesPerSec <= 0) return 0;
            return (f.length() - 44) * 1000L / bytesPerSec;
        } catch (Exception e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

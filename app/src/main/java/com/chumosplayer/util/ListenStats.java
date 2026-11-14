package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.chumosplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 听歌统计（新增功能）。
 *
 * 只用 SharedPreferences + Gson JsonObject 存，不依赖反射模型，R8 收缩后仍然稳定。
 * 记录三项：累计播放次数、累计收听时长、每首歌 / 每位歌手的播放次数。
 * prefs 名为 listen_stats，已接入 BackupManager 的备份 / 恢复。
 */
public final class ListenStats {

    public static final String PREFS = "listen_stats";
    private static final String K_PLAYS = "plays";
    private static final String K_MS = "ms";
    private static final String K_SONGS = "songs";   // key -> 次数
    private static final String K_ARTISTS = "artists"; // 歌手 -> 次数
    private static final String SEP = "\u001F";     // 单位分隔符，避免与歌名冲突

    private ListenStats() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记录一次播放（每首歌开始播放时调用一次） */
    public static void recordPlay(Context c, Song song) {
        if (c == null || song == null) return;
        try {
            SharedPreferences p = sp(c);
            JsonObject songs = obj(p.getString(K_SONGS, null));
            JsonObject artists = obj(p.getString(K_ARTISTS, null));
            String songKey = song.title + SEP + song.artist;
            bump(songs, songKey);
            String artist = (song.artist == null || song.artist.isEmpty()
                    || "<unknown>".equals(song.artist)) ? "未知歌手" : song.artist;
            bump(artists, artist);
            p.edit()
                    .putInt(K_PLAYS, p.getInt(K_PLAYS, 0) + 1)
                    .putString(K_SONGS, songs.toString())
                    .putString(K_ARTISTS, artists.toString())
                    .apply();
        } catch (Exception ignore) { }
    }

    /** 累加收听时长（毫秒）；由播放服务的 30 秒心跳在「正在播放」时调用 */
    public static void addListeningMs(Context c, long ms) {
        if (c == null || ms <= 0 || ms > 60000L) return;
        SharedPreferences p = sp(c);
        p.edit().putLong(K_MS, p.getLong(K_MS, 0L) + ms).apply();
    }

    public static int totalPlays(Context c) {
        return sp(c).getInt(K_PLAYS, 0);
    }

    public static long totalMs(Context c) {
        return sp(c).getLong(K_MS, 0L);
    }

    public static int uniqueSongs(Context c) {
        return obj(sp(c).getString(K_SONGS, null)).size();
    }

    /** 格式化时长：不足 1 小时显示分钟，超过显示小时（保留 1 位小数） */
    public static String formatDuration(Context c) {
        long min = totalMs(c) / 60000L;
        if (min < 60) return String.valueOf(min);
        return String.format(java.util.Locale.CHINA, "%.1f", min / 60.0);
    }

    /** 时长单位：分钟 / 小时，供界面切换文案 */
    public static boolean isHourUnit(Context c) {
        return totalMs(c) / 60000L >= 60;
    }

    /** 排行条目：name 主标题，sub 副标题（歌手 / 次数），count 播放次数 */
    public static class Entry {
        public final String name;
        public final String sub;
        public final int count;

        Entry(String name, String sub, int count) {
            this.name = name;
            this.sub = sub;
            this.count = count;
        }
    }

    public static List<Entry> topSongs(Context c, int limit) {
        JsonObject o = obj(sp(c).getString(K_SONGS, null));
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String[] parts = e.getKey().split(SEP, -1);
            String title = parts.length > 0 ? parts[0] : e.getKey();
            String artist = parts.length > 1 ? parts[1] : "";
            out.add(new Entry(title, artist, intOf(e.getValue())));
        }
        sort(out, limit);
        return out;
    }

    public static List<Entry> topArtists(Context c, int limit) {
        JsonObject o = obj(sp(c).getString(K_ARTISTS, null));
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            out.add(new Entry(e.getKey(), "", intOf(e.getValue())));
        }
        sort(out, limit);
        return out;
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    private static void sort(List<Entry> list, int limit) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return b.count - a.count; }
        });
        if (limit > 0 && list.size() > limit) return; // 调用方按需截取
    }

    /** 截取前 n 条（sort 不裁剪，保持调用方语义清晰） */
    public static List<Entry> head(List<Entry> list, int n) {
        return new ArrayList<>(list.subList(0, Math.min(n, list.size())));
    }

    private static void bump(JsonObject o, String key) {
        int cur = o.has(key) ? intOf(o.get(key)) : 0;
        o.addProperty(key, cur + 1);
    }

    private static int intOf(JsonElement e) {
        try {
            return e.getAsInt();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static JsonObject obj(String json) {
        if (json == null || json.isEmpty()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            return new JsonObject();
        }
    }
}

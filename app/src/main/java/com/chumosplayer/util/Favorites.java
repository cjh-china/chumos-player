package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.chumosplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的收藏（歌曲红心）：SharedPreferences 存 JSON 歌曲数组。
 * - 存档用 {@link Song#archived()}：在线歌保留源占位地址，避免 CDN 直链过期
 * - 判重复用 {@link PlaylistStore#sameSong}，与歌单口径一致
 * - 随「备份设置」一起导出（prefs 名为 favorites）
 */
public final class Favorites {

    private static final String PREFS = "favorites";
    private static final String KEY = "list";

    private Favorites() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全部收藏（容错：坏数据返回空列表） */
    public static List<Song> getAll(Context c) {
        List<Song> out = new ArrayList<>();
        String json = sp(c).getString(KEY, null);
        if (json == null || json.isEmpty()) return out;
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            Gson g = new Gson();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Song s = g.fromJson(e, Song.class);
                if (s != null) out.add(s);
            }
        } catch (Exception ignore) { }
        return out;
    }

    private static void save(Context c, List<Song> list) {
        try {
            sp(c).edit().putString(KEY, new Gson().toJson(list)).apply();
        } catch (Exception ignore) { }
    }

    /** 是否已收藏 */
    public static boolean isFavorite(Context c, Song song) {
        if (song == null) return false;
        for (Song s : getAll(c)) {
            if (PlaylistStore.sameSong(s, song)) return true;
        }
        return false;
    }

    /** 加入收藏；已存在返回 false */
    public static boolean add(Context c, Song song) {
        if (song == null) return false;
        List<Song> all = getAll(c);
        for (Song s : all) {
            if (PlaylistStore.sameSong(s, song)) return false;
        }
        all.add(song.archived());
        save(c, all);
        return true;
    }

    /** 取消收藏；原本就不在返回 false */
    public static boolean remove(Context c, Song song) {
        if (song == null) return false;
        List<Song> all = getAll(c);
        boolean changed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (PlaylistStore.sameSong(all.get(i), song)) {
                all.remove(i);
                changed = true;
            }
        }
        if (changed) save(c, all);
        return changed;
    }

    /** 切换收藏状态，返回切换后是否已收藏 */
    public static boolean toggle(Context c, Song song) {
        return isFavorite(c, song) ? !remove(c, song) : add(c, song);
    }
}

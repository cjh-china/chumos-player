package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.chumosplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放历史：记录最近播放的歌曲（最多 max 首，去重后最新在前）。
 * 用 Gson 序列化为 JSON 存在 SharedPreferences。
 */
public final class PlayHistory {

    private static final String PREFS = "play_history";
    private static final String KEY = "songs";
    private static final int MAX = 100;

    private PlayHistory() {}

    /** 记录一次播放（同曲去重，最新置顶） */
    public static void record(Context c, Song song) {
        if (song == null) return;
        List<Song> list = getAll(c);
        // 去重：标题+歌手+路径相同视为同一首
        String key = keyOf(song);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equals(keyOf(list.get(i)))) list.remove(i);
        }
        list.add(0, song);
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(c, list);
    }

    public static List<Song> getAll(Context c) {
        try {
            String json = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null);
            if (json == null || json.isEmpty()) return new ArrayList<>();
            Type t = new TypeToken<List<Song>>() {}.getType();
            List<Song> list = new Gson().fromJson(json, t);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** 从历史里移除指定歌曲（删除本地文件后调用） */
    public static void remove(Context c, Song song) {
        if (song == null) return;
        String key = keyOf(song);
        List<Song> list = getAll(c);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equals(keyOf(list.get(i)))) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) save(c, list);
    }

    private static void save(Context c, List<Song> list) {
        try {
            String json = new Gson().toJson(list);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, json).apply();
        } catch (Exception ignore) { }
    }

    private static String keyOf(Song s) {
        return (s.title == null ? "" : s.title) + "|"
                + (s.artist == null ? "" : s.artist) + "|"
                + (s.path == null ? "" : s.path);
    }
}

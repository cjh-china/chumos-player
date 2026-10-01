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
import java.util.UUID;

/**
 * 歌单持久化：SharedPreferences 存一段 JSON（Gson）。
 * 支持 新建/重命名/删除/加歌(去重)/移除/上移下移/导出/导入。
 * 歌曲按 {@link Song#archived()} 存档：在线歌存源占位地址，避免 CDN 直链过期。
 */
public final class PlaylistStore {

    public static class Playlist {
        public String id;
        public String name;
        public long createdAt;
        public List<Song> songs = new ArrayList<>();
    }

    private static final String PREFS = "playlists";
    private static final String KEY = "data";

    private PlaylistStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读全部歌单（容错：坏数据返回空列表而不是崩溃） */
    public static List<Playlist> load(Context c) {
        List<Playlist> out = new ArrayList<>();
        String json = sp(c).getString(KEY, null);
        if (json == null || json.isEmpty()) return out;
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            Gson g = new Gson();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Playlist p = g.fromJson(e, Playlist.class);
                if (p == null) continue;
                if (p.songs == null) p.songs = new ArrayList<>();
                if (p.name == null || p.name.trim().isEmpty()) p.name = "未命名歌单";
                if (p.id == null) p.id = UUID.randomUUID().toString();
                out.add(p);
            }
        } catch (Exception ignore) { }
        return out;
    }

    public static void save(Context c, List<Playlist> list) {
        try {
            sp(c).edit().putString(KEY, new Gson().toJson(list)).apply();
        } catch (Exception ignore) { }
    }

    public static Playlist create(Context c, String name) {
        List<Playlist> all = load(c);
        Playlist p = new Playlist();
        p.id = UUID.randomUUID().toString();
        p.name = (name == null || name.trim().isEmpty()) ? "新建歌单" : name.trim();
        p.createdAt = System.currentTimeMillis();
        all.add(p);
        save(c, all);
        return p;
    }

    public static void rename(Context c, String id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        List<Playlist> all = load(c);
        for (Playlist p : all) {
            if (p.id != null && p.id.equals(id)) p.name = name.trim();
        }
        save(c, all);
    }

    public static void delete(Context c, String id) {
        List<Playlist> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id != null && all.get(i).id.equals(id)) all.remove(i);
        }
        save(c, all);
    }

    public static Playlist byId(Context c, String id) {
        for (Playlist p : load(c)) {
            if (p.id != null && p.id.equals(id)) return p;
        }
        return null;
    }

    /** 加歌（存档副本、按源地址/路径去重）。返回 false 表示歌单里已经有了 */
    public static boolean addSong(Context c, String id, Song s) {
        if (s == null) return false;
        List<Playlist> all = load(c);
        for (Playlist p : all) {
            if (p.id == null || !p.id.equals(id)) continue;
            Song arch = s.archived();
            for (Song x : p.songs) {
                if (sameSong(x, arch)) return false;
            }
            p.songs.add(arch);
            save(c, all);
            return true;
        }
        return false;
    }

    /** 整单替换歌曲列表（移除/排序后调用） */
    public static void updateSongs(Context c, String id, List<Song> songs) {
        List<Playlist> all = load(c);
        for (Playlist p : all) {
            if (p.id != null && p.id.equals(id)) {
                p.songs = songs == null ? new ArrayList<Song>() : songs;
            }
        }
        save(c, all);
    }

    /** 从所有歌单里移除某首歌（删除本地文件后调用） */
    public static void removeFromAll(Context c, Song song) {
        if (song == null) return;
        List<Playlist> all = load(c);
        boolean changed = false;
        for (Playlist p : all) {
            for (int i = p.songs.size() - 1; i >= 0; i--) {
                if (sameSong(p.songs.get(i), song)) {
                    p.songs.remove(i);
                    changed = true;
                }
            }
        }
        if (changed) save(c, all);
    }

    /** 两首歌是否同一首（优先比源占位地址，其次文件路径，最后标题+歌手） */
    public static boolean sameSong(Song a, Song b) {
        if (a == null || b == null) return false;
        String ka = archiveKey(a), kb = archiveKey(b);
        return ka != null && ka.equals(kb);
    }

    private static String archiveKey(Song s) {
        String u = (s.sourceUrl != null && !s.sourceUrl.isEmpty()) ? s.sourceUrl : s.url;
        if (u != null && !u.isEmpty()) return "u:" + u;
        if (s.path != null && !s.path.isEmpty()) return "f:" + s.path;
        return "t:" + (s.title == null ? "" : s.title) + "|" + (s.artist == null ? "" : s.artist);
    }

    /** 导出为 JSON 文本（失败返回 null） */
    public static String exportJson(Context c) {
        try {
            return new Gson().toJson(load(c));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 JSON 文本导入。返回导入的歌单数；0 = 没有可用歌单；-1 = 格式错误。
     * 同名歌单做合并（按歌曲去重），不会覆盖已有内容。
     */
    public static int importJson(Context c, String json) {
        if (json == null || json.trim().isEmpty()) return -1;
        try {
            JsonArray arr = JsonParser.parseString(json.trim()).getAsJsonArray();
            Gson g = new Gson();
            List<Playlist> incoming = new ArrayList<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Playlist p = g.fromJson(e, Playlist.class);
                if (p == null) continue;
                if (p.songs == null) p.songs = new ArrayList<>();
                if (p.name == null || p.name.trim().isEmpty()) p.name = "导入的歌单";
                p.id = UUID.randomUUID().toString();   // 重新发号，避免与本地冲突
                p.createdAt = System.currentTimeMillis();
                incoming.add(p);
            }
            if (incoming.isEmpty()) return 0;

            List<Playlist> all = load(c);
            for (Playlist inc : incoming) {
                Playlist target = null;
                for (Playlist p : all) {
                    if (p.name.equals(inc.name)) { target = p; break; }
                }
                if (target == null) {
                    all.add(inc);
                    continue;
                }
                for (Song s : inc.songs) {
                    boolean dup = false;
                    for (Song x : target.songs) {
                        if (sameSong(x, s)) { dup = true; break; }
                    }
                    if (!dup) target.songs.add(s);
                }
            }
            save(c, all);
            return incoming.size();
        } catch (Exception e) {
            return -1;
        }
    }
}

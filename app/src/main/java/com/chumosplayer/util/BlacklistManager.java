package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 本地音乐黑名单：被屏蔽的文件夹，其下歌曲在扫描/搜索时直接跳过。
 * 用 SharedPreferences 持久化，路径按 canonical path 比较。
 */
public final class BlacklistManager {

    private static final String PREFS = "music_blacklist";
    private static final String KEY = "blocked_dirs";

    private BlacklistManager() {}

    /** 当前黑名单文件夹集合（返回副本，可安全修改） */
    public static Set<String> getAll(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet(KEY, new HashSet<>()));
    }

    /** 加入黑名单 */
    public static void add(Context ctx, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        Set<String> set = getAll(ctx);
        set.add(canonical(dir));
        save(ctx, set);
    }

    /** 移出黑名单 */
    public static void remove(Context ctx, String dir) {
        Set<String> set = getAll(ctx);
        set.remove(canonical(dir));
        save(ctx, set);
    }

    /** 某文件路径是否位于黑名单目录下 */
    public static boolean isBlocked(Context ctx, String filePath) {
        if (filePath == null) return false;
        String p = canonical(filePath);
        for (String dir : getAll(ctx)) {
            if (p.equals(dir) || p.startsWith(dir + File.separator)) return true;
        }
        return false;
    }

    private static void save(Context ctx, Set<String> set) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, new HashSet<>(set)).apply();
    }

    private static String canonical(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return path;
        }
    }
}

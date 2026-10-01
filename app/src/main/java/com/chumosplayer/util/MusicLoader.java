package com.chumosplayer.util;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.chumosplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

/** 扫描本地音乐（MediaStore），Android 5 ~ 最新版本均可用 */
public class MusicLoader {

    /** 全部本地音乐 */
    public static List<Song> loadLocal(Context ctx) {
        return query(ctx, null, null);
    }

    /** 最近 days 天添加的本地音乐 */
    public static List<Song> loadRecent(Context ctx, int days) {
        long since = System.currentTimeMillis() / 1000L - (long) days * 86400L;
        return query(ctx, MediaStore.Audio.Media.DATE_ADDED + ">=?",
                new String[]{String.valueOf(since)});
    }

    private static List<Song> query(Context ctx, String extraSel, String[] extraArgs) {
        List<Song> list = new ArrayList<>();
        // 黑名单目录：其下歌曲直接跳过
        java.util.Set<String> blocked = BlacklistManager.getAll(ctx);
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
        };
        String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0 AND "
                + MediaStore.Audio.Media.DURATION + ">=30000"; // 过滤短音效
        if (extraSel != null) sel += " AND " + extraSel;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, proj, sel, extraArgs,
                    MediaStore.Audio.Media.TITLE + " ASC");
            if (c != null) {
                while (c.moveToNext()) {
                    Song s = new Song();
                    s.id = c.getLong(0);
                    s.title = c.getString(1);
                    s.artist = c.getString(2);
                    s.album = c.getString(3);
                    s.path = c.getString(4);
                    s.duration = c.getLong(5);
                    s.online = false;
                    // 过滤媒体库中的失效记录（文件已被删除/移动）
                    if (s.path == null || s.title == null) continue;
                    if (!new java.io.File(s.path).exists()) continue;
                    // 跳过黑名单文件夹下的歌曲
                    if (isUnderAny(s.path, blocked)) continue;
                    list.add(s);
                }
            }
        } finally {
            if (c != null) c.close();
        }
        // 手动扫描的目录只并入"全部本地音乐"，最近添加仍以 MediaStore 时间为准
        if (extraSel == null) appendManualDirs(ctx, list, blocked);
        return list;
    }

    /**
     * 手动扫描目录：把 MediaStore 未收录的音频文件也并进来（APlayer 的「手动扫描目录」）。
     * 与已有条目按 canonical path 去重，屏蔽目录同样跳过；这些歌曲随后会被
     * MediaScanner 收录进媒体库，届时由 MediaStore 提供时长与封面。
     */
    private static void appendManualDirs(Context ctx, List<Song> list,
                                         java.util.Set<String> blocked) {
        java.util.Set<String> dirs = SettingsManager.getScanDirs(ctx);
        if (dirs.isEmpty()) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Song s : list) {
            if (s.path != null) seen.add(canonical(s.path));
        }
        for (String d : dirs) {
            collectAudio(new java.io.File(d), list, seen, blocked, 0);
        }
    }

    private static void collectAudio(java.io.File dir, List<Song> out,
                                     java.util.Set<String> seen,
                                     java.util.Set<String> blocked, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                collectAudio(f, out, seen, blocked, depth + 1);
                continue;
            }
            if (!isAudioName(f.getName())) continue;
            if (f.length() < 100L * 1024) continue;   // 太小的多半不是歌
            String p = canonical(f.getAbsolutePath());
            if (seen.contains(p) || isUnderAny(p, blocked)) continue;
            seen.add(p);
            Song s = new Song();
            s.path = p;
            s.title = stripExt(f.getName());
            s.artist = "未知歌手";
            s.album = "手动扫描";
            s.duration = 0;
            s.online = false;
            out.add(s);
        }
    }

    public static boolean isAudioName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".ape") || n.endsWith(".wma");
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String canonical(String p) {
        try {
            return new java.io.File(p).getCanonicalPath();
        } catch (Exception e) {
            return p;
        }
    }

    /** 判断文件路径是否位于任一黑名单目录下 */
    private static boolean isUnderAny(String path, java.util.Set<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return false;
        String p;
        try {
            p = new java.io.File(path).getCanonicalPath();
        } catch (Exception e) {
            p = path;
        }
        for (String d : dirs) {
            if (p.equals(d) || p.startsWith(d + java.io.File.separator)) return true;
        }
        return false;
    }

    /** 本地歌曲封面 URI */
    public static Uri albumArtUri(long songId) {
        return ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), songId);
    }

    /** 触发媒体库扫描手动目录下的音频（异步），让它们带上时长与封面 */
    public static void scanDirectory(final Context ctx, final java.io.File dir) {
        if (ctx == null || dir == null) return;
        new Thread(() -> {
            try {
                final java.util.List<String> paths = new ArrayList<>();
                collectPaths(dir, paths, 0);
                if (paths.isEmpty()) return;
                android.media.MediaScannerConnection.scanFile(ctx,
                        paths.toArray(new String[0]), null, null);
            } catch (Exception ignore) { }
        }, "manual-scan").start();
    }

    private static void collectPaths(java.io.File dir, List<String> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                collectPaths(f, out, depth + 1);
            } else if (isAudioName(f.getName()) && f.length() >= 100L * 1024) {
                out.add(f.getAbsolutePath());
            }
        }
    }
}

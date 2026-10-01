package com.chumosplayer.util;

import android.os.Environment;

import com.chumosplayer.model.Song;

import java.io.File;

/** 本地歌词文件（.lrc）查找与读取，UTF-8 优先、回退 GBK */
public final class LocalLrcLoader {

    private LocalLrcLoader() {}

    /** 读取与歌曲同名（同目录 / 默认下载目录）的 .lrc 内容，找不到返回 null */
    public static String load(Song song) {
        if (song.online || song.path == null) return null; // 在线歌词来自搜索结果
        String base = stripExt(new File(song.path).getName());
        // 1) 与音频同目录（大小写两种扩展名都试）
        File dir = new File(song.path).getParentFile();
        if (dir != null) {
            for (String ext : new String[]{".lrc", ".LRC"}) {
                File f = new File(dir, base + ext);
                if (f.exists()) return readFile(f);
            }
        }
        // 2) 默认下载目录 Music/MyFreeMP3
        File dl = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "MyFreeMP3");
        for (String ext : new String[]{".lrc", ".LRC"}) {
            File f = new File(dl, base + ext);
            if (f.exists()) return readFile(f);
        }
        return null;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    static String readFile(File f) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            byte[] bytes = bos.toByteArray();
            // BOM 检测 + UTF-8 优先，出现替换符时回退 GBK
            String text = new String(bytes, "UTF-8");
            if (text.indexOf('\uFFFD') >= 0) {
                try { text = new String(bytes, "GBK"); } catch (Exception ignore) {}
            }
            return text.trim();
        } catch (Exception e) {
            return null;
        }
    }
}

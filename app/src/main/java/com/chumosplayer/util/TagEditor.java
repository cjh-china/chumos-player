package com.chumosplayer.util;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;

/** 音频标签读写（标题/艺术家/专辑），直接修改文件本体 */
public final class TagEditor {

    public static class Tags {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String lyrics = "";
    }

    private TagEditor() {}

    /** 读取标签；失败返回 null */
    public static Tags read(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            Tags t = new Tags();
            if (tag != null) {
                t.title = nullToEmpty(tag.getFirst(FieldKey.TITLE));
                t.artist = nullToEmpty(tag.getFirst(FieldKey.ARTIST));
                t.album = nullToEmpty(tag.getFirst(FieldKey.ALBUM));
                t.lyrics = nullToEmpty(tag.getFirst(FieldKey.LYRICS));
            }
            return t;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 写回标题/艺术家/专辑（不动歌词）；成功返回 true */
    public static boolean write(File f, String title, String artist, String album) {
        return write(f, title, artist, album, null);
    }

    /**
     * 写回标签。lyrics 传 null 表示不改动歌词字段，非空则写入
     * （MP3 走 USLT，FLAC/Vorbis 走 LYRICS，均由 jaudiotagger 处理）。
     */
    public static boolean write(File f, String title, String artist, String album, String lyrics) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTagOrCreateAndSetDefault();
            tag.setField(FieldKey.TITLE, title == null ? "" : title);
            tag.setField(FieldKey.ARTIST, artist == null ? "" : artist);
            tag.setField(FieldKey.ALBUM, album == null ? "" : album);
            if (lyrics != null) tag.setField(FieldKey.LYRICS, lyrics);
            af.commit();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}

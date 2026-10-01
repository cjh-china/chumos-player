package com.chumosplayer.model;

/** 统一的歌曲模型：本地歌曲与在线搜索结果共用 */
public class Song {
    public long id;              // 本地：MediaStore id；在线：站点返回的 id
    public String title;         // 歌名
    public String artist;        // 歌手
    public String album;         // 专辑
    public String path;          // 本地文件路径
    public String url;           // 在线播放/下载地址
    public String lrc;           // 歌词（data:text/plain 前缀的 base64 或纯文本）
    public String pic;           // 封面图地址（在线用）
    public long duration;        // 毫秒
    public boolean online;       // 是否为在线歌曲
    /** 解析前的源地址占位（B站为 bilibili://bvid）：存歌单用它，避免直链过期 */
    public String sourceUrl;

    public Song() {}

    /** 深拷一份（歌单/队列用，避免共享对象被解析直链等操作改脏） */
    public Song copy() {
        Song c = new Song();
        c.id = id;
        c.title = title;
        c.artist = artist;
        c.album = album;
        c.path = path;
        c.url = url;
        c.lrc = lrc;
        c.pic = pic;
        c.duration = duration;
        c.online = online;
        c.sourceUrl = sourceUrl;
        return c;
    }

    /** 存档用：在线歌曲优先存源占位地址，避免 CDN 直链过期后歌单失效 */
    public Song archived() {
        Song c = copy();
        if (sourceUrl != null && !sourceUrl.isEmpty()) c.url = sourceUrl;
        return c;
    }

    /** 展示用的副标题 */
    public String subtitle() {
        return (artist == null ? "未知歌手" : artist) + " - " + (album == null ? "未知专辑" : album);
    }
}

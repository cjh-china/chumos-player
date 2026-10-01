package com.chumosplayer.model;

import java.util.ArrayList;
import java.util.List;

/** 本地音乐分组（艺术家 / 专辑 / 文件夹） */
public class Group {
    public String name;
    public final List<Song> songs = new ArrayList<>();

    public Group(String name) {
        this.name = name;
    }
}

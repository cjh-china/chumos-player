package com.chumosplayer.util;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import com.chumosplayer.model.Song;

import java.util.List;

/**
 * 「加入歌单」通用弹层：本地 / 在线列表长按后共用。
 * 没有歌单时可以直接一步「新建歌单…」并把歌放进去。
 */
public final class PlaylistPicker {

    private PlaylistPicker() {}

    public static void show(final Context ctx, final Song song) {
        if (ctx == null || song == null) return;
        final List<PlaylistStore.Playlist> all = PlaylistStore.load(ctx);
        final String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name + "（" + all.get(i).songs.size() + " 首）";
        }
        names[all.size()] = "＋ 新建歌单…";
        new AlertDialog.Builder(ctx)
                .setTitle("加入歌单")
                .setItems(names, (d, which) -> {
                    if (which >= all.size()) {
                        promptNewName(ctx, song);
                        return;
                    }
                    addTo(ctx, all.get(which).id, song);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void addTo(Context ctx, String playlistId, Song song) {
        boolean ok = PlaylistStore.addSong(ctx, playlistId, song);
        Toast.makeText(ctx, ok ? "已加入歌单" : "这首歌已经在该歌单里了",
                Toast.LENGTH_SHORT).show();
    }

    private static void promptNewName(final Context ctx, final Song song) {
        final EditText et = new EditText(ctx);
        et.setHint("歌单名称");
        new AlertDialog.Builder(ctx)
                .setTitle("新建歌单")
                .setView(et)
                .setPositiveButton("创建并加入", (d, w) -> {
                    PlaylistStore.Playlist p = PlaylistStore.create(ctx, et.getText().toString());
                    addTo(ctx, p.id, song);
                })
                .setNegativeButton("取消", null)
                .show();
    }
}

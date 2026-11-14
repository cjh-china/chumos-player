package com.chumosplayer.util;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的歌曲操作菜单（新增功能）。
 *
 * 以前这些操作只能靠「长按」发现，且本地 / 在线两套菜单互不一致。
 * 现在列表项常驻「更多」按钮与长按走同一入口，操作项按歌曲来源自动裁剪。
 */
public final class SongActions {

    public interface Callback {
        default void showLyrics(Song s) { }
        default void editTags(Song s) { }
        default void deleteSong(Song s) { }
        default void blockFolder(Song s) { }
        /** 收藏状态或列表内容变化后刷新界面 */
        default void refresh() { }
    }

    private SongActions() {}

    public static void show(Context ctx, Song song, boolean local,
                            boolean tagEditorEnabled, Callback cb) {
        if (ctx == null || song == null) return;
        final Callback callback = cb == null ? new Callback() { } : cb;
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        add(items, actions, ctx.getString(R.string.action_play_next), () -> {
            if (!PlayService.isReady()) {
                toast(ctx, "播放服务还没就绪");
                return;
            }
            PlayService.playNextGlobal(song);
            toast(ctx, ctx.getString(R.string.toast_added_next));
        });

        add(items, actions, ctx.getString(R.string.action_add_queue), () -> {
            if (!PlayService.isReady()) {
                toast(ctx, "播放服务还没就绪");
                return;
            }
            PlayService.enqueueLastGlobal(song);
            toast(ctx, ctx.getString(R.string.toast_added_queue));
        });

        add(items, actions, ctx.getString(R.string.action_add_playlist),
                () -> PlaylistPicker.show(ctx, song));

        boolean fav = Favorites.isFavorite(ctx, song);
        add(items, actions, fav ? "取消收藏" : "收藏 ♥", () -> {
            boolean now = Favorites.toggle(ctx, song);
            toast(ctx, ctx.getString(now ? R.string.toast_favorite_add
                    : R.string.toast_favorite_remove));
            callback.refresh();
        });

        add(items, actions, ctx.getString(R.string.action_lyrics),
                () -> callback.showLyrics(song));

        if (local && tagEditorEnabled) {
            add(items, actions, ctx.getString(R.string.action_edit_tag),
                    () -> callback.editTags(song));
        }

        add(items, actions, ctx.getString(R.string.action_song_info),
                () -> showInfo(ctx, song));

        if (local) {
            add(items, actions, ctx.getString(R.string.action_delete),
                    () -> callback.deleteSong(song));
            add(items, actions, ctx.getString(R.string.action_block_folder),
                    () -> callback.blockFolder(song));
        }

        new AlertDialog.Builder(ctx)
                .setTitle(song.title)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showInfo(Context ctx, Song s) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(nullTo(s.title, "未知歌曲")).append('\n');
        sb.append("歌手：").append(nullTo(s.artist, "未知歌手")).append('\n');
        sb.append("专辑：").append(nullTo(s.album, "未知专辑")).append('\n');
        if (s.duration > 0) {
            long sec = s.duration / 1000;
            sb.append("时长：").append(String.format(java.util.Locale.CHINA,
                    "%d:%02d", sec / 60, sec % 60)).append('\n');
        }
        sb.append("来源：").append(s.online ? "在线" : "本地文件").append('\n');
        sb.append(s.online ? "地址：" : "路径：")
                .append(nullTo(s.online ? s.url : s.path, "—"));
        new AlertDialog.Builder(ctx)
                .setTitle("歌曲信息")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void add(List<String> items, List<Runnable> actions,
                            String label, Runnable action) {
        items.add(label);
        actions.add(action);
    }

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    private static String nullTo(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }
}

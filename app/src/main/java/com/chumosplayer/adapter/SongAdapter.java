package com.chumosplayer.adapter;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;
import com.chumosplayer.util.CoverLoader;
import com.chumosplayer.util.Favorites;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 歌曲列表通用适配器：本地 / 在线 / 收藏 / 历史 / 歌单共用 */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

    public interface Listener {
        void onItemClick(Song song, int position);
        void onLyricsClick(Song song);
        void onDownloadClick(Song song);
        /** 长按歌曲；默认不处理 */
        default void onItemLongClick(Song song) { }
        /** 常驻「更多」按钮；默认沿用长按逻辑，保证旧页面不改动也能用 */
        default void onMoreClick(Song song) { onItemLongClick(song); }
    }

    private final List<Song> data;
    private final Listener listener;
    /** 在线列表显示下载按钮；本地列表显示歌词按钮 */
    private final boolean showActions;
    /** 收藏 key 缓存：避免滚动时反复反序列化 JSON */
    private Set<String> favKeys;
    /** 当前正在播放的歌曲，用于列表内状态高亮 */
    private Song currentSong;

    public SongAdapter(List<Song> data, Listener listener, boolean showActions) {
        this.data = data;
        this.listener = listener;
        this.showActions = showActions;
    }

    /** 外部（Activity / Fragment）在 onResume 时同步播放状态 */
    public void setCurrentSong(Song song) {
        this.currentSong = song;
        notifyDataSetChanged();
    }

    /** 收藏状态变化后重新读取并刷新列表 */
    public void refreshFavorites(Context c) {
        Set<String> keys = new HashSet<>();
        for (Song s : Favorites.getAll(c)) keys.add(keyOf(s));
        favKeys = keys;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_song, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        final Song s = data.get(pos);
        Context ctx = h.itemView.getContext();
        if (favKeys == null) {
            Set<String> keys = new HashSet<>();
            for (Song f : Favorites.getAll(ctx)) keys.add(keyOf(f));
            favKeys = keys;
        }
        h.tvTitle.setText(s.title);
        h.tvSubtitle.setText(s.subtitle());
        CoverLoader.load(h.ivCover, s);

        // 每行只留一个主要动作按钮 + 更多，避免按钮堆砌
        h.btnLyrics.setVisibility(showActions ? View.GONE : View.VISIBLE);
        h.btnDownload.setVisibility(showActions ? View.VISIBLE : View.GONE);
        h.btnMore.setVisibility(View.VISIBLE);
        h.ivFav.setVisibility(favKeys.contains(keyOf(s)) ? View.VISIBLE : View.GONE);

        boolean playing = currentSong != null
                && keyOf(currentSong).equals(keyOf(s));
        h.playingBar.setVisibility(playing ? View.VISIBLE : View.GONE);
        h.tvTitle.setTextColor(playing ? accent(ctx)
                : ctx.getResources().getColor(R.color.text_primary));
        h.tvTitle.setTypeface(null, playing
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        h.itemView.setOnClickListener(v -> listener.onItemClick(s, h.getAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            listener.onMoreClick(s);
            return true;
        });
        h.btnLyrics.setOnClickListener(v -> listener.onLyricsClick(s));
        h.btnDownload.setOnClickListener(v -> listener.onDownloadClick(s));
        h.btnMore.setOnClickListener(v -> listener.onMoreClick(s));
    }

    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    private static String keyOf(Song s) {
        if (s == null) return "";
        return (s.title == null ? "" : s.title) + "|"
                + (s.artist == null ? "" : s.artist) + "|"
                + (s.path == null ? "" : s.path);
    }

    private static int accent(Context c) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(R.attr.appAccent, tv, true) && tv.data != 0) {
            return tv.data;
        }
        return c.getResources().getColor(R.color.accent_active);
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvSubtitle;
        ImageView ivCover, ivFav;
        ImageButton btnLyrics, btnDownload, btnMore;
        View playingBar;

        Holder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_title);
            tvSubtitle = v.findViewById(R.id.tv_subtitle);
            ivCover = v.findViewById(R.id.iv_cover);
            ivFav = v.findViewById(R.id.iv_fav);
            btnLyrics = v.findViewById(R.id.btn_lyrics);
            btnDownload = v.findViewById(R.id.btn_download);
            btnMore = v.findViewById(R.id.btn_more);
            playingBar = v.findViewById(R.id.v_playing_bar);
        }
    }
}

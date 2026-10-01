package com.chumosplayer.adapter;

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

import java.util.List;

/** 歌曲列表通用适配器（本地/在线共用 item_song 布局） */
public class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

    public interface Listener {
        void onItemClick(Song song, int position);
        void onLyricsClick(Song song);
        void onDownloadClick(Song song);
        /** 长按歌曲（本地列表用于屏蔽文件夹），默认不处理 */
        default void onItemLongClick(Song song) { }
    }

    private final List<Song> data;
    private final Listener listener;
    /** 在线列表才显示歌词/下载按钮 */
    private final boolean showActions;

    public SongAdapter(List<Song> data, Listener listener, boolean showActions) {
        this.data = data;
        this.listener = listener;
        this.showActions = showActions;
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
        h.tvTitle.setText(s.title);
        h.tvSubtitle.setText(s.subtitle());
        CoverLoader.load(h.ivCover, s);
        // 歌词按钮始终显示（本地歌曲读同名 .lrc）；下载按钮仅在线列表显示
        h.btnLyrics.setVisibility(View.VISIBLE);
        h.btnDownload.setVisibility(showActions ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> listener.onItemClick(s, h.getAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            listener.onItemLongClick(s);
            return true;
        });
        h.btnLyrics.setOnClickListener(v -> listener.onLyricsClick(s));
        h.btnDownload.setOnClickListener(v -> listener.onDownloadClick(s));
    }

    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvSubtitle;
        ImageView ivCover;
        ImageButton btnLyrics, btnDownload;

        Holder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_title);
            tvSubtitle = v.findViewById(R.id.tv_subtitle);
            ivCover = v.findViewById(R.id.iv_cover);
            btnLyrics = v.findViewById(R.id.btn_lyrics);
            btnDownload = v.findViewById(R.id.btn_download);
        }
    }
}

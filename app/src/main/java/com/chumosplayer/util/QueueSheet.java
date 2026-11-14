package com.chumosplayer.util;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放队列管理（新增功能）。
 *
 * 以前只是一个纯文本 AlertDialog，只能点歌切换。
 * 现在用底部弹层展示队列，支持：当前曲目高亮、点击切歌、上移 / 下移、移除、清空。
 * 全部通过 PlayService 的全局控制桥操作，不需要各页面重复绑定服务。
 */
public final class QueueSheet {

    private QueueSheet() {}

    public static void show(Context ctx) {
        if (ctx == null) return;
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_queue, null);
        dialog.setContentView(root);

        RecyclerView rv = root.findViewById(R.id.recycler_queue);
        rv.setLayoutManager(new LinearLayoutManager(ctx));

        final TextView title = root.findViewById(R.id.tv_queue_title);
        final View empty = root.findViewById(R.id.queue_empty);
        final TextView emptyTitle = root.findViewById(R.id.tv_empty_title);
        final TextView emptyDesc = root.findViewById(R.id.tv_empty_desc);
        if (emptyTitle != null) emptyTitle.setText(R.string.empty_queue);
        if (emptyDesc != null) emptyDesc.setText("从歌曲列表点一首歌，或用「更多」加入队列");

        final QueueAdapter[] holder = new QueueAdapter[1];
        // 用数组承载回调，避免 lambda 自引用导致「变量可能未初始化」
        final Runnable[] refreshRef = new Runnable[1];
        final Runnable refresh = () -> {
            List<Song> q = PlayService.queueGlobal();
            int cur = PlayService.currentIndexGlobal();
            title.setText(ctx.getString(R.string.queue_title, q.size()));
            if (q.isEmpty()) {
                rv.setVisibility(View.GONE);
                empty.setVisibility(View.VISIBLE);
            } else {
                rv.setVisibility(View.VISIBLE);
                empty.setVisibility(View.GONE);
            }
            if (holder[0] == null) {
                holder[0] = new QueueAdapter(q, cur, refreshRef[0], dialog::dismiss);
                rv.setAdapter(holder[0]);
            } else {
                holder[0].update(q, cur);
            }
        };
        refreshRef[0] = refresh;
        refresh.run();

        View clear = root.findViewById(R.id.btn_queue_clear);
        if (clear != null) {
            clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.queue_clear)
                    .setMessage("清空播放队列并停止播放？")
                    .setPositiveButton(R.string.queue_clear, (d, w) -> {
                        PlayService.clearQueueGlobal();
                        refresh.run();
                        Toast.makeText(ctx, R.string.queue_empty_done, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
        }
        dialog.show();
    }

    public static int accent(Context c) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(R.attr.appAccent, tv, true) && tv.data != 0) {
            return tv.data;
        }
        return c.getResources().getColor(R.color.accent_active);
    }

    private static class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.H> {
        private List<Song> data;
        private int current;
        private final Runnable onChange;
        private final Runnable close;

        QueueAdapter(List<Song> d, int cur, Runnable onChange, Runnable close) {
            this.data = new ArrayList<>(d);
            this.current = cur;
            this.onChange = onChange;
            this.close = close;
        }

        void update(List<Song> d, int cur) {
            this.data = new ArrayList<>(d);
            this.current = cur;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_queue, parent, false);
            return new H(v);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Song s = data.get(pos);
            Context ctx = h.itemView.getContext();
            h.index.setText(String.format(java.util.Locale.CHINA, "%02d", pos + 1));
            h.title.setText(s.title == null ? "未知歌曲" : s.title);
            h.artist.setText(s.artist == null ? "" : s.artist);
            boolean playing = pos == current;
            h.playing.setVisibility(playing ? View.VISIBLE : View.GONE);
            h.title.setTextColor(playing ? accent(ctx)
                    : ctx.getResources().getColor(R.color.text_primary));
            h.title.setTypeface(null, playing ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);

            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                PlayService.playAtGlobal(p);
                close.run();
            });
            h.up.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p > 0) {
                    PlayService.moveGlobal(p, p - 1);
                    onChange.run();
                }
            });
            h.down.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p >= 0 && p < data.size() - 1) {
                    PlayService.moveGlobal(p, p + 1);
                    onChange.run();
                }
            });
            h.remove.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return;
                PlayService.removeAtGlobal(p);
                onChange.run();
                Toast.makeText(ctx, R.string.queue_removed, Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class H extends RecyclerView.ViewHolder {
            final TextView index, title, artist;
            final View playing;
            final ImageButton up, down, remove;

            H(View v) {
                super(v);
                index = v.findViewById(R.id.tv_queue_index);
                title = v.findViewById(R.id.tv_queue_title);
                artist = v.findViewById(R.id.tv_queue_artist);
                playing = v.findViewById(R.id.v_queue_playing);
                up = v.findViewById(R.id.btn_queue_up);
                down = v.findViewById(R.id.btn_queue_down);
                remove = v.findViewById(R.id.btn_queue_remove);
            }
        }
    }
}

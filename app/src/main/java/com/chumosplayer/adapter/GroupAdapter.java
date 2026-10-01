package com.chumosplayer.adapter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.model.Group;

import java.util.List;

/** 分组列表适配器（艺术家 / 专辑 / 文件夹）：显示名称 + 歌曲数 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.Holder> {

    public interface Listener {
        void onGroupClick(Group group);
    }

    private final List<Group> data;
    private final Listener listener;

    public GroupAdapter(List<Group> data, Listener listener) {
        this.data = data;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * parent.getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView title = new TextView(parent.getContext());
        title.setTextSize(16);
        title.setTextColor(parent.getContext().getResources()
                .getColor(com.chumosplayer.R.color.text_primary));
        TextView sub = new TextView(parent.getContext());
        sub.setTextSize(12);
        sub.setTextColor(parent.getContext().getResources()
                .getColor(com.chumosplayer.R.color.text_secondary));
        row.addView(title);
        row.addView(sub);
        Holder h = new Holder(row, title, sub);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        Group g = data.get(pos);
        h.title.setText(g.name);
        h.sub.setText(g.songs.size() + " 首");
        h.itemView.setOnClickListener(v -> listener.onGroupClick(g));
    }

    @Override
    public int getItemCount() {
        return data == null ? 0 : data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView title, sub;

        Holder(View v, TextView t, TextView s) {
            super(v);
            title = t;
            sub = s;
        }
    }
}

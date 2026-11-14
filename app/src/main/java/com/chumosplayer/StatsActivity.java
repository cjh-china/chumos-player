package com.chumosplayer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chumosplayer.util.ListenStats;
import com.chumosplayer.util.QueueSheet;

import java.util.List;

/** 听歌统计（新增功能）：累计播放 / 收听时长 / Top 歌曲与歌手 */
public class StatsActivity extends AppCompatActivity {

    private TextView tvPlays, tvTime, tvUnique;
    private LinearLayout llSongs, llArtists;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.chumosplayer.util.SettingsManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.stats_toolbar);
        setSupportActionBar(tb);
        tb.setNavigationOnClickListener(v -> finish());

        tvPlays = findViewById(R.id.tv_total_plays);
        tvTime = findViewById(R.id.tv_total_time);
        tvUnique = findViewById(R.id.tv_total_unique);
        llSongs = findViewById(R.id.ll_top_songs);
        llArtists = findViewById(R.id.ll_top_artists);

        findViewById(R.id.btn_clear_stats).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.stats_clear)
                        .setMessage("将清空累计播放次数、收听时长与排行榜，且不可恢复。")
                        .setPositiveButton(R.string.stats_clear, (d, w) -> {
                            ListenStats.clear(this);
                            refresh();
                            Toast.makeText(this, "统计已清空", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        tvPlays.setText(String.valueOf(ListenStats.totalPlays(this)));
        tvTime.setText(ListenStats.formatDuration(this));
        tvUnique.setText(String.valueOf(ListenStats.uniqueSongs(this)));
        render(llSongs, ListenStats.head(ListenStats.topSongs(this, 5), 5), true);
        render(llArtists, ListenStats.head(ListenStats.topArtists(this, 5), 5), false);
    }

    private void render(LinearLayout box, List<ListenStats.Entry> entries, boolean withSub) {
        box.removeAllViews();
        if (entries.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.stats_empty);
            tv.setTextSize(13);
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(16), 0, dp(16));
            box.addView(tv);
            return;
        }
        int accent = QueueSheet.accent(this);
        for (int i = 0; i < entries.size(); i++) {
            ListenStats.Entry e = entries.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView rank = new TextView(this);
            rank.setText(String.format(java.util.Locale.CHINA, "%02d", i + 1));
            rank.setTextSize(13);
            rank.setTypeface(null, Typeface.BOLD);
            rank.setTextColor(i == 0 ? accent
                    : getResources().getColor(R.color.text_secondary));
            rank.setLayoutParams(new LinearLayout.LayoutParams(dp(30),
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(this);
            name.setText(e.name);
            name.setTextSize(14);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            text.addView(name);
            if (withSub && e.sub != null && !e.sub.isEmpty()) {
                TextView sub = new TextView(this);
                sub.setText(e.sub);
                sub.setTextSize(11);
                sub.setSingleLine(true);
                sub.setTextColor(getResources().getColor(R.color.text_secondary));
                text.addView(sub);
            }

            TextView count = new TextView(this);
            count.setText(e.count + " 次");
            count.setTextSize(12);
            count.setTextColor(getResources().getColor(R.color.text_secondary));

            row.addView(rank);
            row.addView(text);
            row.addView(count);
            box.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

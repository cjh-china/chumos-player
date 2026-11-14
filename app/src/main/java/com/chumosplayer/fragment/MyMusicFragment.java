package com.chumosplayer.fragment;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.chumosplayer.FavoritesActivity;
import com.chumosplayer.HistoryActivity;
import com.chumosplayer.NowPlayingActivity;
import com.chumosplayer.PlaylistActivity;
import com.chumosplayer.R;
import com.chumosplayer.RemoteMusicActivity;
import com.chumosplayer.SettingsActivity;
import com.chumosplayer.StatsActivity;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.ListenStats;
import com.chumosplayer.util.MusicLoader;
import com.chumosplayer.util.PlayHistory;
import com.chumosplayer.util.QueueSheet;
import com.chumosplayer.util.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「我的音乐」聚合页（新增功能 + 信息架构重构）。
 *
 * 以前「收藏 / 歌单 / 历史 / 设置」全部藏在 Toolbar 溢出菜单里，只能靠回忆找到。
 * 现在一级入口常驻可见，并补充：听歌概览、最近播放、一键播放全部 / 随机播放、
 * 队列管理、音效与定时快捷入口。
 */
public class MyMusicFragment extends Fragment {

    private static final int REQ_READ = 31;
    private boolean pendingShuffle;

    private TextView tvSummary, tvPlays, tvTime, tvUnique, tvTimeUnit;
    private LinearLayout llRecent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_music, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tvSummary = view.findViewById(R.id.tv_mine_summary);
        tvPlays = view.findViewById(R.id.tv_stats_plays);
        tvTime = view.findViewById(R.id.tv_stats_time);
        tvUnique = view.findViewById(R.id.tv_stats_unique);
        tvTimeUnit = view.findViewById(R.id.tv_stats_time_unit);
        llRecent = view.findViewById(R.id.ll_recent);

        buildShortcuts(view);
        buildSmartChips(view);

        view.findViewById(R.id.btn_play_all).setOnClickListener(v -> playAll(false));
        view.findViewById(R.id.btn_shuffle_all).setOnClickListener(v -> playAll(true));
        view.findViewById(R.id.btn_view_stats).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), StatsActivity.class)));
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!isAdded()) return;
        tvPlays.setText(String.valueOf(ListenStats.totalPlays(requireContext())));
        tvTime.setText(ListenStats.formatDuration(requireContext()));
        tvTimeUnit.setText(ListenStats.isHourUnit(requireContext())
                ? R.string.stats_unit_hours : R.string.stats_unit_minutes);
        tvUnique.setText(String.valueOf(ListenStats.uniqueSongs(requireContext())));

        List<Song> favorites = com.chumosplayer.util.Favorites.getAll(requireContext());
        List<Song> history = PlayHistory.getAll(requireContext());
        tvSummary.setText("收藏 " + favorites.size() + " 首 · 听过 " + history.size() + " 首 · 队列 "
                + PlayService.queueGlobal().size() + " 首");
        renderRecent(history);
    }

    /** 最近播放：最多 4 首，点按直接回到那首歌 */
    private void renderRecent(final List<Song> history) {
        llRecent.removeAllViews();
        if (history.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.stats_empty);
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(requireContext().getResources().getColor(R.color.text_secondary));
            empty.setPadding(0, dp(12), 0, dp(12));
            llRecent.addView(empty);
            return;
        }
        final List<Song> queue = new ArrayList<>(history);
        int n = Math.min(4, queue.size());
        for (int i = 0; i < n; i++) {
            final Song s = queue.get(i);
            final int pos = i;
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundResource(R.drawable.bg_selectable);

            ImageView icon = new ImageView(requireContext());
            icon.setImageResource(R.drawable.ic_music_note);
            icon.setColorFilter(QueueSheet.accent(requireContext()));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.setMargins(dp(4), 0, dp(12), 0);
            icon.setLayoutParams(iconLp);

            LinearLayout text = new LinearLayout(requireContext());
            text.setOrientation(LinearLayout.VERTICAL);
            text.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView title = new TextView(requireContext());
            title.setText(s.title == null ? "未知歌曲" : s.title);
            title.setTextSize(14);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextColor(requireContext().getResources().getColor(R.color.text_primary));
            TextView sub = new TextView(requireContext());
            sub.setText(s.artist == null ? "" : s.artist);
            sub.setTextSize(11);
            sub.setSingleLine(true);
            sub.setTextColor(requireContext().getResources().getColor(R.color.text_secondary));
            text.addView(title);
            text.addView(sub);

            row.addView(icon);
            row.addView(text);
            row.setOnClickListener(v -> {
                if (!PlayService.isReady()) {
                    Toast.makeText(requireContext(), "播放服务还没就绪", Toast.LENGTH_SHORT).show();
                    return;
                }
                PlayService.playQueueGlobal(queue, pos);
                startActivity(new Intent(requireContext(), NowPlayingActivity.class));
            });
            llRecent.addView(row);
        }
    }

    /** 九个常驻入口，三行三列 */
    private void buildShortcuts(View root) {
        LinearLayout r1 = root.findViewById(R.id.shortcut_row1);
        LinearLayout r2 = root.findViewById(R.id.shortcut_row2);
        LinearLayout r3 = root.findViewById(R.id.shortcut_row3);

        addShortcut(r1, R.drawable.ic_favorite_border, R.string.mine_entry_favorites,
                v -> go(FavoritesActivity.class));
        addShortcut(r1, R.drawable.ic_playlist, R.string.mine_entry_playlists,
                v -> go(PlaylistActivity.class));
        addShortcut(r1, R.drawable.ic_history, R.string.mine_entry_history,
                v -> go(HistoryActivity.class));

        addShortcut(r2, R.drawable.ic_queue, R.string.mine_entry_queue,
                v -> QueueSheet.show(requireContext()));
        addShortcut(r2, R.drawable.ic_stats, R.string.mine_entry_stats,
                v -> go(StatsActivity.class));
        addShortcut(r2, R.drawable.ic_equalizer, R.string.mine_entry_equalizer,
                v -> showEqDialog());

        addShortcut(r3, R.drawable.ic_timer, R.string.mine_entry_timer,
                v -> showSleepDialog());
        addShortcut(r3, R.drawable.ic_cloud, R.string.mine_entry_remote,
                v -> go(RemoteMusicActivity.class));
        addShortcut(r3, R.drawable.ic_settings, R.string.mine_entry_settings,
                v -> go(SettingsActivity.class));
    }

    /** 智能歌单：4 个动态入口，点开即时按时间 / 次数 / 时长生成列表 */
    private void buildSmartChips(View root) {
        LinearLayout box = root.findViewById(R.id.ll_smart);
        addSmartChip(box, R.string.smart_recent,
                com.chumosplayer.SmartPlaylistActivity.MODE_RECENT_ADDED);
        addSmartChip(box, R.string.smart_top,
                com.chumosplayer.SmartPlaylistActivity.MODE_TOP_PLAYED);
        addSmartChip(box, R.string.smart_never,
                com.chumosplayer.SmartPlaylistActivity.MODE_NEVER_PLAYED);
        addSmartChip(box, R.string.smart_long,
                com.chumosplayer.SmartPlaylistActivity.MODE_LONGEST);
    }

    private void addSmartChip(LinearLayout box, int labelRes, final int mode) {
        TextView chip = new TextView(requireContext());
        chip.setText(labelRes);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(4), dp(10), dp(4), dp(10));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setTextColor(getResources().getColor(R.color.text_primary));
        chip.setClickable(true);
        chip.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(),
                    com.chumosplayer.SmartPlaylistActivity.class);
            i.putExtra(com.chumosplayer.SmartPlaylistActivity.EXTRA_MODE, mode);
            startActivity(i);
        });
        box.addView(chip);
    }

    private void addShortcut(LinearLayout row, int iconRes, int labelRes,
                             View.OnClickListener click) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(8), dp(4), dp(8));
        item.setBackgroundResource(R.drawable.bg_selectable);
        item.setClickable(true);
        item.setFocusable(true);
        item.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout iconBox = new FrameLayout(requireContext());
        iconBox.setBackgroundResource(R.drawable.bg_icon_circle);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(iconRes);
        icon.setColorFilter(QueueSheet.accent(requireContext()));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(22), dp(22));
        ip.gravity = Gravity.CENTER;
        icon.setLayoutParams(ip);
        iconBox.addView(icon);

        TextView label = new TextView(requireContext());
        label.setText(labelRes);
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setTextColor(requireContext().getResources().getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        label.setLayoutParams(lp);

        item.addView(iconBox);
        item.addView(label);
        item.setOnClickListener(click);
        row.addView(item);
    }

    /** 音效预设（与设置页同一套偏好，播放页也能直接调） */
    private void showEqDialog() {
        String[] presets = PlayService.eqPresetNamesGlobal();
        if (presets.length == 0) {
            Toast.makeText(requireContext(), "开始播放一首歌后即可调节音效",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean enabled = SettingsManager.isEqEnabled(requireContext());
        String[] items = new String[presets.length + 1];
        items[0] = "关闭音效";
        System.arraycopy(presets, 0, items, 1, presets.length);
        int checked = enabled ? SettingsManager.getEqPreset(requireContext()) + 1 : 0;
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.mine_entry_equalizer)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        SettingsManager.setEqEnabled(requireContext(), false);
                        PlayService.setEqEnabledGlobal(false);
                    } else {
                        SettingsManager.setEqEnabled(requireContext(), true);
                        SettingsManager.setEqPreset(requireContext(), which - 1);
                        PlayService.setEqEnabledGlobal(true);
                        PlayService.setEqPresetGlobal(which - 1);
                    }
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSleepDialog() {
        final String[] labels = {"关闭", "15 分钟", "30 分钟", "60 分钟", "本首歌结束"};
        final int[] minutes = {0, 15, 30, 60, -1};
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.mine_entry_timer)
                .setItems(labels, (d, which) -> {
                    int v = minutes[which];
                    if (v < 0) {
                        PlayService.setSleepMinutesGlobal(0);
                        PlayService.setSleepOnSongEndGlobal(true);
                    } else {
                        PlayService.setSleepOnSongEndGlobal(false);
                        PlayService.setSleepMinutesGlobal(v);
                    }
                    Toast.makeText(requireContext(),
                            v == 0 ? "睡眠定时已关闭" : "已设置：" + labels[which],
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 一键播放全部 / 随机播放全部（读取媒体库放后台线程，避免卡顿） */
    private void playAll(final boolean shuffle) {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingShuffle = shuffle;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
            return;
        }
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            List<Song> all = MusicLoader.loadLocal(app);
            if (shuffle) Collections.shuffle(all);
            final List<Song> result = all;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (result.isEmpty()) {
                    Toast.makeText(requireContext(), "未找到本地音乐", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!PlayService.isReady()) {
                    Toast.makeText(requireContext(), "播放服务还没就绪", Toast.LENGTH_SHORT).show();
                    return;
                }
                PlayService.playQueueGlobal(result, 0);
                startActivity(new Intent(requireContext(), NowPlayingActivity.class));
            });
        }, "play-all").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_READ && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            playAll(pendingShuffle);
        } else if (requestCode == REQ_READ) {
            Toast.makeText(requireContext(), R.string.permission_needed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void go(Class<?> cls) {
        startActivity(new Intent(requireContext(), cls));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

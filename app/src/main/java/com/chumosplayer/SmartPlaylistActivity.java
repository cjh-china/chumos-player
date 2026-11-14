package com.chumosplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.ListenStats;
import com.chumosplayer.util.MusicLoader;
import com.chumosplayer.util.SongActions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能歌单（v2.1）：不需要手动维护，按文件时间 / 播放次数 / 时长自动生成。
 * 四种模式：最近添加、最常播放、久未播放、超长曲目。
 */
public class SmartPlaylistActivity extends AppCompatActivity implements SongAdapter.Listener {

    public static final String EXTRA_MODE = "smart_mode";
    public static final int MODE_RECENT_ADDED = 1;
    public static final int MODE_TOP_PLAYED = 2;
    public static final int MODE_NEVER_PLAYED = 3;
    public static final int MODE_LONGEST = 4;
    private static final int LIMIT = 60;

    private final List<Song> data = new ArrayList<>();
    private SongAdapter adapter;
    private View emptyView;
    private TextView tvDesc;
    private int mode = MODE_RECENT_ADDED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.chumosplayer.util.SettingsManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smart_playlist);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_RECENT_ADDED);
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.smart_toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleRes());
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        tvDesc = findViewById(R.id.tv_smart_desc);
        tvDesc.setText(descRes());
        emptyView = findViewById(R.id.smart_empty);

        RecyclerView rv = findViewById(R.id.rv_smart);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter(data, this, false);
        rv.setAdapter(adapter);

        findViewById(R.id.btn_smart_play_all).setOnClickListener(v -> playAll(false));
        findViewById(R.id.btn_smart_shuffle).setOnClickListener(v -> playAll(true));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.setCurrentSong(PlayService.snapshotSong());
            adapter.refreshFavorites(this);
        }
    }

    private int titleRes() {
        switch (mode) {
            case MODE_TOP_PLAYED:    return R.string.smart_top;
            case MODE_NEVER_PLAYED:  return R.string.smart_never;
            case MODE_LONGEST:       return R.string.smart_long;
            default:                  return R.string.smart_recent;
        }
    }

    private int descRes() {
        switch (mode) {
            case MODE_TOP_PLAYED:
                return R.string.smart_desc_top;
            case MODE_NEVER_PLAYED:
                return R.string.smart_desc_never;
            case MODE_LONGEST:
                return R.string.smart_desc_long;
            default:
                return R.string.smart_desc_recent;
        }
    }

    private void load() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            final List<Song> result = build(app);
            runOnUiThread(() -> {
                data.clear();
                data.addAll(result);
                adapter.notifyDataSetChanged();
                adapter.setCurrentSong(PlayService.snapshotSong());
                updateEmpty();
                tvDesc.setText(getString(descRes()) + " · " + data.size() + " 首");
            });
        }, "smart-playlist").start();
    }

    /** 后台构建歌单：只读媒体库与统计偏好，不改动任何磁盘文件 */
    private List<Song> build(android.content.Context app) {
        List<Song> all;
        try {
            all = MusicLoader.loadLocal(app);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        switch (mode) {
            case MODE_RECENT_ADDED:
                Collections.sort(all, new Comparator<Song>() {
                    @Override public int compare(Song a, Song b) {
                        return Long.compare(lastModified(b), lastModified(a));
                    }
                });
                break;

            case MODE_LONGEST:
                Collections.sort(all, new Comparator<Song>() {
                    @Override public int compare(Song a, Song b) {
                        return Long.compare(b.duration, a.duration);
                    }
                });
                break;

            case MODE_TOP_PLAYED: {
                Map<String, Song> index = new HashMap<>();
                for (Song s : all) index.put(key(s), s);
                List<Song> ordered = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ListenStats.Entry e : ListenStats.topSongs(app, 0)) {
                    Song s = index.get(key(e.name, e.sub));
                    if (s != null && seen.add(key(s))) ordered.add(s);
                }
                all = ordered;
                break;
            }

            case MODE_NEVER_PLAYED: {
                Set<String> played = new HashSet<>();
                for (ListenStats.Entry e : ListenStats.topSongs(app, 0)) {
                    played.add(key(e.name, e.sub));
                }
                List<Song> rest = new ArrayList<>();
                for (Song s : all) {
                    if (!played.contains(key(s))) rest.add(s);
                }
                all = rest;
                break;
            }
        }
        return new ArrayList<>(all.subList(0, Math.min(LIMIT, all.size())));
    }

    private static long lastModified(Song s) {
        if (s.path == null) return 0L;
        try {
            return new java.io.File(s.path).lastModified();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 统计里用「歌名 + 歌手」作为一首歌的身份，和 ListenStats.recordPlay 保持一致 */
    private static String key(String title, String artist) {
        return (title == null ? "" : title) + "\u0001" + (artist == null ? "" : artist);
    }

    private static String key(Song s) {
        return key(s.title, s.artist);
    }

    private void updateEmpty() {
        boolean empty = data.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        TextView title = findViewById(R.id.tv_empty_title);
        TextView desc = findViewById(R.id.tv_empty_desc);
        if (title != null) title.setText(R.string.smart_empty);
        if (desc != null) desc.setText(R.string.smart_empty_desc);
        View action = findViewById(R.id.btn_empty_action);
        if (action != null) action.setVisibility(View.GONE);
    }

    private void playAll(boolean shuffle) {
        if (data.isEmpty()) {
            Toast.makeText(this, R.string.smart_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PlayService.isReady()) {
            Toast.makeText(this, "播放服务还没就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> queue = new ArrayList<>(data);
        if (shuffle) Collections.shuffle(queue);
        PlayService.playQueueGlobal(queue, 0);
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    @Override
    public void onItemClick(Song song, int position) {
        if (!PlayService.isReady()) {
            Toast.makeText(this, "播放服务还没就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        PlayService.playQueueGlobal(new ArrayList<>(data), position);
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    @Override
    public void onLyricsClick(Song song) { showActions(song); }

    @Override
    public void onDownloadClick(Song song) { showActions(song); }

    @Override
    public void onMoreClick(Song song) { showActions(song); }

    /** 复用统一歌曲操作菜单；删除交给「本地音乐」页，避免在浏览视图里误删 */
    private void showActions(Song song) {
        SongActions.show(this, song, true, false, new SongActions.Callback() {
            @Override public void showLyrics(Song s) {
                com.chumosplayer.fragment.LyricsDialogFragment.show(
                        getSupportFragmentManager(), s);
            }

            @Override public void refresh() {
                if (adapter != null) adapter.refreshFavorites(SmartPlaylistActivity.this);
            }

            @Override public void blockFolder(Song s) {
                if (s.path != null) {
                    java.io.File dir = new java.io.File(s.path).getParentFile();
                    if (dir != null) {
                        com.chumosplayer.util.BlacklistManager.add(
                                SmartPlaylistActivity.this, dir.getAbsolutePath());
                    }
                }
                load(); // 屏蔽后重新生成歌单
            }

            @Override public void deleteSong(Song s) {
                Toast.makeText(SmartPlaylistActivity.this,
                        "删除歌曲请到「本地音乐」列表操作，避免误删",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}

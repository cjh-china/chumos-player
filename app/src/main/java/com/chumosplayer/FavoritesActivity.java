package com.chumosplayer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.fragment.LyricsDialogFragment;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.Favorites;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的收藏（ArtisanMusic 的「歌曲标记为喜欢」对应能力）：
 * 列表 + 点按整队播放 + 长按取消收藏。复用历史页布局。
 */
public class FavoritesActivity extends AppCompatActivity implements SongAdapter.Listener {

    private final List<Song> songs = new ArrayList<>();
    private SongAdapter adapter;
    private PlayService playService;
    private boolean bound = false;

    private final android.content.ServiceConnection conn = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
            playService = ((PlayService.PlayBinder) service).getService();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            bound = false;
            playService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.chumosplayer.util.SettingsManager.applyTheme(this); // 纯黑/主题色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.history_toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("我的收藏");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.recycler_history);
        rv.setLayoutManager(new LinearLayoutManager(this));
        songs.addAll(Favorites.getAll(this));
        adapter = new SongAdapter(songs, this, false);
        rv.setAdapter(adapter);
        if (songs.isEmpty()) {
            Toast.makeText(this, "还没有收藏，播放页点红心即可收藏", Toast.LENGTH_LONG).show();
        }

        bindService(new Intent(this, PlayService.class), conn,
                android.content.Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onItemClick(Song song, int position) {
        if (playService == null) {
            Toast.makeText(this, "播放服务还没就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        // 整个收藏列表入队，从点中的这首开始
        playService.setQueue(new ArrayList<>(songs), position);
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    @Override
    public void onItemLongClick(Song song) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(new String[]{"取消收藏", "播放"}, (d, which) -> {
                    if (which == 0) {
                        Favorites.remove(this, song);
                        songs.remove(song);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
                    } else {
                        onItemClick(song, songs.indexOf(song));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onLyricsClick(Song song) {
        LyricsDialogFragment.show(getSupportFragmentManager(), song);
    }

    @Override
    public void onDownloadClick(Song song) { }
}

package com.chumosplayer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.model.Song;
import com.chumosplayer.util.PlayHistory;

import java.util.ArrayList;
import java.util.List;

/** 播放历史列表 */
public class HistoryActivity extends AppCompatActivity implements SongAdapter.Listener {

    public static final String EXTRA_INDEX = "history_index";

    private final List<Song> songs = new ArrayList<>();
    private SongAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.chumosplayer.util.SettingsManager.applyTheme(this); // 纯黑/主题色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.history_toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("播放历史");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        songs.addAll(PlayHistory.getAll(this));
        RecyclerView rv = findViewById(R.id.recycler_history);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SongAdapter(songs, this, false);
        rv.setAdapter(adapter);

        // 自动定位到正在播放的那首（用 PlayService 的静态快照，无需绑定服务）
        Song playing = com.chumosplayer.playback.PlayService.snapshotSong();
        if (playing != null) {
            for (int i = 0; i < songs.size(); i++) {
                Song s = songs.get(i);
                boolean same = (playing.path != null && playing.path.equals(s.path))
                        || (playing.title != null && playing.title.equals(s.title));
                if (same) {
                    final int pos = i;
                    rv.post(() -> rv.smoothScrollToPosition(pos));
                    break;
                }
            }
        }

        if (songs.isEmpty()) {
            Toast.makeText(this, "暂无播放历史", Toast.LENGTH_SHORT).show();
        }

        tb.inflateMenu(R.menu.history_menu);
        tb.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_clear_history) {
                PlayHistory.clear(this);
                songs.clear();
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "已清空播放历史", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
    }

    @Override
    public void onItemClick(Song song, int position) {
        // 交给 MainActivity 用播放服务播放整个历史列表
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_INDEX, position);
        startActivity(i);
        finish();
    }

    @Override public void onLyricsClick(Song song) { }
    @Override public void onDownloadClick(Song song) { }
}

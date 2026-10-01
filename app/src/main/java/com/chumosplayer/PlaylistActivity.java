package com.chumosplayer;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.fragment.LyricsDialogFragment;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.MusicLoader;
import com.chumosplayer.util.PlaylistStore;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 歌单页：一层是歌单列表（新建/导入/导出），点进去是该歌单的歌曲列表
 * （播放、上移下移排序、移除、添加歌曲、重命名、删除、单独导出）。
 */
public class PlaylistActivity extends AppCompatActivity implements SongAdapter.Listener {

    private static final int REQ_EXPORT = 301;
    private static final int REQ_IMPORT = 302;

    private final List<PlaylistStore.Playlist> playlists = new ArrayList<>();
    /** 当前打开的歌单；null = 处于歌单列表模式 */
    private PlaylistStore.Playlist current;
    /** 当前歌单的歌曲（工作副本，避免把解析后的直链写回存档） */
    private final List<Song> songs = new ArrayList<>();

    private androidx.appcompat.widget.Toolbar toolbar;
    private ScrollView scrollLists;
    private LinearLayout listContainer;
    private RecyclerView rvSongs;
    private SongAdapter songAdapter;

    private PlayService playService;
    private boolean bound = false;
    /** 待导出的歌单 id；null 表示导出全部 */
    private String pendingExportId;

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
        setContentView(R.layout.activity_playlist);

        toolbar = findViewById(R.id.pl_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("我的歌单");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> back());

        scrollLists = findViewById(R.id.pl_scroll);
        listContainer = findViewById(R.id.pl_list);
        rvSongs = findViewById(R.id.pl_recycler);
        rvSongs.setLayoutManager(new LinearLayoutManager(this));
        songAdapter = new SongAdapter(songs, this, false);
        rvSongs.setAdapter(songAdapter);

        toolbar.inflateMenu(R.menu.playlist_menu);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        bindService(new Intent(this, PlayService.class), conn, android.content.Context.BIND_AUTO_CREATE);
        showList();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    // ---- 模式切换 ----

    private void showList() {
        current = null;
        songs.clear();
        scrollLists.setVisibility(View.VISIBLE);
        rvSongs.setVisibility(View.GONE);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("我的歌单");
        applyMenu();
        renderList();
    }

    private void openPlaylist(PlaylistStore.Playlist p) {
        current = p;
        songs.clear();
        for (Song s : p.songs) songs.add(s.copy()); // 工作副本
        songAdapter.notifyDataSetChanged();
        scrollLists.setVisibility(View.GONE);
        rvSongs.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(p.name);
        applyMenu();
        if (songs.isEmpty()) {
            Toast.makeText(this, "空歌单：点右上角「添加歌曲」，或在列表里长按歌曲加入",
                    Toast.LENGTH_LONG).show();
        }
        // 打开歌单时自动定位到正在播放的那首（如果它在这个歌单里）
        final int playing = indexOfPlaying();
        if (playing >= 0) {
            rvSongs.post(() -> rvSongs.smoothScrollToPosition(playing));
        }
    }

    /** 当前正在播放的歌在本列表中的下标；找不到返回 -1 */
    private int indexOfPlaying() {
        if (playService == null) return -1;
        Song cur = playService.currentSong();
        if (cur == null) return -1;
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            if (cur.path != null && cur.path.equals(s.path)) return i;
            if (cur.url != null && cur.url.equals(s.url)) return i;
            if (cur.title != null && cur.title.equals(s.title)) return i;
        }
        return -1;
    }

    private void back() {
        if (current != null) showList();
        else finish();
    }

    @Override
    public void onBackPressed() {
        if (current != null) showList();
        else super.onBackPressed();
    }

    /** 按模式显示对应菜单项 */
    private void applyMenu() {
        Menu m = toolbar.getMenu();
        if (m == null) return;
        boolean listMode = current == null;
        setMenu(m, R.id.action_pl_new, listMode);
        setMenu(m, R.id.action_pl_export, listMode);
        setMenu(m, R.id.action_pl_import, listMode);
        setMenu(m, R.id.action_pl_addsong, !listMode);
        setMenu(m, R.id.action_pl_rename, !listMode);
        setMenu(m, R.id.action_pl_delete, !listMode);
        setMenu(m, R.id.action_pl_export_one, !listMode);
    }

    private void setMenu(Menu m, int id, boolean visible) {
        if (m.findItem(id) != null) m.findItem(id).setVisible(visible);
    }

    // ---- 歌单列表渲染 ----

    private void renderList() {
        listContainer.removeAllViews();
        playlists.clear();
        playlists.addAll(PlaylistStore.load(this));

        if (playlists.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("还没有歌单\n\n在「本地音乐 / 在线搜索」里长按一首歌 →「加入歌单」，\n或者点右上角「新建」。");
            t.setTextColor(getResources().getColor(R.color.text_secondary));
            t.setTextSize(14);
            t.setGravity(android.view.Gravity.CENTER);
            int pad = dp(24);
            t.setPadding(pad, pad * 2, pad, pad);
            listContainer.addView(t);
            return;
        }

        for (final PlaylistStore.Playlist p : playlists) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_playlist, listContainer, false);
            TextView name = row.findViewById(R.id.tv_pl_name);
            TextView count = row.findViewById(R.id.tv_pl_count);
            name.setText(p.name);
            count.setText(p.songs.size() + " 首");
            row.setOnClickListener(v -> openPlaylist(p));
            row.setOnLongClickListener(v -> {
                playlistOptions(p);
                return true;
            });
            listContainer.addView(row);
        }
    }

    private void playlistOptions(final PlaylistStore.Playlist p) {
        final String[] items = {"打开", "重命名", "导出", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openPlaylist(p); break;
                        case 1: promptRename(p); break;
                        case 2: doExport(p.id); break;
                        case 3: confirmDelete(p); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- 菜单 ----

    private boolean onMenu(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_pl_new) {
            promptNew();
            return true;
        }
        if (id == R.id.action_pl_export) {
            doExport(null);
            return true;
        }
        if (id == R.id.action_pl_import) {
            doImport();
            return true;
        }
        if (id == R.id.action_pl_addsong) {
            pickSongsToAdd();
            return true;
        }
        if (id == R.id.action_pl_rename && current != null) {
            promptRename(current);
            return true;
        }
        if (id == R.id.action_pl_delete && current != null) {
            confirmDelete(current);
            return true;
        }
        if (id == R.id.action_pl_export_one && current != null) {
            doExport(current.id);
            return true;
        }
        return false;
    }

    private void promptNew() {
        final EditText et = new EditText(this);
        et.setHint("歌单名称");
        new AlertDialog.Builder(this)
                .setTitle("新建歌单")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    PlaylistStore.create(this, et.getText().toString());
                    renderList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptRename(final PlaylistStore.Playlist p) {
        final EditText et = new EditText(this);
        et.setText(p.name);
        et.setSelection(p.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名歌单")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    PlaylistStore.rename(this, p.id, et.getText().toString());
                    renderList();
                    if (current != null && current.id.equals(p.id)) {
                        current = PlaylistStore.byId(this, p.id);
                        if (current != null && getSupportActionBar() != null) {
                            getSupportActionBar().setTitle(current.name);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final PlaylistStore.Playlist p) {
        new AlertDialog.Builder(this)
                .setTitle("删除歌单")
                .setMessage("确定删除「" + p.name + "」？\n歌单里的 " + p.songs.size()
                        + " 首只从歌单移除，不会删除本地文件。")
                .setPositiveButton("删除", (d, w) -> {
                    PlaylistStore.delete(this, p.id);
                    showList();
                    Toast.makeText(this, "已删除歌单", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- 歌曲操作 ----

    @Override
    public void onItemClick(Song song, int position) {
        playAt(position);
    }

    @Override
    public void onLyricsClick(Song song) {
        LyricsDialogFragment.show(getSupportFragmentManager(), song);
    }

    @Override
    public void onDownloadClick(Song song) { }

    @Override
    public void onItemLongClick(Song song) {
        final int idx = songs.indexOf(song);
        if (idx < 0 || current == null) return;
        final String[] items = {"播放（从这首开始）", "上移", "下移", "从歌单移除"};
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: playAt(idx); break;
                        case 1:
                            if (idx > 0) { swap(idx, idx - 1); }
                            break;
                        case 2:
                            if (idx < songs.size() - 1) { swap(idx, idx + 1); }
                            break;
                        case 3:
                            songs.remove(idx);
                            persist();
                            songAdapter.notifyDataSetChanged();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void swap(int a, int b) {
        Collections.swap(songs, a, b);
        songAdapter.notifyDataSetChanged();
        persist();
    }

    /** 把当前工作列表写回存储（存档格式，避免把临时直链写进去） */
    private void persist() {
        if (current == null) return;
        List<Song> arch = new ArrayList<>();
        for (Song s : songs) arch.add(s.archived());
        PlaylistStore.updateSongs(this, current.id, arch);
        current.songs = arch;
    }

    /** 播放：B 站源先解析直链，再整单入队 */
    private void playAt(final int index) {
        if (!bound || playService == null || index < 0 || index >= songs.size()) {
            Toast.makeText(this, "播放服务还没就绪，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final Song s = songs.get(index);
        if (s.online && s.url != null && s.url.startsWith("bilibili://")) {
            Toast.makeText(this, "正在解析 B 站音源…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String err = null;
                try {
                    com.chumosplayer.net.BilibiliClient.ensureResolved(s);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String msg = err;
                runOnUiThread(() -> {
                    if (msg != null) {
                        Toast.makeText(PlaylistActivity.this, "解析失败：" + msg,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    startQueue(index);
                });
            }).start();
            return;
        }
        startQueue(index);
    }

    private void startQueue(int index) {
        List<Song> queue = new ArrayList<>();
        for (Song s : songs) queue.add(s.copy());
        playService.setQueue(queue, index);
        Toast.makeText(this, "正在播放：" + queue.get(index).title, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    /** 多选添加本地歌曲 */
    private void pickSongsToAdd() {
        if (current == null) return;
        List<Song> loaded = null;
        try {
            loaded = MusicLoader.loadLocal(this);
        } catch (Exception ignore) { }
        final List<Song> local = (loaded == null) ? new ArrayList<Song>() : loaded;
        if (local.isEmpty()) {
            Toast.makeText(this, "没有可添加的本地歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[local.size()];
        for (int i = 0; i < local.size(); i++) {
            names[i] = local.get(i).title + " - " + local.get(i).artist;
        }
        final boolean[] checked = new boolean[local.size()];
        for (int i = 0; i < local.size(); i++) {
            for (Song x : songs) {
                if (PlaylistStore.sameSong(x, local.get(i))) { checked[i] = true; break; }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("添加歌曲（可多选）")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("添加", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < local.size(); i++) {
                        if (!checked[i]) continue;
                        boolean exists = false;
                        for (Song x : songs) {
                            if (PlaylistStore.sameSong(x, local.get(i))) { exists = true; break; }
                        }
                        if (!exists) { songs.add(local.get(i).archived()); added++; }
                    }
                    persist();
                    songAdapter.notifyDataSetChanged();
                    Toast.makeText(this, added + " 首已添加", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- 导入导出 ----

    private void doExport(String playlistId) {
        pendingExportId = playlistId;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
                    .format(new Date());
            i.putExtra(Intent.EXTRA_TITLE, (playlistId != null ? "playlist" : "playlists")
                    + "_" + stamp + ".json");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void doImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                String json;
                if (pendingExportId == null) {
                    json = PlaylistStore.exportJson(this);
                } else {
                    PlaylistStore.Playlist p = PlaylistStore.byId(this, pendingExportId);
                    json = p == null ? null : new Gson().toJson(Collections.singletonList(p));
                }
                if (json == null) {
                    Toast.makeText(this, "导出失败：没有可导出的歌单", Toast.LENGTH_SHORT).show();
                    return;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.close();
                }
                Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT) {
                InputStream is = getContentResolver().openInputStream(uri);
                String json = readAll(is);
                if (is != null) is.close();
                int n = PlaylistStore.importJson(this, json);
                if (n > 0) {
                    Toast.makeText(this, "已导入 " + n + " 个歌单", Toast.LENGTH_SHORT).show();
                    showList();
                } else if (n == 0) {
                    Toast.makeText(this, "文件里没有歌单", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "导入失败：文件格式不正确", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

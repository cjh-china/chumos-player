package com.chumosplayer.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.R;
import com.chumosplayer.adapter.GroupAdapter;
import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.model.Group;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.MusicLoader;
import com.chumosplayer.util.QueueSheet;
import com.chumosplayer.util.SettingsManager;
import com.chumosplayer.util.SongActions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 本地音乐：歌曲 / 艺术家 / 专辑 / 文件夹 / 最近 + 实时搜索 + 排序 + 空状态 */
public class LocalMusicFragment extends Fragment
        implements SongAdapter.Listener, GroupAdapter.Listener {

    private static final int MODE_SONG = 0, MODE_ARTIST = 1, MODE_ALBUM = 2,
            MODE_FOLDER = 3, MODE_RECENT = 4;

    /** 展示用数据 */
    private final List<Song> songs = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    /** 全量数据：搜索从 allSongs 里过滤，不必重新扫描媒体库 */
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Group> allGroups = new ArrayList<>();
    /** 当前模式的基础列表（最近模式是时间窗口子集） */
    private final List<Song> baseSongs = new ArrayList<>();

    private SongAdapter adapter;
    private GroupAdapter groupAdapter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;

    private final TextView[] tabs = new TextView[5];
    private View groupBackBar, recyclerGroup, recyclerLocal;
    private TextView groupTitle;
    private View emptyView;
    private TextView tvEmptyTitle, tvEmptyDesc, btnEmptyAction, tvResultCount;
    private EditText etSearch;
    private View btnClear;

    private int mode = MODE_SONG;
    private Group openGroup;
    private String query = "";
    private int sortMode = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_local, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView rv = view.findViewById(R.id.recycler_local);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SongAdapter(songs, this, false);
        rv.setAdapter(adapter);
        recyclerLocal = view.findViewById(R.id.swipe_local); // 外层 SwipeRefreshLayout

        RecyclerView rg = view.findViewById(R.id.recycler_group);
        rg.setLayoutManager(new LinearLayoutManager(requireContext()));
        groupAdapter = new GroupAdapter(groups, this);
        rg.setAdapter(groupAdapter);
        recyclerGroup = rg;

        swipe = view.findViewById(R.id.swipe_local);
        swipe.setOnRefreshListener(this::reload);

        emptyView = view.findViewById(R.id.empty_view);
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title);
        tvEmptyDesc = view.findViewById(R.id.tv_empty_desc);
        btnEmptyAction = view.findViewById(R.id.btn_empty_action);
        tvResultCount = view.findViewById(R.id.tv_result_count);
        etSearch = view.findViewById(R.id.et_local_search);
        btnClear = view.findViewById(R.id.btn_clear_search);
        sortMode = SettingsManager.getSortMode(requireContext());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        btnClear.setOnClickListener(v -> etSearch.setText(""));
        view.findViewById(R.id.btn_sort).setOnClickListener(v -> pickSort());

        tabs[0] = view.findViewById(R.id.tab_song);
        tabs[1] = view.findViewById(R.id.tab_artist);
        tabs[2] = view.findViewById(R.id.tab_album);
        tabs[3] = view.findViewById(R.id.tab_folder);
        tabs[4] = view.findViewById(R.id.tab_recent);
        for (int i = 0; i < tabs.length; i++) {
            final int m = i;
            tabs[i].setOnClickListener(v -> switchMode(m));
        }
        applyTabVisibility();

        groupBackBar = view.findViewById(R.id.group_back_bar);
        groupTitle = view.findViewById(R.id.group_title);
        view.findViewById(R.id.group_back).setOnClickListener(v -> {
            openGroup = null;
            applyFilter();
        });

        updateTabs();
    }

    private void switchMode(int m) {
        if (mode == m && openGroup == null && query.isEmpty()) return;
        mode = m;
        openGroup = null;
        if (!query.isEmpty()) etSearch.setText("");
        updateTabs();
        reload();
    }

    /** 按设置显示 / 隐藏分类 Chip */
    private void applyTabVisibility() {
        Set<String> enabled = SettingsManager.getHomeTabs(requireContext());
        String[] keys = SettingsManager.TAB_KEYS;
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] != null) {
                tabs[i].setVisibility(enabled.contains(keys[i]) ? View.VISIBLE : View.GONE);
            }
        }
        if (!enabled.contains(keys[mode])) {
            for (int i = 0; i < keys.length; i++) {
                if (enabled.contains(keys[i])) {
                    mode = i;
                    break;
                }
            }
            openGroup = null;
            updateTabs();
        }
    }

    /** 选中 Chip 用主题色容器 + 主题色文字，未选中为中性容器 */
    private void updateTabs() {
        int accent = QueueSheet.accent(requireContext());
        int normal = requireContext().getResources().getColor(R.color.text_secondary);
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            boolean sel = i == mode;
            tabs[i].setBackgroundResource(sel ? R.drawable.bg_chip_active : R.drawable.bg_chip);
            tabs[i].setTextColor(sel ? accent : normal);
            tabs[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    private void pickSort() {
        final String[] labels = {"按歌名 A–Z", "按歌手", "按时长（长 → 短）", "按歌名 Z–A"};
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("排序方式")
                .setSingleChoiceItems(labels, sortMode, (d, which) -> {
                    sortMode = which;
                    SettingsManager.setSortMode(requireContext(), which);
                    applyFilter();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 重新扫描（下拉刷新 / 黑名单变更后调用） */
    public void reload() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (swipe != null) swipe.setRefreshing(false);
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        doLoad();
        if (swipe != null) swipe.setRefreshing(false);
    }

    private void doLoad() {
        allSongs.clear();
        allGroups.clear();
        baseSongs.clear();
        songs.clear();
        groups.clear();
        openGroup = null;

        allSongs.addAll(MusicLoader.loadLocal(requireContext()));
        if (mode == MODE_RECENT) {
            int days = SettingsManager.getRecentDays(requireContext());
            List<Song> recent = MusicLoader.loadRecent(requireContext(), days);
            Set<String> paths = new HashSet<>();
            for (Song s : allSongs) if (s.path != null) paths.add(s.path);
            for (Song s : recent) {
                // 最近列表可能与全库扫描口径略有差异，统一按路径去重后并入
                if (s.path != null && paths.contains(s.path)) baseSongs.add(s);
            }
        } else {
            baseSongs.addAll(allSongs);
        }
        if (mode == MODE_ARTIST || mode == MODE_ALBUM || mode == MODE_FOLDER) {
            buildGroups(allSongs);
        }
        applyFilter();
    }

    /** 按艺术家 / 专辑 / 文件夹分组 */
    private void buildGroups(List<Song> all) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (Song s : all) {
            String key;
            if (mode == MODE_ARTIST) {
                key = isUnknown(s.artist) ? "未知艺术家" : s.artist;
            } else if (mode == MODE_ALBUM) {
                key = isUnknown(s.album) ? "未知专辑" : s.album;
            } else {
                key = folderKey(s);
            }
            Group g = map.get(key);
            if (g == null) {
                g = new Group(key);
                map.put(key, g);
            }
            g.songs.add(s);
        }
        allGroups.addAll(map.values());
    }

    private static boolean isUnknown(String v) {
        return v == null || v.isEmpty() || "<unknown>".equals(v);
    }

    /** 下载器会给每首歌建独立文件夹，统一归到保存根目录 */
    private String folderKey(Song s) {
        if (s.path == null) return "未知目录";
        File p = new File(s.path).getParentFile();
        if (p == null) return "未知目录";
        File root = com.chumosplayer.util.DownloadUtil.saveDir(requireContext());
        return isUnderOrEqual(p, root) ? root.getName() : p.getName();
    }

    private static boolean isUnderOrEqual(File dir, File root) {
        if (dir == null || root == null) return false;
        try {
            String dp = dir.getCanonicalPath();
            String rp = root.getCanonicalPath();
            return dp.equals(rp) || dp.startsWith(rp + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** 根据搜索词 / 当前模式，计算展示数据并切换视图 */
    private void applyFilter() {
        songs.clear();
        groups.clear();
        boolean searching = !query.isEmpty();
        if (searching) {
            for (Song s : allSongs) {
                if (matches(s, query)) songs.add(s);
            }
        } else if (openGroup != null) {
            songs.addAll(openGroup.songs);
        } else if (mode == MODE_SONG || mode == MODE_RECENT) {
            songs.addAll(baseSongs);
        } else {
            groups.addAll(allGroups);
        }
        sortSongs(songs);

        if (searching) {
            tvResultCount.setVisibility(View.VISIBLE);
            tvResultCount.setText("全库搜索 · 找到 " + songs.size() + " 首");
        } else {
            tvResultCount.setVisibility(View.GONE);
        }
        applyView();
        adapter.notifyDataSetChanged();
        groupAdapter.notifyDataSetChanged();
    }

    private static boolean matches(Song s, String q) {
        return contains(s.title, q) || contains(s.artist, q) || contains(s.album, q)
                || contains(s.path, q);
    }

    private static boolean contains(String src, String q) {
        return src != null && src.toLowerCase(Locale.ROOT).contains(q);
    }

    private void sortSongs(List<Song> list) {
        final int m = sortMode;
        Collections.sort(list, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                switch (m) {
                    case 1: return safe(a.artist).compareToIgnoreCase(safe(b.artist));
                    case 2: return Long.compare(b.duration, a.duration);
                    case 3: return safe(b.title).compareToIgnoreCase(safe(a.title));
                    default: return safe(a.title).compareToIgnoreCase(safe(b.title));
                }
            }
        });
    }

    private static String safe(String v) { return v == null ? "" : v; }

    /** 根据当前状态切换列表 / 分组 / 空态的可见性 */
    private void applyView() {
        boolean searching = !query.isEmpty();
        boolean inGroup = openGroup != null && !searching;
        boolean groupMode = !searching && !inGroup
                && (mode == MODE_ARTIST || mode == MODE_ALBUM || mode == MODE_FOLDER);

        groupBackBar.setVisibility(inGroup ? View.VISIBLE : View.GONE);
        if (inGroup) groupTitle.setText(openGroup.name);

        recyclerGroup.setVisibility(groupMode ? View.VISIBLE : View.GONE);
        recyclerLocal.setVisibility(groupMode ? View.GONE : View.VISIBLE);

        boolean empty = groupMode ? groups.isEmpty() : songs.isEmpty();
        showEmpty(empty, searching);
    }

    private void showEmpty(boolean empty, boolean searching) {
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        if (searching) {
            tvEmptyTitle.setText(R.string.empty_search_title);
            tvEmptyDesc.setText(R.string.empty_search_desc);
            btnEmptyAction.setText("清空搜索");
            btnEmptyAction.setVisibility(View.VISIBLE);
            btnEmptyAction.setOnClickListener(v -> etSearch.setText(""));
        } else {
            tvEmptyTitle.setText(R.string.empty_local_title);
            tvEmptyDesc.setText(R.string.empty_local_desc);
            btnEmptyAction.setText("重新扫描");
            btnEmptyAction.setVisibility(View.VISIBLE);
            btnEmptyAction.setOnClickListener(v -> reload());
        }
    }

    @Override
    public void onGroupClick(Group group) {
        openGroup = group;
        applyFilter();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadIfNeeded();
        if (adapter != null) {
            adapter.setCurrentSong(PlayService.snapshotSong());
            adapter.refreshFavorites(requireContext());
        }
    }

    private void loadIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        if (allSongs.isEmpty()) doLoad();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doLoad();
        } else if (requestCode == 1) {
            Toast.makeText(requireContext(), R.string.permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onItemClick(Song song, int position) {
        int p = position < 0 ? 0 : position;
        ((Callback) requireActivity()).onPlayQueue(new ArrayList<>(songs), p);
    }

    @Override
    public void onLyricsClick(Song song) {
        showLyricsFor(song);
    }

    private void showLyricsFor(Song song) {
        String lrc = com.chumosplayer.util.LocalLrcLoader.load(song);
        if (lrc != null && !lrc.isEmpty()) {
            song.lrc = lrc;
            LyricsDialogFragment.show(getParentFragmentManager(), song);
            return;
        }
        Toast.makeText(requireContext(), "正在联网查找歌词…", Toast.LENGTH_SHORT).show();
        com.chumosplayer.util.LrcFetcher.fetch(requireContext(), song, fetched -> {
            if (!isAdded()) return;
            song.lrc = fetched;
            LyricsDialogFragment.show(getParentFragmentManager(), song);
        });
    }

    @Override
    public void onDownloadClick(Song song) { }

    /** 常驻「更多」与长按走同一套操作菜单 */
    @Override
    public void onMoreClick(Song song) {
        SongActions.show(requireContext(), song, true,
                SettingsManager.isTagEditorEnabled(requireContext()),
                new SongActions.Callback() {
                    @Override public void showLyrics(Song s) { showLyricsFor(s); }
                    @Override public void editTags(Song s) { showTagEditor(s); }
                    @Override public void deleteSong(Song s) { confirmDelete(s); }
                    @Override public void blockFolder(Song s) {
                        LocalMusicFragment.this.blockFolder(s);
                    }
                    @Override public void refresh() {
                        if (adapter != null) adapter.refreshFavorites(requireContext());
                    }
                });
    }

    /** 删除歌曲：先确认，再依次处理 播放中 / 侧车 / 媒体库 / 引用 / 文件 */
    private void confirmDelete(final Song song) {
        final File f = new File(song.path == null ? "" : song.path);
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("删除歌曲")
                .setMessage("将永久删除文件：\n" + f.getName()
                        + "\n\n同时删除同名歌词与封面，并清理歌单 / 收藏 / 播放历史里的引用。"
                        + "\n此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> doDelete(song, f))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(final Song song, final File f) {
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            com.chumosplayer.playback.PlayService.stopIfPlayingPath(song.path);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            final String base = dot > 0 ? name.substring(0, dot) : name;
            File dir = f.getParentFile();
            if (dir != null) {
                for (String ext : new String[]{".lrc", ".LRC", ".jpg", ".jpeg", ".png", ".webp"}) {
                    File side = new File(dir, base + ext);
                    if (side.exists()) side.delete();
                }
            }
            if (song.id > 0) {
                try {
                    app.getContentResolver().delete(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            android.provider.MediaStore.Audio.Media._ID + "=?",
                            new String[]{String.valueOf(song.id)});
                } catch (Exception ignore) { }
            }
            try {
                com.chumosplayer.util.PlaylistStore.removeFromAll(app, song);
                com.chumosplayer.util.Favorites.remove(app, song);
                com.chumosplayer.util.PlayHistory.remove(app, song);
            } catch (Exception ignore) { }
            boolean ok = f.delete();
            final boolean success = ok;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                reload();
                Toast.makeText(requireContext(),
                        success ? "已删除：" + base : "删除失败：文件不存在或没有写权限",
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            });
        }, "delete-song").start();
    }

    /** 长按屏蔽：下载目录下的歌，屏蔽范围提到保存根目录 */
    private void blockFolder(Song song) {
        if (song.path == null) return;
        File parent = new File(song.path).getParentFile();
        if (parent == null) return;
        File root = com.chumosplayer.util.DownloadUtil.saveDir(requireContext());
        final File target = isUnderOrEqual(parent, root) ? root : parent;
        final boolean lifted = target != parent;
        final String dir = target.getAbsolutePath();
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("屏蔽文件夹")
                .setMessage("将下列文件夹加入黑名单？其下歌曲将不再显示：\n\n" + dir
                        + (lifted ? "\n\n（含其下所有歌曲文件夹）" : ""))
                .setPositiveButton("屏蔽", (d, w) -> {
                    com.chumosplayer.util.BlacklistManager.add(requireContext(), dir);
                    reload();
                    Toast.makeText(requireContext(),
                            lifted ? "已屏蔽下载目录：" + target.getName() : "已屏蔽该文件夹",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTagEditor(Song song) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText etTitle = new android.widget.EditText(requireContext());
        final android.widget.EditText etArtist = new android.widget.EditText(requireContext());
        final android.widget.EditText etAlbum = new android.widget.EditText(requireContext());
        etTitle.setHint("标题");
        etTitle.setText(song.title == null ? "" : song.title);
        etArtist.setHint("艺术家");
        etArtist.setText(song.artist == null ? "" : song.artist);
        etAlbum.setHint("专辑");
        etAlbum.setText(song.album == null ? "" : song.album);
        final android.widget.EditText etLyrics = new android.widget.EditText(requireContext());
        etLyrics.setHint("歌词（LRC，留空表示不改动）");
        etLyrics.setGravity(android.view.Gravity.TOP);
        etLyrics.setMinLines(4);
        String existing = song.lrc;
        if (existing == null || existing.isEmpty()) {
            existing = com.chumosplayer.util.LocalLrcLoader.load(song);
        }
        etLyrics.setText(existing == null ? "" : existing);
        box.addView(etTitle);
        box.addView(etArtist);
        box.addView(etAlbum);
        box.addView(etLyrics);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(box);
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("编辑标签")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> saveTags(song,
                        etTitle.getText().toString().trim(),
                        etArtist.getText().toString().trim(),
                        etAlbum.getText().toString().trim(),
                        etLyrics.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveTags(Song song, String t, String ar, String al, String lrc) {
        final File f = new File(song.path == null ? "" : song.path);
        final String lyrics = lrc == null ? "" : lrc.trim();
        new Thread(() -> {
            boolean ok = com.chumosplayer.util.TagEditor.write(f, t, ar, al,
                    lyrics.isEmpty() ? null : lyrics);
            if (ok && !lyrics.isEmpty()) {
                com.chumosplayer.util.LrcFetcher.saveToLocal(song, lyrics);
            }
            if (ok) {
                android.media.MediaScannerConnection.scanFile(requireContext(),
                        new String[]{f.getAbsolutePath()}, null, null);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (ok) {
                    song.title = t;
                    song.artist = ar;
                    song.album = al;
                    if (!lyrics.isEmpty()) song.lrc = lyrics;
                    applyFilter();
                    Toast.makeText(requireContext(), "标签已保存", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(),
                            "保存失败（格式不支持或文件只读）", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    public interface Callback {
        void onPlayQueue(List<Song> queue, int startIndex);
    }
}

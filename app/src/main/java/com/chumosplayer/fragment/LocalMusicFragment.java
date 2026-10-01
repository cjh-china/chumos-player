package com.chumosplayer.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.chumosplayer.util.MusicLoader;
import com.chumosplayer.util.SettingsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地音乐：支持 歌曲 / 艺术家 / 专辑 / 文件夹 / 最近 多种视图 */
public class LocalMusicFragment extends Fragment
        implements SongAdapter.Listener, GroupAdapter.Listener {

    private static final int MODE_SONG = 0, MODE_ARTIST = 1, MODE_ALBUM = 2,
            MODE_FOLDER = 3, MODE_RECENT = 4;

    private final List<Song> songs = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private SongAdapter adapter;
    private GroupAdapter groupAdapter;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;

    private final TextView[] tabs = new TextView[5];
    private View groupBackBar, recyclerGroup, recyclerLocal;
    private TextView groupTitle;

    private int mode = MODE_SONG;
    /** 已进入的分组；非空时显示该组歌曲 */
    private Group openGroup;

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
        recyclerLocal = rv;

        RecyclerView rg = view.findViewById(R.id.recycler_group);
        rg.setLayoutManager(new LinearLayoutManager(requireContext()));
        groupAdapter = new GroupAdapter(groups, this);
        rg.setAdapter(groupAdapter);
        recyclerGroup = rg;

        swipe = view.findViewById(R.id.swipe_local);
        swipe.setOnRefreshListener(this::reload);

        tabs[0] = view.findViewById(R.id.tab_song);
        tabs[1] = view.findViewById(R.id.tab_artist);
        tabs[2] = view.findViewById(R.id.tab_album);
        tabs[3] = view.findViewById(R.id.tab_folder);
        tabs[4] = view.findViewById(R.id.tab_recent);
        for (int i = 0; i < tabs.length; i++) {
            final int m = i;
            tabs[i].setOnClickListener(v -> switchMode(m));
        }
        applyTabVisibility(); // 按设置显示/隐藏分类标签

        groupBackBar = view.findViewById(R.id.group_back_bar);
        groupTitle = view.findViewById(R.id.group_title);
        view.findViewById(R.id.group_back).setOnClickListener(v -> {
            openGroup = null;
            applyView();
        });

        updateTabs();
    }

    private void switchMode(int m) {
        if (mode == m && openGroup == null) return;
        mode = m;
        openGroup = null;
        updateTabs();
        reload();
    }

    /** 按设置显示/隐藏分类标签；当前分类被关掉时自动切到第一个还开着的 */
    private void applyTabVisibility() {
        java.util.Set<String> enabled =
                com.chumosplayer.util.SettingsManager.getHomeTabs(requireContext());
        String[] keys = com.chumosplayer.util.SettingsManager.TAB_KEYS;
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            tabs[i].setVisibility(enabled.contains(keys[i]) ? View.VISIBLE : View.GONE);
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

    private void updateTabs() {
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            tabs[i].setTextColor(getResources().getColor(
                    i == mode ? R.color.text_primary : R.color.text_secondary));
        }
    }

    /** 重新扫描（下拉刷新/黑名单变更后调用） */
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
        songs.clear();
        groups.clear();
        openGroup = null;
        if (mode == MODE_RECENT) {
            int days = SettingsManager.getRecentDays(requireContext());
            songs.addAll(MusicLoader.loadRecent(requireContext(), days));
        } else {
            List<Song> all = MusicLoader.loadLocal(requireContext());
            if (mode == MODE_SONG) {
                songs.addAll(all);
            } else {
                buildGroups(all);
            }
        }
        adapter.notifyDataSetChanged();
        groupAdapter.notifyDataSetChanged();
        applyView();
    }

    /** 按艺术家/专辑/文件夹分组 */
    private void buildGroups(List<Song> all) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (Song s : all) {
            String key;
            if (mode == MODE_ARTIST) {
                key = (s.artist == null || s.artist.isEmpty() || "<unknown>".equals(s.artist))
                        ? "未知艺术家" : s.artist;
            } else if (mode == MODE_ALBUM) {
                key = (s.album == null || s.album.isEmpty() || "<unknown>".equals(s.album))
                        ? "未知专辑" : s.album;
            } else { // 文件夹
                key = folderKey(s);
            }
            Group g = map.get(key);
            if (g == null) {
                g = new Group(key);
                map.put(key, g);
            }
            g.songs.add(s);
        }
        groups.addAll(map.values());
    }

    /**
     * 「文件夹」页的分组键。
     * 下载器会给每首歌建独立文件夹（保存根目录/歌名/音频），直接按父目录取名会让
     * 该页一首歌一个分组——所以保存根目录之下的歌统一按上一级（保存根目录）归组，
     * 其余歌曲维持原行为（按所在目录名归组）。
     */
    private String folderKey(Song s) {
        if (s.path == null) return "未知目录";
        File p = new File(s.path).getParentFile();
        if (p == null) return "未知目录";
        File root = com.chumosplayer.util.DownloadUtil.saveDir(requireContext());
        return isUnderOrEqual(p, root) ? root.getName() : p.getName();
    }

    /** dir 是否就是 root，或位于 root 之下（canonical 比较，软链/相对路径不会误判） */
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

    /** 根据当前模式/是否已进组，切换界面显示 */
    private void applyView() {
        if (openGroup != null) {
            // 已进入某组：显示该组歌曲列表
            recyclerGroup.setVisibility(View.GONE);
            recyclerLocal.setVisibility(View.VISIBLE);
            groupBackBar.setVisibility(View.VISIBLE);
            groupTitle.setText(openGroup.name);
            songs.clear();
            songs.addAll(openGroup.songs);
            adapter.notifyDataSetChanged();
            return;
        }
        groupBackBar.setVisibility(View.GONE);
        boolean groupMode = (mode == MODE_ARTIST || mode == MODE_ALBUM || mode == MODE_FOLDER);
        if (groupMode) {
            recyclerGroup.setVisibility(View.VISIBLE);
            recyclerLocal.setVisibility(View.GONE);
            groupAdapter.notifyDataSetChanged();
        } else {
            recyclerGroup.setVisibility(View.GONE);
            recyclerLocal.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onGroupClick(Group group) {
        openGroup = group;
        applyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadIfNeeded();
    }

    private void loadIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        if (!songs.isEmpty() || !groups.isEmpty()) return;
        doLoad();
        if (songs.isEmpty() && groups.isEmpty()) {
            Toast.makeText(requireContext(), "未找到本地音乐", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doLoad();
        } else {
            Toast.makeText(requireContext(), R.string.permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onItemClick(Song song, int position) {
        ((Callback) requireActivity()).onPlayQueue(new ArrayList<>(songs), position);
    }

    @Override
    public void onLyricsClick(Song song) {
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

    @Override
    public void onItemLongClick(Song song) {
        if (song.path == null) return;
        boolean tagEditor =
                com.chumosplayer.util.SettingsManager.isTagEditorEnabled(requireContext());
        final String[] items = tagEditor
                ? new String[]{"加入歌单", "收藏", "编辑标签", "删除歌曲", "屏蔽所在文件夹"}
                : new String[]{"加入歌单", "收藏", "删除歌曲", "屏蔽所在文件夹"};
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(song.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {                                  // 加入歌单
                        com.chumosplayer.util.PlaylistPicker.show(requireContext(), song);
                    } else if (which == 1) {                           // 收藏
                        toggleFavorite(song);
                    } else if (tagEditor && which == 2) {              // 编辑标签
                        showTagEditor(song);
                    } else if (items[which].equals("删除歌曲")) {        // 删除歌曲
                        confirmDelete(song);
                    } else {                                           // 屏蔽所在文件夹
                        blockFolder(song);
                    }
                })
                .show();
    }

    private void toggleFavorite(Song song) {
        boolean now = com.chumosplayer.util.Favorites.toggle(requireContext(), song);
        Toast.makeText(requireContext(),
                now ? "已加入我的收藏 ♥" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    /** 删除歌曲：先确认，再依次处理 播放中 / 侧车 / 媒体库 / 引用 / 文件 */
    private void confirmDelete(final Song song) {
        final File f = new File(song.path);
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
            // 1) 正在播就先停，别让播放器握着即将删除的文件
            com.chumosplayer.playback.PlayService.stopIfPlayingPath(song.path);
            // 2) 侧车文件：同名歌词与封面
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
            // 3) 媒体库记录（id=0 是手动扫描未入库的，跳过）
            if (song.id > 0) {
                try {
                    app.getContentResolver().delete(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            android.provider.MediaStore.Audio.Media._ID + "=?",
                            new String[]{String.valueOf(song.id)});
                } catch (Exception ignore) { }
            }
            // 4) 引用清理：歌单 / 收藏 / 播放历史
            try {
                com.chumosplayer.util.PlaylistStore.removeFromAll(app, song);
                com.chumosplayer.util.Favorites.remove(app, song);
                com.chumosplayer.util.PlayHistory.remove(app, song);
            } catch (Exception ignore) { }
            // 5) 最后删本体
            boolean ok = f.delete();
            final boolean success = ok;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                reload();
                Toast.makeText(requireContext(),
                        success ? "已删除：" + base
                                : "删除失败：文件不存在或没有写权限",
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            });
        }, "delete-song").start();
    }

    /** 长按屏蔽：下载目录下的歌，屏蔽范围提到保存根目录（与"文件夹"页归组口径一致） */
    private void blockFolder(Song song) {
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
                            lifted ? "已屏蔽下载目录：" + target.getName()
                                    : "已屏蔽该文件夹",
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
        // 歌词：优先用已加载的，其次读同名 .lrc；留空表示不改动
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
        // 内容变高，套一层滚动避免被截断
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
        final File f = new File(song.path);
        final String lyrics = lrc == null ? "" : lrc.trim();
        new Thread(() -> {
            // 歌词非空才写入标签字段；空 = 保持原样
            boolean ok = com.chumosplayer.util.TagEditor.write(f, t, ar, al,
                    lyrics.isEmpty() ? null : lyrics);
            if (ok && !lyrics.isEmpty()) {
                // 同时落一份同名 .lrc，播放页与悬浮歌词都能直接读到
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
                    adapter.notifyDataSetChanged();
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

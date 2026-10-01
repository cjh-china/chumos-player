package com.chumosplayer.fragment;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chumosplayer.R;
import com.chumosplayer.adapter.SongAdapter;
import com.chumosplayer.model.Song;
import com.chumosplayer.net.BilibiliClient;
import com.chumosplayer.net.MyFreeMP3Client;
import com.chumosplayer.util.DownloadUtil;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** 在线搜索（myfreemp3.com.cn）+ 歌词 + 下载 */
public class OnlineFragment extends Fragment implements SongAdapter.Listener {

    private final List<Song> results = new ArrayList<>();
    private SongAdapter adapter;
    private EditText etSearch;
    private SearchTask task;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    /** 上次搜索的关键词，供下拉刷新复用 */
    private String lastKeyword;

    private static final int REQ_WRITE = 2;
    /** 等待写权限授予后要下载的歌曲 */
    private Song pendingDownload;
    /** 下载时是否压缩音频（转码为低码率 AAC） */
    private boolean compressAudio = false;
    /** 压缩码率（bps） */
    private int compressBitrate = com.chumosplayer.util.DownloadUtil.COMPRESS_BITRATE;
    /** 可选压缩码率：显示文本 -> bps */
    private static final String[] BITRATE_LABELS = {"48 kbps（最省）", "64 kbps（推荐）", "96 kbps", "128 kbps"};
    private static final int[] BITRATE_VALUES = {48000, 64000, 96000, 128000};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_online, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etSearch = view.findViewById(R.id.et_search);
        Button btn = view.findViewById(R.id.btn_search);
        RecyclerView rv = view.findViewById(R.id.recycler_online);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SongAdapter(results, this, true);
        rv.setAdapter(adapter);

        swipe = view.findViewById(R.id.swipe_online);
        swipe.setOnRefreshListener(() -> {
            String kw = lastKeyword != null ? lastKeyword
                    : etSearch.getText().toString().trim();
            if (kw.isEmpty()) {
                swipe.setRefreshing(false);
                Toast.makeText(requireContext(), "请先输入关键词搜索", Toast.LENGTH_SHORT).show();
                return;
            }
            startSearch(kw);
        });

        btn.setOnClickListener(v -> doSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
    }

    private void startSearch(String kw) {
        lastKeyword = kw;
        task = new SearchTask(this);
        // 用线程池执行器，避免与封面加载等任务在串行队列中相互排队
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, kw);
    }

    /** 搜索按钮/键盘搜索：读取输入框并搜索 */
    private void doSearch() {
        String kw = etSearch.getText().toString().trim();
        if (kw.isEmpty()) return;
        hideKeyboard();
        startSearch(kw);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private static class SearchTask extends AsyncTask<String, Void, List<Song>> {
        private final WeakReference<OnlineFragment> ref;
        private Exception error;
        /** 一个源失败、另一个源有结果时的原因（用于提示，不中断结果展示） */
        private String partialError;

        SearchTask(OnlineFragment f) { ref = new WeakReference<>(f); }

        @Override
        protected List<Song> doInBackground(String... kws) {
            final String kw = kws[0];
            final List<Song> netease = new ArrayList<>();
            final List<Song> bilibili = new ArrayList<>();
            final Exception[] errs = new Exception[2];
            // 网易云与 B 站两个源并行搜索，互不阻塞
            Thread t1 = new Thread(() -> {
                try {
                    List<Song> songs = MyFreeMP3Client.search(kw, 1);
                    if (songs != null) netease.addAll(songs);
                } catch (Exception e) {
                    errs[0] = e;
                }
            });
            Thread t2 = new Thread(() -> {
                try {
                    List<Song> songs = BilibiliClient.search(kw);
                    if (songs != null) bilibili.addAll(songs);
                } catch (Exception e) {
                    errs[1] = e;
                }
            });
            t1.start();
            t2.start();
            try { t1.join(); t2.join(); } catch (InterruptedException ignore) { }
            // 合并展示：网易云结果在前，B 站结果在后
            List<Song> merged = new ArrayList<>(netease);
            merged.addAll(bilibili);
            if (merged.isEmpty()) {
                // 两个源都没结果：两个都报错就合并提示，方便判断是哪个源挂了
                if (errs[0] != null && errs[1] != null) {
                    error = new IOException("网易云：" + errs[0].getMessage()
                            + "；B站：" + errs[1].getMessage());
                } else {
                    error = errs[0] != null ? errs[0] : errs[1];
                }
            } else if (errs[1] != null) {
                partialError = "B站：" + errs[1].getMessage();
            } else if (errs[0] != null) {
                partialError = "网易云：" + errs[0].getMessage();
            }
            return merged;
        }

        @Override
        protected void onPostExecute(List<Song> songs) {
            OnlineFragment f = ref.get();
            if (f == null || !f.isAdded()) return;
            if (f.swipe != null) f.swipe.setRefreshing(false);
            if ((songs == null || songs.isEmpty()) && error != null) {
                Toast.makeText(f.requireContext(), "搜索失败：" + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            f.results.clear();
            if (songs != null) f.results.addAll(songs);
            f.adapter.notifyDataSetChanged();
            if (f.results.isEmpty()) {
                Toast.makeText(f.requireContext(), R.string.no_result, Toast.LENGTH_SHORT).show();
            } else if (partialError != null) {
                // 有结果但某个源失败了（常见于 B 站风控），告诉用户结果不完整
                Toast.makeText(f.requireContext(),
                        "已显示可用结果（" + partialError + " 本次未返回）",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---- SongAdapter.Listener ----

    @Override
    public void onItemClick(Song song, int position) {
        // B 站源的 url 是 bilibili://bvid 占位，播放前先解析为直链
        if (song.url != null && song.url.startsWith("bilibili://")) {
            new ResolveTask(this, position, true).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, song);
            return;
        }
        ((LocalMusicFragment.Callback) requireActivity())
                .onPlayQueue(new ArrayList<>(results), position);
    }

    @Override
    public void onLyricsClick(Song song) {
        LyricsDialogFragment.show(getParentFragmentManager(), song);
    }

    @Override
    public void onDownloadClick(Song song) {
        showDownloadDialog(song);
    }

    /** 在线歌曲长按 → 加入歌单 / 收藏 */
    @Override
    public void onItemLongClick(Song song) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(song.title)
                .setItems(new String[]{"加入歌单", "收藏"}, (d, which) -> {
                    if (which == 0) {
                        com.chumosplayer.util.PlaylistPicker.show(requireContext(), song);
                    } else {
                        boolean now = com.chumosplayer.util.Favorites.toggle(
                                requireContext(), song);
                        Toast.makeText(requireContext(),
                                now ? "已加入我的收藏 ♥" : "已取消收藏",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 下载选项弹窗：
     * - 仅缓存直链：解析网易云 302 直链存入 NeteaseCache，不落盘（省流量，播放更快）
     * - 下载到本地：解析后下载到保存目录（可在输入框改目录，留空用默认/上次设置）
     */
    private void showDownloadDialog(final Song song) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(requireContext());
        b.setTitle("下载选项 - " + song.title);
        final android.widget.EditText etDir = new android.widget.EditText(requireContext());
        String cur = com.chumosplayer.util.DownloadUtil
                .saveDir(requireContext()).getAbsolutePath();
        etDir.setText(cur);
        etDir.setSingleLine(true);

        // 目录输入框 + "浏览"按钮（浏览选择器已过滤系统 root 目录）
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.addView(etDir, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button btnBrowse = new android.widget.Button(requireContext());
        btnBrowse.setText("浏览");
        btnBrowse.setOnClickListener(v -> com.chumosplayer.util.DirPickerDialog.show(
                requireContext(), new java.io.File(etDir.getText().toString().trim()),
                dir -> etDir.setText(dir.getAbsolutePath())));
        row.addView(btnBrowse);

        // 目录行 + 压缩选项 + 码率选择
        final android.widget.CheckBox cbCompress = new android.widget.CheckBox(requireContext());
        cbCompress.setText("压缩音频（转低码率 AAC，更省空间，音质有损）");
        cbCompress.setChecked(false);

        final android.widget.Spinner spBitrate = new android.widget.Spinner(requireContext());
        spBitrate.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, BITRATE_LABELS));
        spBitrate.setSelection(1); // 默认 64 kbps
        spBitrate.setEnabled(false);
        // 仅勾选压缩时可选码率
        cbCompress.setOnCheckedChangeListener((v, checked) -> spBitrate.setEnabled(checked));

        android.widget.LinearLayout content = new android.widget.LinearLayout(requireContext());
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(row, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(cbCompress, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(spBitrate, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(requireContext());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        b.setMessage("保存目录：");
        b.setView(wrap);

        b.setPositiveButton("下载到本地", (d, w) -> {
            // 目录输入非空且与当前不同则记住
            String input = etDir.getText().toString().trim();
            if (!input.isEmpty() && !input.equals(cur)) {
                com.chumosplayer.util.DownloadUtil.setSaveDir(requireContext(), input);
            }
            compressAudio = cbCompress.isChecked();
            int sel = spBitrate.getSelectedItemPosition();
            if (sel >= 0 && sel < BITRATE_VALUES.length) compressBitrate = BITRATE_VALUES[sel];
            startDownload(song);
        });
        b.setNeutralButton("仅缓存直链", (d, w) -> cacheLinkOnly(song));
        b.setNegativeButton(android.R.string.cancel, null);
        b.show();
    }

    /** 仅解析并缓存直链，不下载文件 */
    private void cacheLinkOnly(Song song) {
        if (song.url != null && song.url.startsWith("bilibili://")) {
            Toast.makeText(requireContext(), "B站源不支持直链缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "正在解析直链…", Toast.LENGTH_SHORT).show();
        new CacheTask(this).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, song);
    }

    private void startDownload(Song song) {
        // 下载要写公共存储目录，需先确保已授予写权限（READ 权限不足以写入）
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownload = song;
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        doStartDownload(song);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_WRITE) {
            if (grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Song s = pendingDownload;
                pendingDownload = null;
                if (s != null) doStartDownload(s);
            } else {
                Toast.makeText(requireContext(), "需要存储权限才能下载", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doStartDownload(Song song) {
        Toast.makeText(requireContext(),
                getString(R.string.downloading, song.title), Toast.LENGTH_SHORT).show();
        if (song.url != null && song.url.startsWith("bilibili://")) {
            new ResolveTask(this, -1, false).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, song);
            return;
        }
        new DownloadTask(this, compressAudio, compressBitrate)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, song);
    }

    /** 解析 bilibili://bvid 占位为真实直链后，再播放或下载 */
    private static class ResolveTask extends AsyncTask<Song, Void, Song> {
        private final WeakReference<OnlineFragment> ref;
        private final int playIndex;
        private final boolean playAfter;
        private Exception error;

        ResolveTask(OnlineFragment f, int playIndex, boolean playAfter) {
            ref = new WeakReference<>(f);
            this.playIndex = playIndex;
            this.playAfter = playAfter;
        }

        @Override
        protected Song doInBackground(Song... songs) {
            Song s = songs[0];
            try {
                BilibiliClient.ensureResolved(s);
            } catch (Exception e) {
                error = e;
            }
            return s;
        }

        @Override
        protected void onPostExecute(Song s) {
            OnlineFragment f = ref.get();
            if (f == null || !f.isAdded()) return;
            if (error != null) {
                Toast.makeText(f.requireContext(), "解析失败：" + error.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (playAfter && playIndex >= 0) {
                f.onItemClick(s, playIndex); // 已解析，走正常播放路径
            } else {
                f.onDownloadClick(s); // 已解析，走正常下载路径
            }
        }
    }

    /** 仅解析直链并写入 NeteaseCache，不下载文件 */
    private static class CacheTask extends AsyncTask<Song, Void, Song> {
        private final WeakReference<OnlineFragment> ref;
        private Exception error;

        CacheTask(OnlineFragment f) { ref = new WeakReference<>(f); }

        @Override
        protected Song doInBackground(Song... songs) {
            Song s = songs[0];
            try {
                Context ctx = ref.get() != null
                        ? ref.get().requireContext().getApplicationContext() : null;
                com.chumosplayer.net.NeteaseCache.resolve(ctx, s);
            } catch (Exception e) {
                error = e;
            }
            return s;
        }

        @Override
        protected void onPostExecute(Song s) {
            OnlineFragment f = ref.get();
            if (f == null || !f.isAdded()) return;
            if (error != null) {
                Toast.makeText(f.requireContext(),
                        f.getString(R.string.download_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(f.requireContext(), "直链已缓存，播放更快", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static class DownloadTask extends AsyncTask<Song, Void, String> {
        private final WeakReference<OnlineFragment> ref;
        private final boolean compress;
        private final int bitrate;
        private Exception error;

        DownloadTask(OnlineFragment f, boolean compress, int bitrate) {
            ref = new WeakReference<>(f);
            this.compress = compress;
            this.bitrate = bitrate;
        }

        @Override
        protected String doInBackground(Song... songs) {
            Song s = songs[0];
            try {
                OnlineFragment f = ref.get();
                Context ctx = f != null ? f.requireContext().getApplicationContext() : null;
                String filename = s.artist.isEmpty() ? s.title : s.artist + " - " + s.title;
                // 前导零问题由 DownloadUtil 下载后自动检测修复，无需按源区分
                return DownloadUtil.download(ctx, s.url, filename, s.lrc, s.pic, compress, bitrate, null);
            } catch (Exception e) {
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(String path) {
            OnlineFragment f = ref.get();
            if (f == null || !f.isAdded()) return;
            if (error instanceof DownloadUtil.FileExistsException) {
                Toast.makeText(f.requireContext(), error.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            if (error != null || path == null) {
                Toast.makeText(f.requireContext(),
                        com.chumosplayer.util.Tips.onError()
                                + "\n" + (error != null ? error.getMessage() : "?"),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(f.requireContext(),
                        com.chumosplayer.util.Tips.onDownload(),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}


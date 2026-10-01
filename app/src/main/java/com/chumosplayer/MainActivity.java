package com.chumosplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager.widget.ViewPager;

import com.chumosplayer.adapter.MainPagerAdapter;
import com.chumosplayer.fragment.LocalMusicFragment;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.CoverLoader;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

public class MainActivity extends AppCompatActivity implements
        LocalMusicFragment.Callback, PlayService.Listener {

    private PlayService playService;
    private boolean bound = false;

    // 迷你播放条
    private View miniPlayer;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle, tvMiniSubtitle;
    private ImageButton btnMiniPlay;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    /**
     * 暖心小提示定时器：隔一段时间随机弹一条。
     * 为避免打扰（原实现首条 30 秒后就弹、之后每 45~100 秒一条，几乎刷屏）：
     * - 首条推迟到启动 8 分钟后
     * - 之后每 15~25 分钟随机一条
     * - 单次启动最多弹 3 条
     */
    private static final long TIP_FIRST_DELAY_MS = 8 * 60 * 1000L;
    private static final long TIP_MIN_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long TIP_JITTER_MS = 10 * 60 * 1000L;
    private static final int TIP_MAX_PER_SESSION = 3;

    private int tipsShownThisSession = 0;
    private final Handler tipHandler = new Handler(Looper.getMainLooper());
    private final Runnable tipRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (tipsShownThisSession < TIP_MAX_PER_SESSION) {
                tipsShownThisSession++;
                Toast.makeText(MainActivity.this, com.chumosplayer.util.Tips.idle(),
                        Toast.LENGTH_SHORT).show();
            }
            // 到达本次启动的上限就不再排下一次；否则按 15~25 分钟随机间隔续排
            if (tipsShownThisSession < TIP_MAX_PER_SESSION) {
                tipHandler.postDelayed(this, TIP_MIN_INTERVAL_MS
                        + (long) (Math.random() * TIP_JITTER_MS));
            }
        }
    };
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (bound && playService != null) {
                Song s = playService.currentSong();
                int total = s != null ? (int) s.duration : 0;
                onProgress(playService.position(), total);
            }
            progressHandler.postDelayed(this, 500);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playService = ((PlayService.PlayBinder) service).getService();
            bound = true;
            playService.addListener(MainActivity.this);
            startProgressTask();
            tryPlayPendingHistory();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            playService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.chumosplayer.util.SettingsManager.applyDarkMode(
                com.chumosplayer.util.SettingsManager.getDarkMode(this));
        com.chumosplayer.util.SettingsManager.applyTheme(this); // 纯黑/主题色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        String[] titles = {getString(R.string.tab_local), getString(R.string.tab_online)};
        ViewPager vp = findViewById(R.id.viewpager);
        vp.setAdapter(new MainPagerAdapter(getSupportFragmentManager(), titles));
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.setupWithViewPager(vp);

        // 迷你播放条
        miniPlayer = findViewById(R.id.mini_player);
        ivMiniCover = findViewById(R.id.iv_mini_cover);
        tvMiniTitle = findViewById(R.id.tv_mini_title);
        tvMiniSubtitle = findViewById(R.id.tv_mini_subtitle);
        btnMiniPlay = findViewById(R.id.btn_mini_play);

        btnMiniPlay.setOnClickListener(v -> {
            if (playService != null) playService.togglePlay();
        });
        findViewById(R.id.btn_mini_next).setOnClickListener(v -> {
            if (playService != null) playService.next();
        });
        // 点迷你条（非按钮区域）进全屏播放页
        miniPlayer.setOnClickListener(v -> {
            if (playService != null && playService.currentSong() != null) {
                startActivity(new Intent(this, NowPlayingActivity.class));
            }
        });

        bindService(new Intent(this, PlayService.class), conn, Context.BIND_AUTO_CREATE);

        showGreeting();
        ensureAllFilesAccess();
        askNotificationPermission();
        handleHistoryPlay(getIntent());

        // 启动暖心小提示（首条 8 分钟后，之后每 15~25 分钟，单次启动最多 3 条）
        tipHandler.postDelayed(tipRunnable, TIP_FIRST_DELAY_MS);
    }

    /**
     * 启动问候：首次打开弹欢迎语，之后每次按时段问候。
     * 弹出时机由设置里的「问候语弹出时间」控制；设为"不弹出"时直接跳过。
     */
    private void showGreeting() {
        int delay = com.chumosplayer.util.SettingsManager.getGreetingDelayMs(this);
        if (delay == com.chumosplayer.util.SettingsManager.GREETING_NEVER) {
            // 不消费 first_open 标记：以后重新开启问候语仍能看到欢迎语
            return;
        }
        android.content.SharedPreferences sp =
                getSharedPreferences("tips_prefs", MODE_PRIVATE);
        boolean firstOpen = sp.getBoolean("first_open", true);
        final String msg;
        if (firstOpen) {
            sp.edit().putBoolean("first_open", false).apply();
            msg = com.chumosplayer.util.Tips.welcome();
        } else {
            msg = com.chumosplayer.util.Tips.greeting();
        }
        tipHandler.postDelayed(() -> {
            if (!isFinishing()) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        }, Math.max(0, delay));
    }

    /** 由 Fragment 回调：以指定队列从第 startIndex 首开始播放 */
    @Override
    public void onPlayQueue(List<Song> queue, int startIndex) {
        if (playService != null) playService.setQueue(queue, startIndex);
        else Toast.makeText(this, "播放服务未就绪", Toast.LENGTH_SHORT).show();
    }

    // ---- PlayService.Listener ----

    @Override
    public void onPlayingChanged(final Song song, final boolean playing) {
        runOnUiThread(() -> {
            if (song != null) {
                miniPlayer.setVisibility(View.VISIBLE);
                tvMiniTitle.setText(song.title);
                tvMiniSubtitle.setText(song.artist);
                CoverLoader.load(ivMiniCover, song);
            } else {
                miniPlayer.setVisibility(View.GONE);
            }
            btnMiniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        });
    }

    @Override
    public void onProgress(int curMs, int totalMs) {
        // 迷你条不显示进度，全屏播放页自己监听
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    /** 定时刷新播放进度（触发 Listener 通知） */
    private void startProgressTask() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    static String formatMs(int ms) {
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ---- 黑名单管理 ----

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 3, 0, "播放历史");
        menu.add(0, 4, 1, "我的歌单");
        menu.add(0, 5, 2, "我的收藏");
        menu.add(0, 2, 3, "设置");
        menu.add(0, 1, 4, "屏蔽管理");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 3) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        }
        if (item.getItemId() == 4) {
            startActivity(new Intent(this, PlaylistActivity.class));
            return true;
        }
        if (item.getItemId() == 5) {
            startActivity(new Intent(this, FavoritesActivity.class));
            return true;
        }
        if (item.getItemId() == 2) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == 1) {
            showBlacklistDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleHistoryPlay(intent);
    }

    /** 从播放历史页跳回时，播放对应歌曲 */
    private void handleHistoryPlay(Intent intent) {
        if (intent == null || !intent.hasExtra(HistoryActivity.EXTRA_INDEX)) return;
        int idx = intent.getIntExtra(HistoryActivity.EXTRA_INDEX, -1);
        if (idx < 0) return;
        java.util.List<Song> hist = com.chumosplayer.util.PlayHistory.getAll(this);
        if (hist.isEmpty()) return;
        // 延迟到服务绑定后再播
        final java.util.List<Song> queue = new java.util.ArrayList<>(hist);
        pendingHistoryPlay = new int[]{idx};
        tryPlayPendingHistory();
    }

    private int[] pendingHistoryPlay;

    private void tryPlayPendingHistory() {
        if (pendingHistoryPlay == null) return;
        if (playService == null) return; // 服务未就绪，等服务连接后重试
        int idx = pendingHistoryPlay[0];
        pendingHistoryPlay = null;
        java.util.List<Song> hist = com.chumosplayer.util.PlayHistory.getAll(this);
        if (hist.isEmpty()) return;
        if (idx >= hist.size()) idx = 0;
        playService.setQueue(new java.util.ArrayList<>(hist), idx);
    }

    /** 黑名单管理对话框：查看/移除/添加被屏蔽的文件夹 */
    private void showBlacklistDialog() {
        final java.util.Set<String> dirs =
                com.chumosplayer.util.BlacklistManager.getAll(this);
        final String[] arr = dirs.toArray(new String[0]);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("屏蔽的文件夹");
        if (arr.length == 0) {
            b.setMessage("暂无屏蔽的文件夹。\n长按本地歌曲可快速屏蔽其所在文件夹。");
        } else {
            b.setItems(arr, (d, which) -> {
                final String dir = arr[which];
                new android.app.AlertDialog.Builder(this)
                        .setTitle("移除屏蔽")
                        .setMessage("恢复显示下列文件夹？\n\n" + dir)
                        .setPositiveButton("恢复", (dd, ww) -> {
                            com.chumosplayer.util.BlacklistManager.remove(this, dir);
                            Toast.makeText(this, "已恢复显示", Toast.LENGTH_SHORT).show();
                            refreshLocal();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }
        b.setPositiveButton("添加文件夹", (d, w) ->
                com.chumosplayer.util.DirPickerDialog.show(this,
                        android.os.Environment.getExternalStorageDirectory(), dir -> {
                            com.chumosplayer.util.BlacklistManager.add(this,
                                    dir.getAbsolutePath());
                            Toast.makeText(this, "已屏蔽：" + dir.getName(),
                                    Toast.LENGTH_SHORT).show();
                            refreshLocal();
                        }));
        b.setNegativeButton("关闭", null);
        b.show();
    }

    /** Android 11+ 写外置卷（TF 卡）需「所有文件访问」权限，未授权则引导一次 */
    private void ensureAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        if (android.os.Environment.isExternalStorageManager()) return;
        android.content.SharedPreferences sp =
                getSharedPreferences("tips_prefs", MODE_PRIVATE);
        if (sp.getBoolean("asked_all_files", false)) return;
        sp.edit().putBoolean("asked_all_files", true).apply();
        new android.app.AlertDialog.Builder(this)
                .setTitle("授权访问外置存储")
                .setMessage("为支持保存到 TF 卡等外置存储，建议授予「所有文件访问」权限。\n\n若只用内置存储，可忽略此提示。")
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignore) { }
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

    /** Android 13+：通知权限（通知栏/锁屏的播放控制需要），只在首次询问一次 */
    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences sp = getSharedPreferences("tips_prefs", MODE_PRIVATE);
        if (sp.getBoolean("asked_notif", false)) return;
        sp.edit().putBoolean("asked_notif", true).apply();
        try {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        } catch (Exception ignore) { }
    }

    /** 通知本地音乐页重新扫描 */
    private void refreshLocal() {
        androidx.fragment.app.Fragment f = getSupportFragmentManager()
                .findFragmentByTag("android:switcher:" + R.id.viewpager + ":0");
        if (f instanceof LocalMusicFragment) {
            ((LocalMusicFragment) f).reload();
        }
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacks(progressRunnable);
        tipHandler.removeCallbacks(tipRunnable);
        if (bound && playService != null) {
            playService.removeListener(this);
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }
}

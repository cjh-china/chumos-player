package com.chumosplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
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
import com.chumosplayer.util.Favorites;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements
        LocalMusicFragment.Callback, PlayService.Listener {

    private PlayService playService;
    private boolean bound = false;

    private ViewPager viewPager;
    private BottomNavigationView bottomNav;

    // 迷你播放条
    private View miniPlayer;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle, tvMiniSubtitle;
    private ImageButton btnMiniPlay, btnMiniFav, btnMiniNext;
    private ProgressBar miniProgress;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    /** 暖心小提示：首条 8 分钟后，之后每 15~25 分钟，单次启动最多 3 条 */
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
                int pos = playService.position();
                int total = playService.duration();
                if (total > 0) {
                    miniProgress.setProgress((int) (1000L * pos / total));
                } else {
                    miniProgress.setProgress(0);
                }
                onProgress(pos, total);
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
            onPlayingChanged(playService.currentSong(), playService.isPlaying());
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
        com.chumosplayer.util.SettingsManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        String[] titles = {getString(R.string.tab_local), getString(R.string.tab_online),
                getString(R.string.tab_mine)};
        viewPager = findViewById(R.id.viewpager);
        viewPager.setAdapter(new MainPagerAdapter(getSupportFragmentManager(), titles));
        viewPager.setOffscreenPageLimit(2);
        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_local) viewPager.setCurrentItem(0, true);
            else if (id == R.id.nav_online) viewPager.setCurrentItem(1, true);
            else viewPager.setCurrentItem(2, true);
            return true;
        });
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                int id = position == 0 ? R.id.nav_local
                        : position == 1 ? R.id.nav_online : R.id.nav_mine;
                bottomNav.setSelectedItemId(id);
            }
        });

        initMiniPlayer();
        bindService(new Intent(this, PlayService.class), conn, Context.BIND_AUTO_CREATE);

        showGreeting();
        ensureAllFilesAccess();
        askNotificationPermission();
        handleHistoryPlay(getIntent());
        tipHandler.postDelayed(tipRunnable, TIP_FIRST_DELAY_MS);
    }

    private void initMiniPlayer() {
        miniPlayer = findViewById(R.id.mini_player);
        ivMiniCover = findViewById(R.id.iv_mini_cover);
        tvMiniTitle = findViewById(R.id.tv_mini_title);
        tvMiniSubtitle = findViewById(R.id.tv_mini_subtitle);
        btnMiniPlay = findViewById(R.id.btn_mini_play);
        btnMiniFav = findViewById(R.id.btn_mini_fav);
        btnMiniNext = findViewById(R.id.btn_mini_next);
        miniProgress = findViewById(R.id.mini_progress);

        btnMiniPlay.setOnClickListener(v -> {
            if (playService != null) playService.togglePlay();
        });
        btnMiniNext.setOnClickListener(v -> {
            if (playService != null) playService.next();
        });
        btnMiniFav.setOnClickListener(v -> {
            Song cur = playService != null ? playService.currentSong() : null;
            if (cur == null) return;
            boolean now = Favorites.toggle(this, cur);
            updateMiniFavorite(now);
            Toast.makeText(this, getString(now ? R.string.toast_favorite_add
                    : R.string.toast_favorite_remove), Toast.LENGTH_SHORT).show();
        });
        miniPlayer.setOnClickListener(v -> openNowPlaying());

        // 手势：左右滑切歌、上滑展开播放页；只有识别为 fling 时才消费事件，
        // 其余情况交还给 View，保持点击 / 无障碍点击可用。
        final GestureDetector gesture = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) { return false; }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > 80 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            if (playService != null) {
                                if (dx < 0) playService.next();
                                else playService.prev();
                            }
                            return true;
                        }
                        if (dy < -100 && Math.abs(dy) > Math.abs(dx)) {
                            openNowPlaying();
                            return true;
                        }
                        return false;
                    }
                });
        miniPlayer.setOnTouchListener((v, event) -> gesture.onTouchEvent(event));
    }

    private void openNowPlaying() {
        if (playService != null && playService.currentSong() != null) {
            startActivity(new Intent(this, NowPlayingActivity.class));
        }
    }

    private void updateMiniFavorite(boolean fav) {
        btnMiniFav.setImageResource(fav ? R.drawable.ic_favorite
                : R.drawable.ic_favorite_border);
    }

    /** 启动问候：首次打开弹欢迎语，之后按时段问候 */
    private void showGreeting() {
        int delay = com.chumosplayer.util.SettingsManager.getGreetingDelayMs(this);
        if (delay == com.chumosplayer.util.SettingsManager.GREETING_NEVER) return;
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
                updateMiniFavorite(Favorites.isFavorite(this, song));
            } else {
                miniPlayer.setVisibility(View.GONE);
            }
            btnMiniPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            btnMiniPlay.setContentDescription(getString(playing ? R.string.cd_pause
                    : R.string.cd_play));
        });
    }

    @Override
    public void onProgress(int curMs, int totalMs) {
        // 迷你条进度由 progressRunnable 直接更新，这里留空给其它监听者
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void startProgressTask() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    static String formatMs(int ms) {
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ---- 溢出菜单：保留为老用户的快捷路径，主入口已移到「我的」页 ----

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
        int id = item.getItemId();
        if (id == 3) startActivity(new Intent(this, HistoryActivity.class));
        else if (id == 4) startActivity(new Intent(this, PlaylistActivity.class));
        else if (id == 5) startActivity(new Intent(this, FavoritesActivity.class));
        else if (id == 2) startActivity(new Intent(this, SettingsActivity.class));
        else if (id == 1) showBlacklistDialog();
        else return super.onOptionsItemSelected(item);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleHistoryPlay(intent);
    }

    private void handleHistoryPlay(Intent intent) {
        if (intent == null || !intent.hasExtra(HistoryActivity.EXTRA_INDEX)) return;
        int idx = intent.getIntExtra(HistoryActivity.EXTRA_INDEX, -1);
        if (idx < 0) return;
        pendingHistoryPlay = new int[]{idx};
        tryPlayPendingHistory();
    }

    private int[] pendingHistoryPlay;

    private void tryPlayPendingHistory() {
        if (pendingHistoryPlay == null || playService == null) return;
        int idx = pendingHistoryPlay[0];
        pendingHistoryPlay = null;
        List<Song> hist = com.chumosplayer.util.PlayHistory.getAll(this);
        if (hist.isEmpty()) return;
        if (idx >= hist.size()) idx = 0;
        playService.setQueue(new java.util.ArrayList<>(hist), idx);
    }

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
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignore) { }
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }

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

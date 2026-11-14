package com.chumosplayer;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.CoverLoader;
import com.chumosplayer.util.LrcParser;

import java.util.ArrayList;
import java.util.List;

/** APlayer 风格全屏播放页：大封面 + 进度 + 歌词 */
public class NowPlayingActivity extends AppCompatActivity implements PlayService.Listener {

    public static final String EXTRA_SHOW_LYRICS = "show_lyrics";

    private ImageView ivCover;
    private android.view.View coverContainer;
    private TextView tvTitle, tvSubtitle, tvCur, tvTotal;
    private SeekBar seekBar;
    private ImageButton btnPlay;
    /** 播放模式按钮（列表循环/随机/单曲循环） */
    private ImageButton btnMode;
    /** 队列按钮 */
    private ImageButton btnQueue;
    /** 收藏按钮 */
    private ImageButton btnLike;
    /** 播放页快捷工具：倍速 / 定时标签 */
    private TextView tvSpeedValue, tvTimerValue;
    private boolean userSeeking = false;
    private boolean bound = false;
    private PlayService playService;

    /** 当前已知的总时长（播放器实测值优先，取不到时用歌曲元数据兜底） */
    private int knownDuration;
    /** 当前绑定的歌曲；换歌时重置时长与 seek 缓存 */
    private Song boundSong;
    /** 松手后希望到达的位置：播放器真正跳过去之前先显示它，避免进度条"弹回原位" */
    private int seekTargetMs = -1;
    private long seekTargetAt;
    /** 等待播放器跟上 seek 目标的最长时间 */
    private static final long SEEK_SETTLE_MS = 2500;

    // 歌词区（随播放高亮并滚动）
    private ScrollView scrollLyrics;
    private LinearLayout llLyrics;
    private final List<LrcParser.Line> lyricsLines = new ArrayList<>();
    private final List<TextView> lyricViews = new ArrayList<>();
    private int activeLyric = -1;
    private String boundLrc;
    /** 当前高亮行的逐字信息（普通歌词为空），用于逐字高亮 */
    private List<LrcParser.Word> activeWords = java.util.Collections.emptyList();
    private int activeWord = -1;

    /** 封面黑胶缓慢旋转动画（仅无真实封面时的占位黑胶才转） */
    private ObjectAnimator coverSpin;
    /** 当前歌曲是否有真实封面；有则不旋转 */
    private boolean hasRealCover = true;
    /** 最近一次的播放状态，供封面加载回调后重算旋转 */
    private boolean isPlayingNow = false;
    /** 当前歌曲是否有歌词；有则允许在封面/歌词间切换 */
    private boolean hasLyrics = false;
    /** 当前是否显示歌词视图（有歌词时可切换） */
    private boolean showingLyrics = true;

    /** 进度驱动：PlayService 不主动回调 onProgress，这里自行定时读取并刷新进度与歌词 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int toolTick;
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (bound && playService != null) {
                applyProgress(playService.position(), playService.duration());
                // 定时倒计时每 3 秒刷新一次即可，不必每帧 setText
                if (++toolTick % 10 == 0) refreshTools();
            }
            uiHandler.postDelayed(this, 300);
        }
    };

    /**
     * 统一刷新进度：确定总时长、处理刚拖动的 seek 目标、更新进度条与时间文本。
     * 总时长恒定优先取播放器实测值，其次歌曲元数据，最后沿用上次已知值——
     * 任何时候都不能让进度条停留在默认的 0~100 范围（那样拖动只会 seek 到几十毫秒内，等于无法快进）。
     */
    private void applyProgress(int curMs, int totalMs) {
        if (totalMs > 0) {
            knownDuration = totalMs;
        } else if (knownDuration <= 0 && playService != null) {
            Song s = playService.currentSong();
            if (s != null && s.duration > 0) knownDuration = (int) s.duration;
        }

        if (userSeeking) {
            updateLyricsHighlight(curMs);
            return;
        }

        if (knownDuration > 0 && seekBar.getMax() != knownDuration) {
            seekBar.setMax(knownDuration);
            tvTotal.setText(MainActivity.formatMs(knownDuration));
        }

        int shown = curMs;
        if (seekTargetMs >= 0) {
            boolean arrived = Math.abs(curMs - seekTargetMs) < 700;
            boolean timeout = android.os.SystemClock.uptimeMillis() - seekTargetAt > SEEK_SETTLE_MS;
            if (arrived || timeout) {
                seekTargetMs = -1; // 已经跳过去（或超时放弃），交还给真实播放进度
            } else {
                shown = seekTargetMs; // 播放器还在跳，先把进度条钉在目标位置
            }
        }
        if (knownDuration > 0) {
            seekBar.setProgress(shown);
        }
        tvCur.setText(MainActivity.formatMs(shown));
        updateLyricsHighlight(shown);
    }

    private final android.content.ServiceConnection conn = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
            playService = ((PlayService.PlayBinder) service).getService();
            bound = true;
            playService.addListener(NowPlayingActivity.this);
            refreshAll();
            uiHandler.removeCallbacks(progressTick);
            uiHandler.post(progressTick);
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
        setContentView(R.layout.activity_now_playing);

        ivCover = findViewById(R.id.iv_np_cover);
        coverContainer = findViewById(R.id.cover_container);
        tvTitle = findViewById(R.id.tv_np_title);
        tvSubtitle = findViewById(R.id.tv_np_subtitle);
        tvCur = findViewById(R.id.tv_np_cur);
        tvTotal = findViewById(R.id.tv_np_total);
        seekBar = findViewById(R.id.seek_np);
        btnPlay = findViewById(R.id.btn_np_play);
        btnMode = findViewById(R.id.btn_np_mode);
        btnQueue = findViewById(R.id.btn_np_queue);
        btnLike = findViewById(R.id.btn_np_like);
        scrollLyrics = findViewById(R.id.scroll_lyrics);
        llLyrics = findViewById(R.id.ll_lyrics);
        tvSpeedValue = findViewById(R.id.tv_speed_value);
        tvTimerValue = findViewById(R.id.tv_timer_value);

        // 播放现场快捷工具：把原本埋在设置页的操作提到一屏之内
        findViewById(R.id.tool_speed).setOnClickListener(v -> pickSpeed());
        findViewById(R.id.tool_timer).setOnClickListener(v -> pickSleep());
        findViewById(R.id.tool_eq).setOnClickListener(v -> pickEq());
        findViewById(R.id.tool_more).setOnClickListener(v -> showMore());

        // 封面 / 歌词 互相切换（仅当歌曲有歌词时）
        coverContainer.setOnClickListener(v -> {
            if (hasLyrics) toggleLyricsCover();
        });
        scrollLyrics.setOnClickListener(v -> {
            if (hasLyrics) toggleLyricsCover();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> {
            if (playService != null) playService.togglePlay();
        });
        findViewById(R.id.btn_np_next).setOnClickListener(v -> {
            if (playService != null) playService.next();
        });
        findViewById(R.id.btn_np_prev).setOnClickListener(v -> {
            if (playService != null) playService.prev();
        });
        findViewById(R.id.btn_np_mode).setOnClickListener(v -> {
            if (playService == null) {
                android.widget.Toast.makeText(this, "播放服务还没就绪",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            int m = playService.cyclePlayMode();
            android.widget.Toast.makeText(this,
                    com.chumosplayer.playback.PlayService.modeName(m),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        btnQueue.setOnClickListener(v -> showQueue());
        btnLike.setOnClickListener(v -> {
            Song cur = playService != null ? playService.currentSong() : null;
            if (cur == null) {
                android.widget.Toast.makeText(this, "还没有正在播放的歌曲",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            boolean now = com.chumosplayer.util.Favorites.toggle(this, cur);
            btnLike.setImageResource(now ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            android.widget.Toast.makeText(this,
                    now ? "已加入我的收藏 ♥" : "已取消收藏",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvCur.setText(MainActivity.formatMs(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                // 总时长未知时进度条量程不可信（默认只有 0~100），seek 只会跳到开头，直接忽略
                if (knownDuration <= 0) return;
                int target = sb.getProgress();
                // 先把 UI 钉在目标位置，等播放器真的跳过去再交还给实时进度，避免"松手弹回"
                seekTargetMs = target;
                seekTargetAt = android.os.SystemClock.uptimeMillis();
                tvCur.setText(MainActivity.formatMs(target));
                if (playService != null) playService.seekTo(target);
            }
        });

        bindService(new Intent(this, PlayService.class), conn, android.content.Context.BIND_AUTO_CREATE);
    }

    /** 播放队列：交给底部弹层，可切歌 / 排序 / 移除 / 清空 */
    private void showQueue() {
        com.chumosplayer.util.QueueSheet.show(this);
    }

    // ---- 播放现场快捷工具 ----

    /** 状态可见：工具行实时显示当前倍速与睡眠定时剩余 */
    private void refreshTools() {
        if (tvSpeedValue != null) {
            tvSpeedValue.setText(String.valueOf(
                    com.chumosplayer.util.SettingsManager.getSpeed(this)) + "x");
        }
        if (tvTimerValue == null) return;
        if (playService == null) {
            tvTimerValue.setText(R.string.tool_timer);
            return;
        }
        if (playService.isSleepOnSongEnd()) {
            tvTimerValue.setText("本首结束");
        } else {
            long remain = playService.sleepRemainingMs();
            tvTimerValue.setText(remain > 0
                    ? "剩 " + (remain / 60000L + 1) + " 分"
                    : getString(R.string.tool_timer));
        }
    }

    private void pickSpeed() {
        final float[] speeds = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        final String[] labels = {"0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
        float cur = com.chumosplayer.util.SettingsManager.getSpeed(this);
        int sel = 1;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - cur) < 0.001f) sel = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setMessage("Android 6.0 及以上支持变速播放（不影响音调）。")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    com.chumosplayer.util.SettingsManager.setSpeed(this, speeds[which]);
                    if (playService != null) playService.setSpeed(speeds[which]);
                    refreshTools();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickSleep() {
        if (playService == null) {
            android.widget.Toast.makeText(this, "播放服务还没就绪",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = {"关闭", "15 分钟", "30 分钟", "60 分钟", "本首歌结束"};
        final int[] minutes = {0, 15, 30, 60, -1};
        new android.app.AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setSingleChoiceItems(labels, 0, (d, which) -> {
                    int v = minutes[which];
                    if (v < 0) {
                        playService.setSleepMinutes(0);
                        playService.setSleepOnSongEnd(true);
                    } else {
                        playService.setSleepOnSongEnd(false);
                        playService.setSleepMinutes(v);
                    }
                    refreshTools();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickEq() {
        String[] presets = playService == null
                ? new String[0] : playService.getEqPresetNames();
        if (presets.length == 0) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("音效")
                    .setMessage("开始播放一首歌后即可调节均衡器 / 重低音 / 环绕声。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        boolean enabled = com.chumosplayer.util.SettingsManager.isEqEnabled(this);
        String[] items = new String[presets.length + 1];
        items[0] = "关闭音效";
        System.arraycopy(presets, 0, items, 1, presets.length);
        int checked = enabled
                ? com.chumosplayer.util.SettingsManager.getEqPreset(this) + 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("均衡器")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        com.chumosplayer.util.SettingsManager.setEqEnabled(this, false);
                        if (playService != null) playService.setEqEnabled(false);
                    } else {
                        com.chumosplayer.util.SettingsManager.setEqEnabled(this, true);
                        com.chumosplayer.util.SettingsManager.setEqPreset(this, which - 1);
                        if (playService != null) {
                            playService.setEqEnabled(true);
                            playService.setEqPreset(which - 1);
                        }
                    }
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showMore() {
        final Song cur = playService != null ? playService.currentSong() : null;
        final String[] items = {"切换封面 / 歌词", "加入歌单", "歌曲信息",
                "桌面悬浮歌词", "设置"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.tool_more)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (hasLyrics) toggleLyricsCover();
                            else android.widget.Toast.makeText(this, "这首歌还没有歌词",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            if (cur != null) {
                                com.chumosplayer.util.PlaylistPicker.show(this, cur);
                            }
                            break;
                        case 2:
                            showSongInfo(cur);
                            break;
                        case 3:
                            toggleFloatLyric();
                            break;
                        default:
                            startActivity(new android.content.Intent(this,
                                    com.chumosplayer.SettingsActivity.class));
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSongInfo(Song s) {
        if (s == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(s.title == null ? "未知歌曲" : s.title).append('\n');
        sb.append("歌手：").append(s.artist == null ? "未知歌手" : s.artist).append('\n');
        sb.append("专辑：").append(s.album == null ? "未知专辑" : s.album).append('\n');
        sb.append("来源：").append(s.online ? "在线" : "本地文件");
        new android.app.AlertDialog.Builder(this)
                .setTitle("歌曲信息")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toggleFloatLyric() {
        boolean on = com.chumosplayer.util.SettingsManager.isFloatLyric(this);
        if (!on && android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("桌面悬浮歌词需要「在其他应用上层显示」权限。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignore) { }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        com.chumosplayer.util.SettingsManager.setFloatLyric(this, !on);
        android.content.Intent i = new android.content.Intent(this,
                com.chumosplayer.FloatingLyricsService.class);
        if (on) stopService(i);
        else startService(i);
        android.widget.Toast.makeText(this, on ? "已关闭桌面歌词" : "已开启桌面歌词",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void refreshAll() {
        if (!bound || playService == null) return;
        Song s = playService.currentSong();
        onPlayingChanged(s, playService.isPlaying());
        onProgress(playService.position(), playService.duration());
        refreshTools();
    }

    @Override
    public void onPlayingChanged(Song song, boolean playing) {
        runOnUiThread(() -> {
            btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            // 播放模式图标（切换模式时 PlayService 也会走这里刷新）
            if (btnMode != null) {
                int mode = playService != null ? playService.playMode()
                        : com.chumosplayer.util.SettingsManager.MODE_LOOP;
                btnMode.setImageResource(com.chumosplayer.playback.PlayService.modeIcon(mode));
            }
            // 收藏状态（换歌/外部改动后同步红心）
            if (btnLike != null) {
                boolean fav = song != null
                        && com.chumosplayer.util.Favorites.isFavorite(this, song);
                btnLike.setImageResource(fav ? R.drawable.ic_favorite
                        : R.drawable.ic_favorite_border);
            }
            isPlayingNow = playing;
            if (song != boundSong) {
                // 换歌：重置时长缓存与 seek 目标，并先用歌曲元数据把进度条量程设对
                boundSong = song;
                seekTargetMs = -1;
                knownDuration = (song != null && song.duration > 0) ? (int) song.duration : 0;
                if (knownDuration > 0) {
                    seekBar.setMax(knownDuration);
                    tvTotal.setText(MainActivity.formatMs(knownDuration));
                }
            }
            if (song != null) {
                tvTitle.setText(song.title);
                tvSubtitle.setText(song.subtitle());
                // 加载前先当作"有封面"（不转），等结果回来再决定是否旋转占位黑胶
                hasRealCover = true;
                CoverLoader.load(ivCover, song, has -> runOnUiThread(() -> {
                    hasRealCover = has;
                    updateCoverSpin(isPlayingNow);
                }));
                bindLyrics(song);
            }
            updateCoverSpin(playing);
            refreshTools();
        });
    }

    /** 只有"无真实封面的占位黑胶"在播放时才旋转；真实专辑封面保持正立 */
    private void updateCoverSpin(boolean playing) {
        if (coverSpin == null) {
            coverSpin = ObjectAnimator.ofFloat(ivCover, "rotation", 0f, 360f);
            coverSpin.setDuration(20000);          // 20 秒一圈
            coverSpin.setInterpolator(new LinearInterpolator());
            coverSpin.setRepeatCount(ObjectAnimator.INFINITE);
        }
        boolean shouldSpin = playing && !hasRealCover;
        if (shouldSpin) {
            if (coverSpin.isPaused()) coverSpin.resume();
            if (!coverSpin.isStarted()) coverSpin.start();
        } else {
            if (coverSpin.isStarted() && !coverSpin.isPaused()) coverSpin.pause();
            // 真实封面：转正，避免残留角度
            if (hasRealCover) ivCover.setRotation(0f);
        }
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, message,
                android.widget.Toast.LENGTH_LONG).show());
    }

    @Override
    public void onProgress(int curMs, int totalMs) {
        runOnUiThread(() -> applyProgress(curMs, totalMs));
    }

    /** 加载并渲染歌词：优先歌曲自带 lrc，其次本地同名 .lrc；无歌词则隐藏歌词区 */
    private void bindLyrics(Song s) {
        String lrc = (s.lrc != null && !s.lrc.isEmpty())
                ? s.lrc : com.chumosplayer.util.LocalLrcLoader.load(s);
        if (lrc == null) lrc = "";
        if (lrc.equals(boundLrc)) return; // 同一首歌，避免重复构建
        boundLrc = lrc;
        lyricsLines.clear();
        lyricViews.clear();
        llLyrics.removeAllViews();
        activeLyric = -1;
        activeWords = java.util.Collections.emptyList();
        activeWord = -1;
        if (!lrc.isEmpty()) lyricsLines.addAll(LrcParser.parse(lrc));

        if (lyricsLines.isEmpty()) {
            // 无歌词：只能显示封面，不可切换；尝试联网抓取
            hasLyrics = false;
            applyViewMode();
            tryFetchLyrics(s);
            return;
        }
        // 有歌词：默认显示歌词，点击可切到封面
        hasLyrics = true;
        showingLyrics = true;
        for (LrcParser.Line line : lyricsLines) {
            TextView tv = new TextView(this);
            tv.setText(line.text.isEmpty() ? " " : line.text);
            tv.setTextSize(16);
            tv.setTextColor(getResources().getColor(R.color.lyric_normal));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(8), 0, dp(8));
            // 点击任意歌词行也可切回封面
            tv.setOnClickListener(v -> toggleLyricsCover());
            llLyrics.addView(tv);
            lyricViews.add(tv);
        }
        applyViewMode();
    }

    /** 缺歌词时联网抓取（按设置的音源优先级）；成功后重绑歌词 */
    private void tryFetchLyrics(final Song s) {
        if (s == null) return;
        if (!s.online && s.path == null) return;
        final String key = s.online ? ("u:" + s.url) : s.path;
        com.chumosplayer.util.LrcFetcher.fetch(this, s, lrc -> {
            if (!bound || playService == null) return;
            Song cur = playService.currentSong();
            // 确认还在播放同一首歌
            if (cur == null || key == null) return;
            String curKey = cur.online ? ("u:" + cur.url) : cur.path;
            if (!key.equals(curKey)) return;
            s.lrc = lrc;
            boundLrc = null; // 允许重新绑定
            bindLyrics(s);
        });
    }

    /** 封面与歌词互相切换（仅当歌曲有歌词时可用） */
    private void toggleLyricsCover() {
        if (!hasLyrics) return;
        showingLyrics = !showingLyrics;
        applyViewMode();
    }

    /** 按当前状态应用封面 / 歌词的可见性 */
    private void applyViewMode() {
        if (!hasLyrics) {
            // 无歌词：始终显示封面
            scrollLyrics.setVisibility(View.GONE);
            coverContainer.setVisibility(View.VISIBLE);
            return;
        }
        if (showingLyrics) {
            scrollLyrics.setVisibility(View.VISIBLE);
            coverContainer.setVisibility(View.GONE);
        } else {
            scrollLyrics.setVisibility(View.GONE);
            coverContainer.setVisibility(View.VISIBLE);
        }
    }

    /** 按当前播放进度高亮对应歌词行并滚动到中间；带逐字时间时再逐词高亮 */
    private void updateLyricsHighlight(int curMs) {
        if (lyricsLines.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < lyricsLines.size(); i++) {
            if (lyricsLines.get(i).timeMs <= curMs) idx = i;
            else break;
        }
        if (idx == activeLyric) {
            updateWordHighlight(curMs); // 同一行内逐字推进
            return;
        }
        if (activeLyric >= 0 && activeLyric < lyricViews.size()) {
            TextView old = lyricViews.get(activeLyric);
            old.setTextColor(getResources().getColor(R.color.lyric_normal));
            old.setTextSize(16);
            // 还原成纯文本，去掉上一次的逐字 span
            String oldText = lyricsLines.get(activeLyric).text;
            old.setText(oldText.isEmpty() ? " " : oldText);
        }
        activeLyric = idx;
        activeWords = (idx >= 0 && idx < lyricsLines.size())
                ? lyricsLines.get(idx).words : java.util.Collections.<LrcParser.Word>emptyList();
        activeWord = -1;
        if (idx >= 0 && idx < lyricViews.size()) {
            TextView cur = lyricViews.get(idx);
            cur.setTextSize(18);
            if (activeWords.isEmpty()) {
                cur.setTextColor(getResources().getColor(R.color.lyric_highlight));
            } else {
                // 逐字行：整行用普通色，只让当前词高亮
                cur.setTextColor(getResources().getColor(R.color.lyric_normal));
                updateWordHighlight(curMs);
            }
            // 让高亮行中心对齐歌词区中心（行高变化时也居中，避免错位）
            int target = cur.getTop() + cur.getHeight() / 2 - scrollLyrics.getHeight() / 2;
            scrollLyrics.smoothScrollTo(0, Math.max(0, target));
        }
    }

    /** 逐字高亮：只在当前词变化时重建 span，避免每帧重排 */
    private void updateWordHighlight(int curMs) {
        if (activeWords.isEmpty()) return;
        if (activeLyric < 0 || activeLyric >= lyricViews.size()) return;
        int i = com.chumosplayer.util.LyricWord.indexAt(activeWords, curMs);
        if (i == activeWord) return;
        activeWord = i;
        TextView tv = lyricViews.get(activeLyric);
        tv.setText(com.chumosplayer.util.LyricWord.render(activeWords, curMs,
                getResources().getColor(R.color.lyric_normal),
                getResources().getColor(R.color.lyric_highlight)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(progressTick);
        if (coverSpin != null) {
            coverSpin.cancel();
            coverSpin = null;
        }
        if (bound && playService != null) {
            playService.removeListener(this);
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }
}

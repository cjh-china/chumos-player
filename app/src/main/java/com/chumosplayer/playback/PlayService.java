package com.chumosplayer.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import com.chumosplayer.model.Song;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 播放服务：MediaPlayer + 前台常驻。
 * 支持本地文件与在线 URL（网易云外链），自动切下一首。
 */
public class PlayService extends Service implements
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener {

    public class PlayBinder extends Binder {
        public PlayService getService() { return PlayService.this; }
    }

    private final IBinder binder = new PlayBinder();
    private MediaPlayer player;
    /** 音效：均衡器 / 重低音 / 环绕声（绑定到当前音频会话） */
    private android.media.audiofx.Equalizer equalizer;
    private android.media.audiofx.BassBoost bassBoost;
    private android.media.audiofx.Virtualizer virtualizer;
    /** 媒体会话：接收耳机线控按键，并提供锁屏控制 */
    private android.media.session.MediaSession mediaSession;
    private List<Song> queue = new ArrayList<>();
    private int index = -1;
    private final List<Listener> listeners = new ArrayList<>();

    /** 是否已 prepare 完成；未完成时 start()/seekTo() 会被忽略或抛 IllegalStateException */
    private boolean prepared = false;
    /** 缓冲期间收到的 seek 请求，onPrepared 后补跳，避免"拖完没反应" */
    private int pendingSeekMs = -1;
    /** 是否给播放器设置过非 1.0x 倍速（没设过就完全不碰 PlaybackParams） */
    private boolean nonDefaultSpeedApplied = false;

    // ---- 睡眠定时 ----
    /** 定时截止点（elapsedRealtime 毫秒）；0 表示未设置 */
    private long sleepDeadline = 0;
    /** 本首歌结束后暂停 */
    private boolean sleepOnSongEnd = false;
    private final android.os.Handler sleepHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleepTask = new Runnable() {
        @Override
        public void run() {
            if (sleepDeadline <= 0) return;          // 已被取消
            long remain = sleepRemainingMs();
            if (remain > 0) {                        // 系统休眠唤醒后可能提前醒来，重新对齐
                sleepHandler.postDelayed(this, remain);
                return;
            }
            fireSleep("睡眠定时到啦，已暂停播放 🌙");
        }
    };

    // ---- 供桌面小组件读取的播放快照（同进程静态字段） ----
    private static volatile Song widgetSong;
    private static volatile boolean widgetPlaying;

    /** 当前歌曲快照；无播放时为 null */
    public static Song snapshotSong() { return widgetSong; }

    /** 是否正在播放的快照 */
    public static boolean snapshotPlaying() { return widgetPlaying; }

    /** 进程内单例引用（onCreate/onDestroy 维护），供删除文件前判断"正在播的是不是它" */
    private static volatile PlayService sInstance;

    /** 若正播放指定路径的本地歌曲则停止播放；返回是否执行了停止 */
    public static boolean stopIfPlayingPath(String path) {
        PlayService p = sInstance;
        if (p == null || path == null) return false;
        try {
            Song cur = p.currentSong();
            if (cur != null && path.equals(cur.path)) {
                p.stopPlayback();
                return true;
            }
        } catch (Exception ignore) { }
        return false;
    }

    // ---- 前台媒体通知（通知栏控制 / 锁屏 / 耳机） ----
    private static final int NOTI_ID = 2001;
    private static final String CH_PLAYBACK = "playback_control";
    private static final String ACTION_N_PLAY = "com.chumosplayer.action.N_PLAY";
    private static final String ACTION_N_PAUSE = "com.chumosplayer.action.N_PAUSE";
    private static final String ACTION_N_NEXT = "com.chumosplayer.action.N_NEXT";
    private static final String ACTION_N_PREV = "com.chumosplayer.action.N_PREV";
    private static final String ACTION_N_MODE = "com.chumosplayer.action.N_MODE";
    /** 是否已进入前台服务状态 */
    private boolean inForeground = false;
    /** 播放模式：见 SettingsManager.MODE_* */
    private int playMode = com.chumosplayer.util.SettingsManager.MODE_LOOP;

    public interface Listener {
        void onPlayingChanged(Song song, boolean playing);
        void onProgress(int curMs, int totalMs);
        /** 播放出错（文件缺失、格式不支持等） */
        void onError(String message);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new MediaPlayer();
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnCompletionListener(this);
        player.setOnPreparedListener(this);
        player.setOnErrorListener(this);
        playMode = com.chumosplayer.util.SettingsManager.getPlayMode(this);
        sInstance = this;
        setupMediaSession();
    }

    /** 建立媒体会话：耳机线控（播放/暂停/上一首/下一首）与锁屏控制 */
    private void setupMediaSession() {
        try {
            mediaSession = new android.media.session.MediaSession(this, "ChumoPlayer");
            mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public void onPlay() { if (!isPlaying()) togglePlay(); }
                @Override public void onPause() { if (isPlaying()) togglePlay(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onStop() { if (isPlaying()) togglePlay(); }
                /** 耳机/锁屏/系统媒体面板发起的快进快退 */
                @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            });
            mediaSession.setActive(true);
            updatePlaybackState(false);
        } catch (Exception ignore) { }
    }

    /** 更新媒体会话的播放状态（让耳机/锁屏按键盘正确路由） */
    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        try {
            android.media.session.PlaybackState.Builder b =
                    new android.media.session.PlaybackState.Builder();
            b.setActions(android.media.session.PlaybackState.ACTION_PLAY
                    | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | android.media.session.PlaybackState.ACTION_STOP
                    | android.media.session.PlaybackState.ACTION_SEEK_TO);
            b.setState(playing ? android.media.session.PlaybackState.STATE_PLAYING
                            : android.media.session.PlaybackState.STATE_PAUSED,
                    position(), 1.0f);
            mediaSession.setPlaybackState(b.build());
            Song s = currentSong();
            if (s != null) {
                android.media.MediaMetadata.Builder m = new android.media.MediaMetadata.Builder();
                m.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, s.title);
                m.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, s.artist);
                m.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration());
                mediaSession.setMetadata(m.build());
            }
        } catch (Exception ignore) { }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** 通知栏按钮 / 系统媒体控制发来的指令 */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            try {
                if (ACTION_N_PLAY.equals(a) || ACTION_N_PAUSE.equals(a)) togglePlay();
                else if (ACTION_N_NEXT.equals(a)) next();
                else if (ACTION_N_PREV.equals(a)) prev();
                else if (ACTION_N_MODE.equals(a)) cyclePlayMode();
            } catch (Exception ignore) { }
        }
        return START_NOT_STICKY;
    }

    // ---- 公开控制 ----

    public void setQueue(List<Song> songs, int startIndex) {
        queue = songs;
        playAt(startIndex);
    }

    public void playAt(int i) {
        if (queue.isEmpty()) return;
        index = ((i % queue.size()) + queue.size()) % queue.size();
        Song s = queue.get(index);
        String src = s.online ? s.url : s.path;
        // SMB 远程音乐：交给本地代理串流（MediaPlayer 只认 http）
        if (com.chumosplayer.net.SmbStreamer.isSmb(src)) {
            try {
                src = com.chumosplayer.net.SmbStreamer.mediaUrl(this, src);
            } catch (Exception e) {
                notifyError("SMB 串流失败：" + e.getMessage());
                return;
            }
        }
        if (src == null || src.trim().isEmpty()) {
            notifyError("无效的播放地址");
            return;
        }
        // 本地文件先确认存在，避免失效的媒体库记录触发 MediaPlayer error(-38)
        if (!s.online && !new java.io.File(src).exists()) {
            notifyError("文件不存在或已被移动：" + new java.io.File(src).getName());
            return;
        }
        try {
            // reset 起播放器进入未就绪态：清掉旧的 seek 请求，未 ready 前不接受 start/seek
            prepared = false;
            pendingSeekMs = -1;
            player.reset();
            // WebDAV 等需要鉴权的地址：把 Authorization 头一并交给播放器
            java.util.Map<String, String> headers = s.online
                    ? com.chumosplayer.net.WebDavClient.headersIfWebDav(this, src) : null;
            if (headers != null) {
                // 公开 API 里带 headers 的只有 (Context, Uri, Map) 这个重载
                player.setDataSource(this, android.net.Uri.parse(src), headers);
            } else {
                player.setDataSource(src);
            }
            player.prepareAsync(); // 异步准备，在线播放不卡 UI
        } catch (Exception e) {
            prepared = false;
            notifyError("无法播放：" + e.getMessage());
        }
    }

    public void togglePlay() {
        // 还没 prepare 完成就按播放/暂停：直接忽略，避免 IllegalStateException
        if (index < 0 || !prepared) return;
        if (player.isPlaying()) player.pause();
        else player.start();
        notifyState(player.isPlaying());
    }

    /** 停止并清空当前播放（删除正在播放的文件时用） */
    public void stopPlayback() {
        try {
            if (prepared) player.stop();
        } catch (Exception ignore) { }
        prepared = false;
        pendingSeekMs = -1;
        queue = new ArrayList<>();
        index = -1;
        widgetSong = null;
        widgetPlaying = false;
        sleepHandler.removeCallbacks(sleepTask);
        sleepDeadline = 0;
        sleepOnSongEnd = false;
        notifyState(false); // 迷你条/小组件/通知都会回到"未播放"
    }

    public void next() {
        // 随机模式下手动切歌也走随机（但不会重复当前曲）
        if (playMode == com.chumosplayer.util.SettingsManager.MODE_SHUFFLE && queue.size() > 1) {
            playAt(randomOtherIndex());
            return;
        }
        playAt(index + 1);
    }

    public void prev() { playAt(index - 1); }

    // ---- 播放模式 ----

    /** 当前播放模式：列表循环 / 随机 / 单曲循环 */
    public int playMode() {
        return playMode;
    }

    /** 循环切换播放模式（顺序：列表循环 → 随机 → 单曲循环），返回新模式并持久化 */
    public int cyclePlayMode() {
        playMode = (playMode + 1) % com.chumosplayer.util.SettingsManager.MODE_COUNT;
        com.chumosplayer.util.SettingsManager.setPlayMode(this, playMode);
        notifyState(isPlaying()); // 通知界面与通知栏刷新图标
        return playMode;
    }

    /** 随机取一个「不等于当前」的下标；队列只有一首时返回当前 */
    private int randomOtherIndex() {
        int size = queue.size();
        if (size <= 1) return index;
        int r;
        do {
            r = new java.util.Random().nextInt(size);
        } while (r == index);
        return r;
    }

    // ---- 睡眠定时（运行时状态，不落盘；重启 app 后自动回到"关闭"）----

    /** 设置睡眠定时：minutes > 0 表示多少分钟后暂停，0 表示取消定时 */
    public void setSleepMinutes(int minutes) {
        sleepHandler.removeCallbacks(sleepTask);
        sleepDeadline = 0;
        if (minutes > 0) {
            sleepDeadline = android.os.SystemClock.elapsedRealtime() + minutes * 60000L;
            sleepHandler.postDelayed(sleepTask, minutes * 60000L);
        }
        notifyState(isPlaying()); // 通知界面刷新定时标签
    }

    /** 「本首歌结束」模式开关 */
    public void setSleepOnSongEnd(boolean on) {
        sleepOnSongEnd = on;
        notifyState(isPlaying());
    }

    /** 定时剩余毫秒；未设置或已到期返回 0 */
    public long sleepRemainingMs() {
        if (sleepDeadline <= 0) return 0;
        return Math.max(0, sleepDeadline - android.os.SystemClock.elapsedRealtime());
    }

    public boolean isSleepOnSongEnd() { return sleepOnSongEnd; }

    /** 定时触发：暂停并回到开头，方便醒来接着听 */
    private void fireSleep(String msg) {
        sleepHandler.removeCallbacks(sleepTask);
        sleepDeadline = 0;
        sleepOnSongEnd = false;
        if (index >= 0 && prepared) {
            try {
                if (player.isPlaying()) player.pause();
                player.seekTo(0);
            } catch (Exception ignore) { }
        }
        notifyState(false);
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignore) { }
    }

    /**
     * 跳转到指定位置。
     * 缓冲中（尚未 prepare）时先记下请求，onPrepared 后补跳——这是"拖动进度条没反应"的主因之一。
     */
    public void seekTo(int ms) {
        if (index < 0 || player == null) return;
        if (!prepared) {
            pendingSeekMs = ms;
            return;
        }
        int dur = duration();
        int target = ms < 0 ? 0 : ms;
        if (dur > 0 && target > dur) target = dur; // 越界钳制，避免 seek 报错
        try {
            player.seekTo(target);
            // 让系统媒体面板（耳机/锁屏/蓝牙）上的进度同步到新位置
            updatePlaybackState(isPlaying());
        } catch (IllegalStateException e) {
            pendingSeekMs = target; // 状态刚好在切歌，稍后补跳
        }
    }

    /** 设置播放速度（0.5x ~ 2.0x）；需 Android 6.0+，低版本忽略 */
    public void setSpeed(float speed) {
        // 默认 1.0x 且从未设置过其它倍速时，完全不碰 PlaybackParams：
        // 部分机型 setPlaybackParams 调用后会出现进度/seek 异常，能不调就不调。
        boolean isDefault = Math.abs(speed - 1.0f) < 0.001f;
        if (isDefault && !nonDefaultSpeedApplied) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.media.PlaybackParams pp = player.getPlaybackParams();
                pp.setSpeed(speed);
                player.setPlaybackParams(pp);
                if (!isDefault) nonDefaultSpeedApplied = true;
            }
        } catch (Exception ignore) { }
    }

    public boolean isPlaying() {
        try {
            return index >= 0 && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public Song currentSong() {
        return (index >= 0 && index < queue.size()) ? queue.get(index) : null;
    }

    public int currentIndex() { return index; }

    /** 当前播放队列的副本（供界面展示/跳转） */
    public java.util.List<Song> queueSnapshot() {
        return new java.util.ArrayList<>(queue);
    }

    public int position() {
        try {
            return index >= 0 ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 总时长（毫秒）。
     * 优先用播放器实测值；部分在线音源（m4s/无时长信息的 mp3）getDuration() 返回 0，
     * 此时回退到歌曲元数据里的时长，否则进度条范围会退化成默认的 0~100，导致完全无法快进。
     */
    public int duration() {
        try {
            if (index >= 0) {
                int d = player.getDuration();
                if (d > 0) return d;
            }
        } catch (Exception ignore) { }
        Song s = currentSong();
        return (s != null && s.duration > 0) ? (int) s.duration : 0;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    // ---- MediaPlayer 回调 ----

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        mp.start();
        // 应用用户设置的播放速度
        try {
            setSpeed(com.chumosplayer.util.SettingsManager.getSpeed(this));
        } catch (Exception ignore) { }
        // 记录播放历史
        try {
            com.chumosplayer.util.PlayHistory.record(this, currentSong());
        } catch (Exception ignore) { }
        setupAudioEffects();
        // 补上缓冲期间积压的快进/快退请求
        if (pendingSeekMs >= 0) {
            int target = pendingSeekMs;
            pendingSeekMs = -1;
            seekTo(target);
        }
        notifyState(true);
        notifyProgress(); // 就绪后立刻回传一次，让 UI 拿到真实时长
    }

    // ---- 音效（均衡器等）----

    /** 在当前音频会话上重建音效链，并应用已保存的偏好 */
    private void setupAudioEffects() {
        releaseAudioEffects();
        try {
            int session = player.getAudioSessionId();
            equalizer = new android.media.audiofx.Equalizer(0, session);
            bassBoost = new android.media.audiofx.BassBoost(0, session);
            virtualizer = new android.media.audiofx.Virtualizer(0, session);
            applyAudioEffects();
        } catch (Exception ignore) {
            releaseAudioEffects();
        }
    }

    /** 按已保存偏好设置音效开关与均衡器预设 */
    private void applyAudioEffects() {
        boolean eqOn = com.chumosplayer.util.SettingsManager.isEqEnabled(this);
        try {
            if (equalizer != null) {
                equalizer.setEnabled(eqOn);
                if (eqOn) {
                    short preset = (short) com.chumosplayer.util.SettingsManager.getEqPreset(this);
                    if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                        equalizer.usePreset(preset);
                    }
                }
            }
            if (bassBoost != null) {
                bassBoost.setEnabled(eqOn
                        && com.chumosplayer.util.SettingsManager.isBassBoost(this));
                if (bassBoost.getStrengthSupported()) bassBoost.setStrength((short) 500);
            }
            if (virtualizer != null) {
                virtualizer.setEnabled(eqOn
                        && com.chumosplayer.util.SettingsManager.isVirtualizer(this));
            }
        } catch (Exception ignore) { }
    }

    public void setEqEnabled(boolean enabled) {
        com.chumosplayer.util.SettingsManager.setEqEnabled(this, enabled);
        applyAudioEffects();
    }

    public void setEqPreset(int preset) {
        com.chumosplayer.util.SettingsManager.setEqPreset(this, preset);
        applyAudioEffects();
    }

    public void setBassBoost(boolean on) {
        com.chumosplayer.util.SettingsManager.setBassBoost(this, on);
        applyAudioEffects();
    }

    public void setVirtualizer(boolean on) {
        com.chumosplayer.util.SettingsManager.setVirtualizer(this, on);
        applyAudioEffects();
    }

    /** 当前均衡器预设名列表；不可用时返回空数组 */
    public String[] getEqPresetNames() {
        try {
            if (equalizer != null) {
                short n = equalizer.getNumberOfPresets();
                String[] names = new String[n];
                for (short i = 0; i < n; i++) names[i] = equalizer.getPresetName(i);
                return names;
            }
        } catch (Exception ignore) { }
        return new String[0];
    }

    private void releaseAudioEffects() {
        try { if (equalizer != null) equalizer.release(); } catch (Exception ignore) { }
        try { if (bassBoost != null) bassBoost.release(); } catch (Exception ignore) { }
        try { if (virtualizer != null) virtualizer.release(); } catch (Exception ignore) { }
        equalizer = null;
        bassBoost = null;
        virtualizer = null;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        // 睡眠定时「本首歌结束」：播完就停，不切下一首
        if (sleepOnSongEnd) {
            sleepOnSongEnd = false;
            sleepHandler.removeCallbacks(sleepTask);
            sleepDeadline = 0;
            try {
                mp.pause();
                mp.seekTo(0);   // 回到开头，醒来按播放即可继续
            } catch (Exception ignore) { }
            notifyState(false);
            try {
                android.widget.Toast.makeText(this, "本首歌播放结束，已暂停 🌙",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignore) { }
            return;
        }
        // 单曲循环：从头再播一遍（seekTo(0) 后 start，避免停在曲尾）
        if (playMode == com.chumosplayer.util.SettingsManager.MODE_SINGLE) {
            try {
                mp.seekTo(0);
                mp.start();
            } catch (Exception ignore) { }
            notifyState(true);
            return;
        }
        next(); // 列表循环顺序推进；随机模式在 next() 内部走随机
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        prepared = false;   // 进入 Error 状态，后续 seek/start 都会被忽略
        pendingSeekMs = -1;
        notifyError("播放失败（错误码 " + what + "," + extra + "）");
        return true;
    }

    private void notifyState(boolean playing) {
        Song s = currentSong();
        widgetSong = s;
        widgetPlaying = playing;
        updatePlaybackState(playing);
        for (Listener l : listeners) l.onPlayingChanged(s, playing);
        // 同步刷新桌面小组件（桌面没放组件时内部会直接返回）
        com.chumosplayer.widget.MusicWidgetProvider.updateAll(this);
        // 播放中则拉起前台通知并随状态刷新
        refreshNotification(playing);
    }

    /** 创建或刷新前台媒体通知；首次播放时才进前台，避免刚启动 app 就挂常驻通知 */
    private void refreshNotification(boolean playing) {
        try {
            Notification n = buildNotification();
            NotificationManager nm = notiManager();
            if (inForeground) {
                if (nm != null) nm.notify(NOTI_ID, n);
            } else if (playing && index >= 0) {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTI_ID, n,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTI_ID, n);
                }
                inForeground = true;
            }
        } catch (Exception ignore) { }
    }

    private NotificationManager notiManager() {
        try {
            return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /** 媒体样式通知：歌名/歌手 + 上一首/播放暂停/下一首，点正文进播放页 */
    private Notification buildNotification() {
        NotificationManager nm = notiManager();
        if (nm != null && android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_PLAYBACK, "播放控制",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Song s = currentSong();
        boolean playing = isPlaying();

        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_PLAYBACK);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(s != null && s.title != null ? s.title : "初墨播放器")
                .setContentText(s != null && s.artist != null ? s.artist : "未在播放")
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(true)
                .setContentIntent(openPlayerPending());
        try {
            if (mediaSession != null) {
                b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
            }
        } catch (Exception ignore) { }
        b.addAction(android.R.drawable.ic_media_previous, "上一首", actionPending(ACTION_N_PREV, 1));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "播放",
                actionPending(playing ? ACTION_N_PAUSE : ACTION_N_PLAY, 2));
        b.addAction(android.R.drawable.ic_media_next, "下一首", actionPending(ACTION_N_NEXT, 3));
        // 播放模式放最后，保证紧凑视图（0,1,2）仍是 上一首/播放/下一首
        b.addAction(modeIcon(playMode), modeName(playMode), actionPending(ACTION_N_MODE, 4));
        return b.build();
    }

    /** 播放模式名称（静态，供界面提示复用） */
    public static String modeName(int mode) {
        switch (mode) {
            case com.chumosplayer.util.SettingsManager.MODE_SHUFFLE:
                return "随机播放";
            case com.chumosplayer.util.SettingsManager.MODE_SINGLE:
                return "单曲循环";
            default:
                return "列表循环";
        }
    }

    /** 播放模式图标资源 id（供界面按模式换图） */
    public static int modeIcon(int mode) {
        switch (mode) {
            case com.chumosplayer.util.SettingsManager.MODE_SHUFFLE:
                return com.chumosplayer.R.drawable.ic_shuffle;
            case com.chumosplayer.util.SettingsManager.MODE_SINGLE:
                return com.chumosplayer.R.drawable.ic_repeat_one;
            default:
                return com.chumosplayer.R.drawable.ic_repeat;
        }
    }

    private PendingIntent actionPending(String action, int reqCode) {
        Intent i = new Intent(this, PlayService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, reqCode, i, flags);
    }

    private PendingIntent openPlayerPending() {
        Intent i = new Intent(this, com.chumosplayer.NowPlayingActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private void notifyError(String message) {
        for (Listener l : listeners) l.onError(message);
    }

    /** 主动向监听者回传一次进度（用于就绪后刷新真实时长） */
    private void notifyProgress() {
        int cur = position();
        int total = duration();
        for (Listener l : listeners) l.onProgress(cur, total);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        sleepHandler.removeCallbacks(sleepTask);
        if (inForeground) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignore) { }
            inForeground = false;
        }
        releaseAudioEffects();
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignore) { }
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}

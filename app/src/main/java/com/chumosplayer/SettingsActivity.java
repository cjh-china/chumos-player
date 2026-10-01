package com.chumosplayer;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chumosplayer.util.SettingsManager;

/** 设置页：深色模式、播放速度、最近添加窗口等 */
public class SettingsActivity extends AppCompatActivity {

    private static final String[] DARK_LABELS = {"跟随系统", "浅色", "深色", "纯黑 (AMOLED)"};
    private static final String[] ACCENT_LABELS = {"靛蓝（默认）", "樱粉", "薄荷", "紫罗兰", "琥珀", "翠绿"};
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
    private static final int[] RECENT_DAYS = {3, 7, 14, 30};
    private static final String[] RECENT_LABELS = {"3 天", "7 天", "14 天", "30 天"};
    /** 问候语弹出时间：值为毫秒延迟，-1 表示不弹出（与 SettingsManager.GREETING_NEVER 一致） */
    private static final int[] GREETING_DELAYS = {800, 3000, 10000, 30000, 60000, -1};
    private static final String[] GREETING_LABELS =
            {"启动后立即", "3 秒后", "10 秒后", "30 秒后", "1 分钟后", "不弹出"};

    /** 睡眠定时选项：分钟数，-1 表示"本首歌结束" */
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 60, -1};
    private static final String[] SLEEP_LABELS = {"关闭", "15 分钟", "30 分钟", "60 分钟", "本首歌结束"};

    /** 在线歌词音源优先级选项 */
    private static final String[] LYRIC_SRC_LABELS = {"网易云（默认）", "酷狗"};

    /** 首页分类标签（与 SettingsManager.TAB_KEYS 顺序一致） */
    private static final String[] TAB_LABELS = {"歌曲", "艺术家", "专辑", "文件夹", "最近"};

    private TextView tvDark, tvSpeed, tvRecent, tvEq, tvBass, tvVirtual, tvFloat, tvTagEditor;
    private TextView tvGreeting;
    private TextView tvAccent;
    private TextView tvSleep;
    private TextView tvLyricSrc;
    private TextView tvCoverFetch;
    private TextView tvScanAdd;
    private TextView tvScanList;
    private TextView tvHomeTabs;
    private TextView tvWebdav;
    private TextView tvSmb;
    private TextView tvGrantStatus;
    /** 运行时权限请求码 */
    private static final int REQ_ALL_PERMS = 100;
    private com.chumosplayer.playback.PlayService playService;
    private boolean bound = false;

    /** 睡眠定时倒计时刷新（仅在本页可见时跑） */
    private final android.os.Handler sleepUiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleepUiTick = new Runnable() {
        @Override
        public void run() {
            if (tvSleep != null) {
                String now = sleepLabel();
                if (!now.contentEquals(tvSleep.getText())) tvSleep.setText(now);
            }
            sleepUiHandler.postDelayed(this, 1000);
        }
    };

    private final android.content.ServiceConnection conn =
            new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name,
                                       android.os.IBinder service) {
            playService = ((com.chumosplayer.playback.PlayService.PlayBinder) service)
                    .getService();
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
        setContentView(R.layout.activity_settings);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.settings_toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("设置");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        tvDark = findViewById(R.id.tv_dark_value);
        tvAccent = findViewById(R.id.tv_accent_value);
        tvSpeed = findViewById(R.id.tv_speed_value);
        tvRecent = findViewById(R.id.tv_recent_value);
        tvEq = findViewById(R.id.tv_eq_value);
        tvBass = findViewById(R.id.tv_bass_value);
        tvVirtual = findViewById(R.id.tv_virtual_value);
        tvFloat = findViewById(R.id.tv_float_value);
        tvTagEditor = findViewById(R.id.tv_tag_editor_value);
        tvGreeting = findViewById(R.id.tv_greeting_value);
        tvSleep = findViewById(R.id.tv_sleep_value);
        tvLyricSrc = findViewById(R.id.tv_lyric_src_value);
        tvCoverFetch = findViewById(R.id.tv_cover_fetch_value);
        tvScanAdd = findViewById(R.id.tv_scan_add_value);
        tvScanList = findViewById(R.id.tv_scan_list_value);
        tvHomeTabs = findViewById(R.id.tv_home_tabs_value);
        tvWebdav = findViewById(R.id.tv_webdav_value);
        tvSmb = findViewById(R.id.tv_smb_value);
        tvGrantStatus = findViewById(R.id.tv_grant_status);

        refreshValues();

        findViewById(R.id.row_dark_mode).setOnClickListener(v -> pickDarkMode());
        findViewById(R.id.row_accent).setOnClickListener(v -> pickAccent());
        findViewById(R.id.row_greeting).setOnClickListener(v -> pickGreeting());
        findViewById(R.id.row_sleep).setOnClickListener(v -> pickSleep());
        findViewById(R.id.row_lyric_src).setOnClickListener(v -> pickLyricSource());
        findViewById(R.id.row_cover_fetch).setOnClickListener(v -> toggleCoverFetch());
        findViewById(R.id.row_scan_add).setOnClickListener(v -> addScanDir());
        findViewById(R.id.row_scan_list).setOnClickListener(v -> showScanDirs());
        findViewById(R.id.row_home_tabs).setOnClickListener(v -> pickHomeTabs());
        findViewById(R.id.row_webdav).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, RemoteMusicActivity.class)));
        findViewById(R.id.row_smb).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, SmbActivity.class)));
        findViewById(R.id.row_speed).setOnClickListener(v -> pickSpeed());
        findViewById(R.id.row_recent).setOnClickListener(v -> pickRecent());
        findViewById(R.id.row_eq).setOnClickListener(v -> pickEqPreset());
        findViewById(R.id.row_bass).setOnClickListener(v -> toggleBass());
        findViewById(R.id.row_virtual).setOnClickListener(v -> toggleVirtual());
        findViewById(R.id.row_float_lyric).setOnClickListener(v -> toggleFloatLyric());
        findViewById(R.id.row_tag_editor).setOnClickListener(v -> toggleTagEditor());
        findViewById(R.id.row_grant_all).setOnClickListener(v -> grantAllPermissions());
        findViewById(R.id.row_backup).setOnClickListener(v -> doBackup());
        findViewById(R.id.row_restore).setOnClickListener(v -> doRestore());

        bindService(new android.content.Intent(this,
                com.chumosplayer.playback.PlayService.class), conn,
                android.content.Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGrantStatus();
        sleepUiHandler.removeCallbacks(sleepUiTick);
        sleepUiHandler.post(sleepUiTick);
    }

    @Override
    protected void onPause() {
        sleepUiHandler.removeCallbacks(sleepUiTick);
        super.onPause();
    }

    /** 显示当前权限授予概况 */
    private void updateGrantStatus() {
        if (tvGrantStatus == null) return;
        boolean ok = true;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
            if (!android.provider.Settings.canDrawOverlays(this)) ok = false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()) ok = false;
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
        tvGrantStatus.setText(ok ? "已全部授予" : "点击申请");
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    private static final int REQ_BACKUP = 201;
    private static final int REQ_RESTORE = 202;

    /** 备份：让用户选保存位置，写入 JSON */
    private void doBackup() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(android.content.Intent.EXTRA_TITLE,
                    "chumo_backup_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                            java.util.Locale.getDefault()).format(new java.util.Date()) + ".json");
            startActivityForResult(i, REQ_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 恢复：让用户选 JSON 文件，还原设置 */
    private void doRestore() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("恢复设置")
                .setMessage("将从所选文件还原设置、播放历史、黑名单等。\n\n当前设置会被覆盖，确定继续吗？")
                .setPositiveButton("选择文件", (d, w) -> {
                    try {
                        android.content.Intent i = new android.content.Intent(
                                android.content.Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_RESTORE);
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            if (requestCode == REQ_BACKUP) {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                com.chumosplayer.util.BackupManager.backup(this, os);
                if (os != null) os.close();
                Toast.makeText(this, "备份成功", Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_RESTORE) {
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                boolean ok = com.chumosplayer.util.BackupManager.restore(this, is);
                if (is != null) is.close();
                Toast.makeText(this, ok ? "恢复成功，部分设置重启后生效" : "恢复失败：文件格式不正确",
                        Toast.LENGTH_LONG).show();
                refreshValues();
            }
        } catch (Exception e) {
            Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 一键申请所有所需权限 */
    private void grantAllPermissions() {
        StringBuilder missing = new StringBuilder();

        // 1) 运行时权限：存储读写 + 通知（Android 13+ 的通知栏播放控制）
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            java.util.List<String> req = new java.util.ArrayList<>();
            for (String p : new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    req.add(p);
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                req.add(android.Manifest.permission.POST_NOTIFICATIONS);
                missing.append("· 通知（通知栏播放控制）：未授予\n");
            }
            if (!req.isEmpty()) {
                requestPermissions(req.toArray(new String[0]), REQ_ALL_PERMS);
            } else {
                missing.append("· 存储读写 / 通知：已授予\n");
            }
        }

        // 2) 悬浮窗权限（桌面歌词）
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            missing.append("· 悬浮窗（桌面歌词）：未授予\n");
        }

        // 3) 所有文件访问（Android 11+，写 TF 卡等）
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()) {
            missing.append("· 所有文件访问（外置存储）：未授予\n");
        }

        // 逐项引导：先运行时权限弹窗，再依次跳系统设置
        if (missing.length() > 0) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("申请权限")
                    .setMessage("以下权限尚未授予：\n\n" + missing
                            + "\n将依次引导你到系统设置授予。")
                    .setPositiveButton("继续", (d, w) -> guideSystemPermissions())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            Toast.makeText(this, "所需权限均已授予 🎉", Toast.LENGTH_SHORT).show();
        }
    }

    /** 引导授予系统级权限（悬浮窗、所有文件访问） */
    private void guideSystemPermissions() {
        // 所有文件访问（Android 11+）
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()) {
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
            return;
        }
        // 悬浮窗（Android 6+）
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignore) { }
            return;
        }
        Toast.makeText(this, "权限申请完成", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ALL_PERMS) {
            // 运行时权限弹窗结束后，继续引导系统级权限
            guideSystemPermissions();
        }
    }

    /** 开关标签编辑器：禁用会弹警告，说明受影响的功能 */
    private void toggleTagEditor() {
        boolean on = SettingsManager.isTagEditorEnabled(this);
        if (on) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("禁用标签编辑器？")
                    .setMessage("标签编辑器由第三方库 jaudiotagger 提供。\n\n"
                            + "禁用后将无法编辑歌曲标签（标题 / 艺术家 / 专辑）；"
                            + "长按本地歌曲时只会显示「屏蔽所在文件夹」。\n\n"
                            + "已保存的歌曲不受影响，可随时重新启用。\n\n确定禁用吗？")
                    .setPositiveButton("禁用", (d, w) -> {
                        SettingsManager.setTagEditorEnabled(this, false);
                        refreshValues();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("启用标签编辑器")
                    .setMessage("标签编辑器可修改音频文件的标题 / 艺术家 / 专辑（直接写入文件）。\n\n"
                            + "它基于第三方库 jaudiotagger，为纯 Java 实现；"
                            + "部分音频格式可能改写失败——失败时不会损坏原文件，只会提示保存失败。\n\n确定启用吗？")
                    .setPositiveButton("启用", (d, w) -> {
                        SettingsManager.setTagEditorEnabled(this, true);
                        refreshValues();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /** 开关桌面悬浮歌词（需悬浮窗权限） */
    private void toggleFloatLyric() {
        boolean on = com.chumosplayer.util.SettingsManager.isFloatLyric(this);
        if (!on) {
            // 开启前先检查悬浮窗权限
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && !android.provider.Settings.canDrawOverlays(this)) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("桌面悬浮歌词需要在其他应用上方显示，请授予悬浮窗权限。")
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
            com.chumosplayer.util.SettingsManager.setFloatLyric(this, true);
            startService(new android.content.Intent(this,
                    com.chumosplayer.FloatingLyricsService.class));
        } else {
            com.chumosplayer.util.SettingsManager.setFloatLyric(this, false);
            stopService(new android.content.Intent(this,
                    com.chumosplayer.FloatingLyricsService.class));
        }
        refreshValues();
    }

    private void toggleBass() {
        boolean on = !SettingsManager.isBassBoost(this);
        SettingsManager.setBassBoost(this, on);
        if (bound && playService != null) playService.setBassBoost(on);
        refreshValues();
    }

    private void toggleVirtual() {
        boolean on = !SettingsManager.isVirtualizer(this);
        SettingsManager.setVirtualizer(this, on);
        if (bound && playService != null) playService.setVirtualizer(on);
        refreshValues();
    }

    /** 均衡器预设选择（含总开关） */
    private void pickEqPreset() {
        String[] presets = (bound && playService != null)
                ? playService.getEqPresetNames() : new String[0];
        if (presets.length == 0) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("均衡器")
                    .setMessage("均衡器需在开始播放后可用。\n\n开始播放一首歌后再进入此页即可调节。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        boolean enabled = SettingsManager.isEqEnabled(this);
        final String[] items = new String[presets.length + 1];
        items[0] = enabled ? "✔ 已开启均衡器" : "开启均衡器";
        System.arraycopy(presets, 0, items, 1, presets.length);
        int checked = enabled ? SettingsManager.getEqPreset(this) + 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("均衡器")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        boolean newState = !SettingsManager.isEqEnabled(this);
                        SettingsManager.setEqEnabled(this, newState);
                        if (bound && playService != null) playService.setEqEnabled(newState);
                    } else {
                        SettingsManager.setEqEnabled(this, true);
                        SettingsManager.setEqPreset(this, which - 1);
                        if (bound && playService != null) {
                            playService.setEqEnabled(true);
                            playService.setEqPreset(which - 1);
                        }
                    }
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshValues() {
        int dm = SettingsManager.getDarkMode(this);
        tvDark.setText(dm >= 0 && dm < DARK_LABELS.length ? DARK_LABELS[dm] : DARK_LABELS[0]);
        int ac = SettingsManager.getAccent(this);
        tvAccent.setText(ac >= 0 && ac < ACCENT_LABELS.length ? ACCENT_LABELS[ac] : ACCENT_LABELS[0]);
        tvSpeed.setText(speedLabel(SettingsManager.getSpeed(this)));
        tvRecent.setText(SettingsManager.getRecentDays(this) + " 天");
        tvEq.setText(SettingsManager.isEqEnabled(this) ? "已开启" : "关闭");
        tvBass.setText(SettingsManager.isBassBoost(this) ? "已开启" : "关闭");
        tvVirtual.setText(SettingsManager.isVirtualizer(this) ? "已开启" : "关闭");
        tvFloat.setText(SettingsManager.isFloatLyric(this) ? "已开启" : "关闭");
        tvTagEditor.setText(SettingsManager.isTagEditorEnabled(this) ? "已启用" : "已禁用");
        tvGreeting.setText(greetingLabel(SettingsManager.getGreetingDelayMs(this)));
        tvSleep.setText(sleepLabel());
        int ls = SettingsManager.getLyricSource(this);
        tvLyricSrc.setText(ls >= 0 && ls < LYRIC_SRC_LABELS.length
                ? LYRIC_SRC_LABELS[ls] : LYRIC_SRC_LABELS[0]);
        tvCoverFetch.setText(SettingsManager.isCoverFetch(this) ? "已开启" : "已关闭");
        int scanN = SettingsManager.getScanDirs(this).size();
        tvScanList.setText(scanN == 0 ? "未添加" : "已添加 " + scanN + " 个");
        tvHomeTabs.setText(SettingsManager.getHomeTabs(this).size() + " 项");
        tvWebdav.setText(SettingsManager.isWebDavConfigured(this) ? "已配置" : "未配置");
        tvSmb.setText(SettingsManager.isSmbConfigured(this) ? "已配置" : "未配置");
        updateGrantStatus();
    }

    private String greetingLabel(int delayMs) {
        for (int i = 0; i < GREETING_DELAYS.length; i++) {
            if (GREETING_DELAYS[i] == delayMs) return GREETING_LABELS[i];
        }
        return delayMs < 0 ? "不弹出" : (delayMs / 1000.0) + " 秒后";
    }

    private String speedLabel(float s) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - s) < 0.001f) return SPEED_LABELS[i];
        }
        return s + "x";
    }

    private void pickDarkMode() {
        int cur = SettingsManager.getDarkMode(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("深色模式")
                .setSingleChoiceItems(DARK_LABELS, cur, (d, which) -> {
                    SettingsManager.setDarkMode(this, which);
                    refreshValues();
                    d.dismiss();
                    recreate(); // 主题可能换了（深色 ↔ 纯黑），重新 inflate
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 主题色：顶栏 / 标签栏 / 高亮色，与深浅、纯黑可自由组合 */
    private void pickAccent() {
        int cur = SettingsManager.getAccent(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("主题色")
                .setMessage("影响顶栏、标签栏与强调色；与深色 / 纯黑模式可自由组合。")
                .setSingleChoiceItems(ACCENT_LABELS, cur, (d, which) -> {
                    SettingsManager.setAccent(this, which);
                    refreshValues();
                    d.dismiss();
                    recreate(); // 重新 inflate 才会套上新主题
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickSpeed() {
        float cur = SettingsManager.getSpeed(this);
        int sel = 1;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - cur) < 0.001f) sel = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(SPEED_LABELS, sel, (d, which) -> {
                    SettingsManager.setSpeed(this, SPEEDS[which]);
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickRecent() {
        int cur = SettingsManager.getRecentDays(this);
        int sel = 1;
        for (int i = 0; i < RECENT_DAYS.length; i++) {
            if (RECENT_DAYS[i] == cur) sel = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("最近添加窗口")
                .setSingleChoiceItems(RECENT_LABELS, sel, (d, which) -> {
                    SettingsManager.setRecentDays(this, RECENT_DAYS[which]);
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 问候语弹出时间：下次启动生效 */
    private void pickGreeting() {
        int cur = SettingsManager.getGreetingDelayMs(this);
        int sel = 0;
        for (int i = 0; i < GREETING_DELAYS.length; i++) {
            if (GREETING_DELAYS[i] == cur) sel = i;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("问候语弹出时间")
                .setMessage("控制每次启动时顶部问候语（欢迎语 / 时段问候）弹出的时机。"
                        + "\n暖心小提示另有节奏，不受此项影响。")
                .setSingleChoiceItems(GREETING_LABELS, sel, (d, which) -> {
                    SettingsManager.setGreetingDelayMs(this, GREETING_DELAYS[which]);
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 睡眠定时：到点自动暂停，或本首歌结束后暂停 */
    private void pickSleep() {
        if (!bound || playService == null) {
            Toast.makeText(this, "播放服务还没就绪，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        int sel = 0;
        if (playService.isSleepOnSongEnd()) {
            sel = SLEEP_LABELS.length - 1;
        } else if (playService.sleepRemainingMs() > 0) {
            long min = playService.sleepRemainingMs() / 60000L + 1; // 向上取整便于回显
            for (int i = 0; i < SLEEP_MINUTES.length; i++) {
                if (SLEEP_MINUTES[i] > 0 && Math.abs(SLEEP_MINUTES[i] - min) <= 5) sel = i;
            }
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setMessage("到点自动暂停播放；选「本首歌结束」则在当前歌曲播完后暂停。"
                        + "\n（定时为运行时状态，重启 app 后回到关闭）")
                .setSingleChoiceItems(SLEEP_LABELS, sel, (d, which) -> {
                    int v = SLEEP_MINUTES[which];
                    if (v < 0) {
                        playService.setSleepMinutes(0);
                        playService.setSleepOnSongEnd(true);
                    } else {
                        playService.setSleepOnSongEnd(false);
                        playService.setSleepMinutes(v);
                    }
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 手动扫描：选目录 → 记录并触发媒体扫描，本地音乐列表即可看到其中的歌 */
    private void addScanDir() {
        com.chumosplayer.util.DirPickerDialog.show(this,
                android.os.Environment.getExternalStorageDirectory(), dir -> {
                    if (dir == null) return;
                    com.chumosplayer.util.SettingsManager.addScanDir(this,
                            dir.getAbsolutePath());
                    com.chumosplayer.util.MusicLoader.scanDirectory(this, dir);
                    refreshValues();
                    Toast.makeText(this, "已添加并开始扫描：" + dir.getName(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    /** 查看/移除已添加的扫描目录 */
    private void showScanDirs() {
        final java.util.List<String> dirs = new java.util.ArrayList<>(
                SettingsManager.getScanDirs(this));
        if (dirs.isEmpty()) {
            Toast.makeText(this, "还没有手动添加的扫描目录", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = dirs.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("已添加的扫描目录")
                .setItems(items, (d, which) -> {
                    final String pick = items[which];
                    new android.app.AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("移除扫描目录")
                            .setMessage("移除后，该目录下未被媒体库收录的歌将不再出现在本地列表：\n\n" + pick)
                            .setPositiveButton("移除", (dd, ww) -> {
                                SettingsManager.removeScanDir(SettingsActivity.this, pick);
                                refreshValues();
                                Toast.makeText(SettingsActivity.this, "已移除",
                                        Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 开关「联网补全封面」 */
    private void toggleCoverFetch() {
        SettingsManager.setCoverFetch(this, !SettingsManager.isCoverFetch(this));
        refreshValues();
    }

    /** 首页本地音乐的分类标签：多选，至少保留一个 */
    private void pickHomeTabs() {
        final java.util.Set<String> cur = SettingsManager.getHomeTabs(this);
        final boolean[] checked = new boolean[SettingsManager.TAB_KEYS.length];
        for (int i = 0; i < checked.length; i++) {
            checked[i] = cur.contains(SettingsManager.TAB_KEYS[i]);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("本地音乐分类标签")
                .setMessage("勾选首页「本地音乐」下要显示的分类，可只留常用的几个。")
                .setMultiChoiceItems(TAB_LABELS, checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    java.util.Set<String> sel = new java.util.HashSet<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) sel.add(SettingsManager.TAB_KEYS[i]);
                    }
                    if (sel.isEmpty()) {
                        Toast.makeText(SettingsActivity.this, "至少保留一个分类",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SettingsManager.setHomeTabs(SettingsActivity.this, sel);
                    refreshValues();
                    Toast.makeText(SettingsActivity.this,
                            "已更新，重新进入本地音乐页生效", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 在线歌词音源优先级：先试所选源，取不到自动落备选源 */
    private void pickLyricSource() {
        int cur = SettingsManager.getLyricSource(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("在线歌词音源")
                .setMessage("缺歌词时按这里的优先级联网获取；一个源没有会自动尝试另一个。"
                        + "\n本地已有的 .lrc / 内嵌歌词始终优先，不受此项影响。")
                .setSingleChoiceItems(LYRIC_SRC_LABELS, cur, (d, which) -> {
                    SettingsManager.setLyricSource(this, which);
                    refreshValues();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String sleepLabel() {        if (bound && playService != null) {
            if (playService.isSleepOnSongEnd()) return "本首歌结束";
            long remain = playService.sleepRemainingMs();
            if (remain > 0) {
                long sec = remain / 1000;
                return String.format(java.util.Locale.CHINA, "剩余 %d:%02d",
                        sec / 60, sec % 60);
            }
        }
        return "关闭";
    }
}

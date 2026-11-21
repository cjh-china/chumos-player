package com.chumosplayer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.SettingsManager;
import com.chumosplayer.util.TtsLibrary;
import com.chumosplayer.util.TtsSynthesizer;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文本 → 音频：既是对内功能页，也是对外接口入口。
 *
 * 对外契约（详见 README）：
 *   action  : com.chumosplayer.action.SPEAK_TEXT
 *   extras  : com.chumosplayer.extra.TEXT    (String, 必填)
 *             com.chumosplayer.extra.TITLE   (String, 可选，作为曲目标题)
 *             com.chumosplayer.extra.AUTO_PLAY (boolean, 可选，true 表示自动生成并播放)
 * 同时接受系统 ACTION_SEND(text/plain) 与 API 23+ 的 ACTION_PROCESS_TEXT，
 * 让用户从任意 App 分享文本、或选中一段文字后直接交给初墨朗读。
 *
 * 生成的 WAV 作为一首本地曲目进入 PlayService 播放栈，因此进度、通知栏、
 * 队列、A-B 复读、倍速、收藏等既有能力全部自动可用。
 */
public class TextToAudioActivity extends AppCompatActivity
        implements TtsSynthesizer.Callback {

    // ---- 对外接口常量 ----
    public static final String ACTION_SPEAK_TEXT = "com.chumosplayer.action.SPEAK_TEXT";
    public static final String EXTRA_TEXT = "com.chumosplayer.extra.TEXT";
    public static final String EXTRA_TITLE = "com.chumosplayer.extra.TITLE";
    public static final String EXTRA_AUTO_PLAY = "com.chumosplayer.extra.AUTO_PLAY";

    /** 用字面量而非 Intent 常量，避免低版本设备上的 NewApi 检查 */
    private static final String ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
    private static final String EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT";

    private EditText etText;
    private TextView tvCount, tvRate, tvPitch, tvStatus;
    private TextView btnGenerate, btnSettings, btnClearAll;
    private SeekBar seekRate, seekPitch;
    private ProgressBar progress;
    private LinearLayout boxStatus, llHistory;

    private TtsSynthesizer synth;
    private boolean generating;
    private boolean autoGenerate;
    private boolean playWhenDone;
    private String pendingText;
    private String incomingTitle;
    private float rate = 1.0f;
    private float pitch = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SettingsManager.applyDarkMode(SettingsManager.getDarkMode(this));
        SettingsManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_to_audio);

        Toolbar toolbar = findViewById(R.id.tts_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etText = findViewById(R.id.et_tts_text);
        tvCount = findViewById(R.id.tv_tts_count);
        tvRate = findViewById(R.id.tv_tts_rate);
        tvPitch = findViewById(R.id.tv_tts_pitch);
        tvStatus = findViewById(R.id.tv_tts_status);
        btnGenerate = findViewById(R.id.btn_tts_generate);
        btnSettings = findViewById(R.id.btn_tts_settings);
        btnClearAll = findViewById(R.id.btn_tts_clear_all);
        seekRate = findViewById(R.id.seek_tts_rate);
        seekPitch = findViewById(R.id.seek_tts_pitch);
        progress = findViewById(R.id.pb_tts);
        boxStatus = findViewById(R.id.box_tts_status);
        llHistory = findViewById(R.id.ll_tts_history);

        rate = SettingsManager.getTtsRate(this);
        pitch = SettingsManager.getTtsPitch(this);
        setupSeeks();
        setupInput();

        btnGenerate.setOnClickListener(v -> startGenerate(true));
        findViewById(R.id.btn_tts_clear).setOnClickListener(v -> {
            etText.setText("");
            etText.requestFocus();
        });
        btnSettings.setOnClickListener(v -> openTtsSettings());
        btnClearAll.setOnClickListener(v -> confirmClearAll());

        synth = new TtsSynthesizer(this, this);
        synth.init();
        renderHistory();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        autoGenerate = false;
        incomingTitle = null;
        handleIncoming(intent);
    }

    /** 解析三种来源：显式 API、系统分享、选中文本处理 */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String text = null;
        try {
            if (ACTION_SPEAK_TEXT.equals(action)) {
                text = intent.getStringExtra(EXTRA_TEXT);
                incomingTitle = intent.getStringExtra(EXTRA_TITLE);
                autoGenerate = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false);
            } else if (Intent.ACTION_SEND.equals(action)) {
                text = intent.getStringExtra(Intent.EXTRA_TEXT);
                incomingTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            } else if (ACTION_PROCESS_TEXT.equals(action)) {
                CharSequence cs = intent.getCharSequenceExtra(EXTRA_PROCESS_TEXT);
                text = cs == null ? null : cs.toString();
            }
        } catch (Exception e) {
            text = null;
        }
        final String safe = TtsSynthesizer.normalize(text);
        if (safe.isEmpty()) return;
        etText.setText(safe);
        etText.setSelection(safe.length());
        Toast.makeText(this, R.string.tts_received, Toast.LENGTH_SHORT).show();
        if (autoGenerate) {
            btnGenerate.post(() -> startGenerate(true));
        }
    }

    private void setupInput() {
        updateCount();
        etText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateCount(); }
        });
    }

    private void updateCount() {
        int n = etText.getText() == null ? 0 : etText.getText().length();
        tvCount.setText(n + " / " + TtsSynthesizer.MAX_CHARS);
        tvCount.setTextColor(n > TtsSynthesizer.MAX_CHARS
                ? accent() : getResources().getColor(R.color.text_secondary));
    }

    private void setupSeeks() {
        seekRate.setProgress(Math.round((rate - 0.5f) / 1.5f * 100));
        seekPitch.setProgress(Math.round((pitch - 0.5f) / 1.5f * 100));
        updateParamLabels();
        seekRate.setOnSeekBarChangeListener(paramListener(true));
        seekPitch.setOnSeekBarChangeListener(paramListener(false));
    }

    private SeekBar.OnSeekBarChangeListener paramListener(boolean isRate) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = 0.5f + p * 1.5f / 100f;
                if (isRate) {
                    rate = v;
                    SettingsManager.setTtsRate(TextToAudioActivity.this, v);
                } else {
                    pitch = v;
                    SettingsManager.setTtsPitch(TextToAudioActivity.this, v);
                }
                updateParamLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        };
    }

    private void updateParamLabels() {
        tvRate.setText(String.format(Locale.CHINA, "%.1fx", rate));
        tvPitch.setText(String.format(Locale.CHINA, "%.1fx", pitch));
    }

    /** 生成音频；引擎未就绪时由 TtsSynthesizer 自动排队 */
    private void startGenerate(boolean play) {
        if (generating) return;
        final String text = TtsSynthesizer.normalize(
                etText.getText() == null ? "" : etText.getText().toString());
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.tts_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        int raw = etText.getText().length();
        if (raw > TtsSynthesizer.MAX_CHARS) {
            Toast.makeText(this,
                    getString(R.string.tts_truncated, TtsSynthesizer.MAX_CHARS),
                    Toast.LENGTH_SHORT).show();
        }
        pendingText = text;
        playWhenDone = play;
        btnSettings.setVisibility(View.GONE);
        setGenerating(true);
        synth.synthesize(text, rate, pitch, TtsLibrary.newFile(this));
    }

    private void setGenerating(boolean on) {
        generating = on;
        btnGenerate.setEnabled(!on);
        btnGenerate.setAlpha(on ? 0.55f : 1f);
        btnGenerate.setText(on ? R.string.tts_generating : R.string.tts_generate);
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) boxStatus.setVisibility(View.VISIBLE);
    }

    // ---- TtsSynthesizer.Callback ----

    @Override
    public void onStatus(String message) {
        tvStatus.setText(message);
    }

    @Override
    public void onSuccess(File file) {
        setGenerating(false);
        TtsLibrary.Entry e = new TtsLibrary.Entry();
        e.title = TtsLibrary.makeTitle(pendingText, incomingTitle);
        e.text = pendingText;
        e.path = file.getAbsolutePath();
        e.time = System.currentTimeMillis();
        e.chars = pendingText == null ? 0 : pendingText.length();
        TtsLibrary.add(this, e);
        renderHistory();
        tvStatus.setText(getString(R.string.tts_done,
                com.chumosplayer.MainActivity.formatMs((int) TtsLibrary
                        .toSong(this, e).duration)));
        if (playWhenDone) play(TtsLibrary.toSong(this, e), true);
        autoGenerate = false;
        incomingTitle = null;
    }

    @Override
    public void onFailure(String message) {
        setGenerating(false);
        boxStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(message);
        btnSettings.setVisibility(View.VISIBLE);
    }

    /** 播放服务已就绪就直接入队；未就绪交给 MainActivity 在 bind 完成后播放 */
    private void play(Song s, boolean openNowPlaying) {
        if (PlayService.isReady()) {
            PlayService.playQueueGlobal(
                    new ArrayList<>(Collections.singletonList(s)), 0);
            if (openNowPlaying) startActivity(new Intent(this, NowPlayingActivity.class));
        } else {
            MainActivity.queueTtsPlay(s);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        }
    }

    private void enqueue(Song s, boolean next) {
        if (!PlayService.isReady()) {
            Toast.makeText(this, R.string.tts_service_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        if (next) PlayService.playNextGlobal(s);
        else PlayService.enqueueLastGlobal(s);
        Toast.makeText(this, next ? R.string.tts_added_next : R.string.tts_added_last,
                Toast.LENGTH_SHORT).show();
    }

    // ---- 生成记录 ----

    private void renderHistory() {
        llHistory.removeAllViews();
        final List<TtsLibrary.Entry> items = TtsLibrary.getAll(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.tts_empty_list);
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(16), 0, dp(16));
            llHistory.addView(empty);
            btnClearAll.setVisibility(View.GONE);
            return;
        }
        btnClearAll.setVisibility(View.VISIBLE);
        for (TtsLibrary.Entry e : items) llHistory.addView(buildRow(e));
    }

    private View buildRow(final TtsLibrary.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(2), dp(6));
        row.setMinimumHeight(dp(56));
        row.setBackgroundResource(R.drawable.bg_selectable);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_tts);
        icon.setColorFilter(accent());
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.setMargins(dp(4), 0, dp(12), 0);
        icon.setLayoutParams(iconLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        title.setText(e.title == null ? getString(R.string.tts_default_title) : e.title);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        TextView sub = new TextView(this);
        String stamp = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                .format(new Date(e.time));
        sub.setText(e.chars + " " + getString(R.string.tts_unit_chars) + " · " + stamp);
        sub.setTextSize(11);
        sub.setTextColor(getResources().getColor(R.color.text_secondary));
        text.addView(title);
        text.addView(sub);

        ImageButton play = iconButton(R.drawable.ic_play, R.string.cd_play);
        ImageButton more = iconButton(R.drawable.ic_more, R.string.cd_more);
        View.OnClickListener start = v -> play(TtsLibrary.toSong(this, e), true);
        row.setOnClickListener(start);
        play.setOnClickListener(start);
        more.setOnClickListener(v -> showEntryMenu(e));

        row.addView(icon);
        row.addView(text);
        row.addView(play);
        row.addView(more);
        return row;
    }

    private ImageButton iconButton(int iconRes, int cdRes) {
        ImageButton b = new ImageButton(this);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        b.setBackgroundResource(R.drawable.bg_selectable);
        b.setImageResource(iconRes);
        b.setColorFilter(getResources().getColor(R.color.text_secondary));
        b.setContentDescription(getString(cdRes));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return b;
    }

    private void showEntryMenu(final TtsLibrary.Entry e) {
        final String[] items = {
                getString(R.string.tts_menu_play),
                getString(R.string.tts_menu_next),
                getString(R.string.tts_menu_queue),
                getString(R.string.tts_menu_copy),
                getString(R.string.tts_menu_delete)
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(items, (d, which) -> {
                    Song s = TtsLibrary.toSong(this, e);
                    switch (which) {
                        case 0: play(s, true); break;
                        case 1: enqueue(s, true); break;
                        case 2: enqueue(s, false); break;
                        case 3: copyText(e); break;
                        default: confirmDelete(e); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyText(TtsLibrary.Entry e) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && e.text != null) {
            cm.setPrimaryClip(ClipData.newPlainText(e.title, e.text));
            Toast.makeText(this, R.string.tts_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除是不可逆操作：先确认，并顺手停掉正在播放的这条 */
    private void confirmDelete(final TtsLibrary.Entry e) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.tts_delete_title)
                .setMessage(getString(R.string.tts_delete_msg, e.title))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    PlayService.stopIfPlayingPath(e.path);
                    TtsLibrary.remove(this, e.path);
                    renderHistory();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClearAll() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.tts_clear_all)
                .setMessage(R.string.tts_clear_all_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    TtsLibrary.clear(this);
                    renderHistory();
                    Toast.makeText(this, R.string.tts_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e1) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignore) {
                Toast.makeText(this, R.string.tts_settings_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int accent() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.appAccent, tv, true);
        return tv.data;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (synth != null) synth.shutdown();
        super.onDestroy();
    }
}

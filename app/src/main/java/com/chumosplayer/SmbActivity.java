package com.chumosplayer;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.chumosplayer.model.Song;
import com.chumosplayer.net.SmbClient;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SMB 远程音乐浏览页（APlayer 的 feature_smb 对应能力）：
 * 列目录 → 点文件夹进入、点音频入队播放；播放时由 SmbStreamer 起本地代理串流。
 * 复用 WebDAV 页的布局与菜单，只是数据源换成 SmbClient。
 */
public class SmbActivity extends AppCompatActivity {

    private String currentPath = "/";
    private final List<SmbClient.Entry> dirs = new ArrayList<>();
    private final List<SmbClient.Entry> audios = new ArrayList<>();
    private final List<Song> audioSongs = new ArrayList<>();

    private androidx.appcompat.widget.Toolbar toolbar;
    private TextView status;
    private LinearLayout listContainer;

    private PlayService playService;
    private boolean bound = false;

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
        SettingsManager.applyTheme(this); // 纯黑/主题色
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);

        toolbar = findViewById(R.id.remote_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("SMB 远程音乐");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> back());
        status = findViewById(R.id.remote_status);
        listContainer = findViewById(R.id.remote_list);

        toolbar.inflateMenu(R.menu.remote_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_wd_refresh) {
                load(currentPath);
                return true;
            }
            if (id == R.id.action_wd_config) {
                showConfig();
                return true;
            }
            return false;
        });

        bindService(new Intent(this, PlayService.class), conn,
                android.content.Context.BIND_AUTO_CREATE);

        if (!SettingsManager.isSmbConfigured(this)) {
            showStatus("还没有配置 SMB 服务器，点右上角「服务器设置」。");
            showConfig();
        } else {
            load("/");
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (!"/".equals(currentPath)) up();
        else finish();
    }

    private void up() {
        String p = SmbClient_targetNormalize(currentPath);
        if ("/".equals(p)) return;
        int i = p.lastIndexOf('/');
        load(i <= 0 ? "/" : p.substring(0, i));
    }

    private static String SmbClient_targetNormalize(String p) {
        String s = p == null || p.isEmpty() ? "/" : p;
        if (!s.startsWith("/")) s = "/" + s;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private SmbClient.Target target() {
        return new SmbClient.Target(
                SettingsManager.getSmbHost(this),
                SettingsManager.getSmbPort(this),
                SettingsManager.getSmbShare(this),
                SettingsManager.getSmbRoot(this),
                SettingsManager.getSmbUser(this),
                SettingsManager.getSmbPass(this),
                "");
    }

    // ---- 加载与渲染 ----

    private void load(final String path) {
        showStatus("正在加载 " + path + " …");
        listContainer.removeAllViews();
        final SmbClient.Target t = target();
        new Thread(() -> {
            try {
                final List<SmbClient.Entry> entries = SmbClient.list(t, path);
                runOnUiThread(() -> showEntries(path, entries));
            } catch (final Exception e) {
                runOnUiThread(() -> showStatus("加载失败：" + e.getMessage()));
            }
        }, "smb-list").start();
    }

    private void showEntries(String path, List<SmbClient.Entry> entries) {
        currentPath = SmbClient_targetNormalize(path);
        dirs.clear();
        audios.clear();
        audioSongs.clear();
        for (SmbClient.Entry e : entries) {
            if (e.isDir) {
                dirs.add(e);
            } else if (isAudioName(e.name)) {
                audios.add(e);
                audioSongs.add(toSong(e));
            }
        }
        Comparator<SmbClient.Entry> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        Collections.sort(dirs, byName);
        Collections.sort(audios, byName);

        listContainer.removeAllViews();
        showStatus(dirs.size() + " 个文件夹 · " + audios.size() + " 首音频");

        if (!"/".equals(currentPath)) {
            addRow("‹ 返回上级", true, v -> up());
        }
        for (SmbClient.Entry d : dirs) {
            addRow(d.name + "/", true, v -> load(d.path));
        }
        for (int i = 0; i < audios.size(); i++) {
            final int idx = i;
            addRow(audios.get(i).name, false, v -> play(idx));
        }
    }

    private static boolean isAudioName(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".ape") || n.endsWith(".wma");
    }

    /** 相对 root 的路径 → 自包含 smb:// 地址（含 root 前缀，便于存进歌单） */
    private String smbUrlFor(String relPath) {
        String root = SettingsManager.getSmbRoot(this);
        String p = relPath == null ? "" : relPath;
        while (p.startsWith("/")) p = p.substring(1);
        String full = (root == null || root.isEmpty()) ? p
                : (p.isEmpty() ? root : root + "/" + p);
        return SmbClient.buildUrl(SettingsManager.getSmbHost(this),
                SettingsManager.getSmbPort(this), SettingsManager.getSmbShare(this), full);
    }

    private Song toSong(SmbClient.Entry e) {
        Song s = new Song();
        s.online = true;
        s.url = smbUrlFor(e.path);
        String n = e.name;
        int dot = n.lastIndexOf('.');
        s.title = dot > 0 ? n.substring(0, dot) : n;
        s.artist = null;   // 歌词/封面检索只用歌名
        String parent = SmbClient_targetNormalize(currentPath);
        int slash = parent.lastIndexOf('/');
        s.album = parent.substring(slash + 1);
        s.duration = 0;    // 播放器实测兜底
        return s;
    }

    private void play(int index) {
        if (!bound || playService == null || index < 0 || index >= audioSongs.size()) {
            Toast.makeText(this, "播放服务还没就绪，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        playService.setQueue(new ArrayList<>(audioSongs), index);
        Toast.makeText(this, "正在播放：" + audioSongs.get(index).title, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    private void showStatus(String msg) {
        status.setText(msg);
    }

    private void addRow(String title, boolean dim, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getResources().getColor(dim
                ? com.chumosplayer.R.color.text_secondary
                : com.chumosplayer.R.color.text_primary));
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        int pad = dp(15);
        tv.setPadding(pad, pad, pad, pad);
        applyRowBackground(tv);
        tv.setOnClickListener(click);
        listContainer.addView(tv);
    }

    private void applyRowBackground(View v) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    // ---- 服务器配置 ----

    private void showConfig() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(box);

        final EditText etHost = field("主机，如 192.168.1.10 或 nas.local",
                SettingsManager.getSmbHost(this),
                InputType.TYPE_CLASS_TEXT);
        final EditText etPort = field("端口（默认 445）",
                String.valueOf(SettingsManager.getSmbPort(this)),
                InputType.TYPE_CLASS_NUMBER);
        final EditText etShare = field("共享名，如 media",
                SettingsManager.getSmbShare(this),
                InputType.TYPE_CLASS_TEXT);
        final EditText etRoot = field("根路径（可空，如 Music）",
                SettingsManager.getSmbRoot(this),
                InputType.TYPE_CLASS_TEXT);
        final EditText etUser = field("账号（匿名留空）",
                SettingsManager.getSmbUser(this),
                InputType.TYPE_CLASS_TEXT);
        final EditText etPass = field("密码（匿名留空）",
                SettingsManager.getSmbPass(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etHost);
        box.addView(etPort);
        box.addView(etShare);
        box.addView(etRoot);
        box.addView(etUser);
        box.addView(etPass);

        new AlertDialog.Builder(this)
                .setTitle("SMB 服务器")
                .setMessage("支持 Windows 共享、群晖、QNAP 等 SMB2/3 服务；\n"
                        + "播放时走本地代理串流，不会把整个文件下下来。")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    int port = 445;
                    try {
                        port = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (Exception ignore) { }
                    SettingsManager.setSmb(this,
                            etHost.getText().toString(),
                            port,
                            etShare.getText().toString(),
                            etRoot.getText().toString(),
                            etUser.getText().toString(),
                            etPass.getText().toString());
                    if (SettingsManager.isSmbConfigured(this)) {
                        load("/");
                    } else {
                        showStatus("请至少填写主机与共享名");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(String hint, String value, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setText(value == null ? "" : value);
        et.setInputType(inputType);
        return et;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

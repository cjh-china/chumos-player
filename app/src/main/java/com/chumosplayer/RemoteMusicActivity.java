package com.chumosplayer;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
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
import com.chumosplayer.net.WebDavClient;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.SettingsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * WebDAV 远程音乐浏览页（APlayer 的 WebDAV 串流）：
 * 列目录 → 点文件夹进入、点音频即整目录入队串流播放。
 * Authorization 头由 PlayService 在播放时按设置自动附加，歌单/历史同样适用。
 */
public class RemoteMusicActivity extends AppCompatActivity {

    private String currentPath = "/";
    private final List<WebDavClient.Entry> dirs = new ArrayList<>();
    private final List<WebDavClient.Entry> audios = new ArrayList<>();
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
            getSupportActionBar().setTitle("WebDAV 远程音乐");
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

        if (!SettingsManager.isWebDavConfigured(this)) {
            showStatus("还没有配置 WebDAV 服务器，点右上角「服务器设置」。");
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
        String p = WebDavClient.normalize(currentPath);
        if ("/".equals(p)) return;
        int i = p.lastIndexOf('/');
        load(i <= 0 ? "/" : p.substring(0, i));
    }

    // ---- 加载与渲染 ----

    private void load(final String path) {
        showStatus("正在加载 " + path + " …");
        listContainer.removeAllViews();
        new Thread(() -> {
            try {
                final List<WebDavClient.Entry> entries =
                        WebDavClient.list(RemoteMusicActivity.this, path);
                runOnUiThread(() -> showEntries(path, entries));
            } catch (final Exception e) {
                runOnUiThread(() -> showStatus("加载失败：" + e.getMessage()));
            }
        }, "webdav-list").start();
    }

    private void showEntries(String path, List<WebDavClient.Entry> entries) {
        currentPath = WebDavClient.normalize(path);
        dirs.clear();
        audios.clear();
        audioSongs.clear();
        for (WebDavClient.Entry e : entries) {
            if (e.isDir) dirs.add(e);
            else if (WebDavClient.isAudio(e)) {
                audios.add(e);
                audioSongs.add(toSong(e));
            }
        }
        Comparator<WebDavClient.Entry> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        Collections.sort(dirs, byName);
        Collections.sort(audios, byName);

        listContainer.removeAllViews();
        showStatus(dirs.size() + " 个文件夹 · " + audios.size() + " 首音频");

        if (!"/".equals(currentPath)) {
            addRow("‹ 返回上级", null, v -> up(), true);
        }
        if (dirs.isEmpty() && audios.isEmpty()) {
            if ("/".equals(currentPath)) {
                showStatus("这个服务器根目录下没有可识别的文件夹或音频");
            }
            return;
        }
        for (WebDavClient.Entry d : dirs) {
            addRow(d.name + "/", null, v -> load(d.path), true);
        }
        for (int i = 0; i < audios.size(); i++) {
            final int idx = i;
            addRow(audios.get(i).name, null, v -> play(idx), false);
        }
    }

    private Song toSong(WebDavClient.Entry e) {
        Song s = new Song();
        s.online = true;
        s.url = WebDavClient.resolve(SettingsManager.getWebDavUrl(this), e.path);
        String n = e.name;
        int dot = n.lastIndexOf('.');
        s.title = dot > 0 ? n.substring(0, dot) : n;
        s.artist = null;                 // 歌词/封面检索只用歌名，别让 "WebDAV" 污染关键词
        String parent = WebDavClient.normalize(currentPath);
        int slash = parent.lastIndexOf('/');
        s.album = parent.substring(slash + 1);
        s.duration = 0;                  // 远程无时长元数据 → 播放器实测兜底
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

    /** 追加一行（带按压态）；isDir 决定是否用次要色 */
    private void addRow(String title, String sub, View.OnClickListener click, boolean dim) {
        TextView tv = new TextView(this);
        tv.setText(sub == null || sub.isEmpty() ? title : title + "  ·  " + sub);
        tv.setTextColor(getResources().getColor(dim
                ? com.chumosplayer.R.color.text_secondary
                : com.chumosplayer.R.color.text_primary));
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("服务器地址，如 https://example.com/dav/");
        etUrl.setSingleLine(true);
        etUrl.setText(SettingsManager.getWebDavUrl(this));
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        final EditText etUser = new EditText(this);
        etUser.setHint("账号（匿名服务器留空）");
        etUser.setSingleLine(true);
        etUser.setText(SettingsManager.getWebDavUser(this));

        final EditText etPass = new EditText(this);
        etPass.setHint("密码（匿名服务器留空）");
        etPass.setSingleLine(true);
        etPass.setText(SettingsManager.getWebDavPass(this));
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        box.addView(etUrl);
        box.addView(etUser);
        box.addView(etPass);

        new AlertDialog.Builder(this)
                .setTitle("WebDAV 服务器")
                .setMessage("支持坚果云 / Nextcloud / 群晖等 WebDAV 服务；\n"
                        + "地址需以 http(s):// 开头。保存后即可浏览并串流播放。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    SettingsManager.setWebDav(this,
                            etUrl.getText().toString(),
                            etUser.getText().toString(),
                            etPass.getText().toString());
                    if (SettingsManager.isWebDavConfigured(this)) {
                        load("/");
                    } else {
                        showStatus("请填写形如 https://example.com/dav/ 的服务器地址");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

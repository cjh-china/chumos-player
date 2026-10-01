package com.chumosplayer;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;
import com.chumosplayer.util.LrcParser;

import java.util.ArrayList;
import java.util.List;

/** 桌面悬浮歌词：在其他应用上方浮动显示当前歌词行，可拖动、可点击关闭 */
public class FloatingLyricsService extends Service {

    private WindowManager wm;
    private TextView lyricView;
    private WindowManager.LayoutParams params;

    private PlayService playService;
    private boolean bound = false;

    private final List<LrcParser.Line> lines = new ArrayList<>();
    private String boundSongKey;
    private int activeLine = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLyric();
            // 逐字歌词行用更细的刷新粒度，普通行维持 500ms
            handler.postDelayed(this, wordMode ? 200 : 500);
        }
    };

    /** 当前是否处于逐字歌词行（决定刷新粒度） */
    private boolean wordMode = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playService = ((PlayService.PlayBinder) service).getService();
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            playService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createOverlay();
        bindService(new Intent(this, PlayService.class), conn, Context.BIND_AUTO_CREATE);
        handler.post(tick);
    }

    private void createOverlay() {
        lyricView = new TextView(this);
        lyricView.setText("♪ 初墨播放器");
        lyricView.setTextColor(0xFFFFFFFF);
        lyricView.setTextSize(16);
        lyricView.setMaxLines(2);
        lyricView.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        lyricView.setPadding(pad, pad / 2, pad, pad / 2);
        lyricView.setBackgroundColor(0x99000000);

        int type = android.os.Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM;
        params.y = 200;

        // 拖动移动位置；长按关闭（单击不做事，避免误触消失）
        lyricView.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startY;
            final Runnable closeTask = () -> {
                try { stopSelf(); } catch (Exception ignore) { }
            };
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); startY = params.y;
                        handler.postDelayed(closeTask, 800); // 长按 0.8 秒关闭
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawY() - downY) > 10) {
                            handler.removeCallbacks(closeTask); // 拖动则取消长按
                        }
                        params.y = startY + (int) (downY - e.getRawY());
                        try { wm.updateViewLayout(lyricView, params); } catch (Exception ignore) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(closeTask);
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(lyricView, params);
        } catch (Exception ignore) { }
    }

    /** 每 500ms 同步一次歌词与高亮行 */
    private void updateLyric() {
        wordMode = false;
        if (!bound || playService == null || lyricView == null) return;
        Song s = playService.currentSong();
        if (s == null) {
            lyricView.setText("♪ 初墨播放器");
            return;
        }
        String key = s.title + "|" + s.artist;
        if (!key.equals(boundSongKey)) {
            boundSongKey = key;
            activeLine = -1;
            lines.clear();
            String lrc = (s.lrc != null && !s.lrc.isEmpty())
                    ? s.lrc : com.chumosplayer.util.LocalLrcLoader.load(s);
            if (lrc != null && !lrc.isEmpty()) lines.addAll(LrcParser.parse(lrc));
        }
        if (lines.isEmpty()) {
            lyricView.setText("♪ " + s.title);
            return;
        }
        int cur = playService.position();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= cur) idx = i;
            else break;
        }
        if (idx >= 0 && idx < lines.size()) {
            LrcParser.Line line = lines.get(idx);
            if (line.hasWords()) {
                // 逐字歌词：当前词高亮，其余半透明
                wordMode = true;
                lyricView.setText(com.chumosplayer.util.LyricWord.render(
                        line.words, cur, 0xB3FFFFFF, 0xFFFFFFFF));
            } else {
                String text = line.text;
                lyricView.setText(text.isEmpty() ? "♪" : text);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
        if (lyricView != null && wm != null) {
            try { wm.removeView(lyricView); } catch (Exception ignore) { }
            lyricView = null;
        }
        super.onDestroy();
    }
}

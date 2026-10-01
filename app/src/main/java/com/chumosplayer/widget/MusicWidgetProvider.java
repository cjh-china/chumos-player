package com.chumosplayer.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import com.chumosplayer.NowPlayingActivity;
import com.chumosplayer.R;
import com.chumosplayer.model.Song;
import com.chumosplayer.playback.PlayService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面音乐小组件：封面 + 歌名/歌手 + 上一首/播放暂停/下一首，点正文打开播放页。
 *
 * 设计要点：
 * - 播放状态通过 {@link PlayService} 的同进程静态快照读取，不需要额外 AIDL；
 * - 按钮点击走广播 → 进程内 bindService 复用同一条连接（保持服务存活，不 unbind）；
 * - 封面在工作线程取（本地取内嵌图，在线取 pic 直链），结果做小容量缓存。
 */
public class MusicWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_PREV = "com.chumosplayer.widget.PREV";
    public static final String ACTION_PLAY = "com.chumosplayer.widget.PLAY";
    public static final String ACTION_NEXT = "com.chumosplayer.widget.NEXT";

    /** 封面缓存：key -> bitmap，最多留 6 张，避免反复读盘/下载 */
    private static final Map<String, Bitmap> COVER_CACHE = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 6;
        }
    };

    /** 进程级服务引用：widget 按钮点击用，绑定后不释放，顺带保证播放服务不被回收 */
    private static PlayService sService;
    private static boolean sBound;
    private static String sPendingAction;
    private static final ServiceConnection sConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sService = ((PlayService.PlayBinder) service).getService();
            sBound = true;
            String act = sPendingAction;
            sPendingAction = null;
            runAction(act);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sService = null;
            sBound = false;
        }
    };

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_PREV.equals(action) || ACTION_PLAY.equals(action) || ACTION_NEXT.equals(action)) {
            callService(ctx, action);
            return;
        }
        super.onReceive(ctx, intent); // UPDATE / ENABLE 等，转到 onUpdate
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        paint(context, false);
    }

    /** 播放状态变化时由 PlayService 调用（同进程直调，不用广播） */
    public static void updateAll(Context ctx) {
        try {
            paint(ctx, false);
        } catch (Exception ignore) { }
    }

    /** 把当前状态画到所有小组件上；coverReady=false 且本地无缓存时，再起线程补封面 */
    private static void paint(Context ctx, boolean coverReady) {
        AppWidgetManager am = AppWidgetManager.getInstance(ctx);
        if (am == null) return;
        int[] ids = am.getAppWidgetIds(new ComponentName(ctx, MusicWidgetProvider.class));
        if (ids == null || ids.length == 0) return; // 桌面没放小组件，直接省事

        final Song song = PlayService.snapshotSong();
        boolean playing = PlayService.snapshotPlaying();
        String key = coverKey(song);
        Bitmap cover = key == null ? null : synchronizedCache(() -> COVER_CACHE.get(key));

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_music);
        if (song != null) {
            v.setTextViewText(R.id.widget_title,
                    song.title == null || song.title.isEmpty() ? "未知歌曲" : song.title);
            v.setTextViewText(R.id.widget_artist,
                    song.artist == null || song.artist.isEmpty() ? " " : song.artist);
        } else {
            v.setTextViewText(R.id.widget_title, "还没有在播放");
            v.setTextViewText(R.id.widget_artist, "点开播放器挑一首吧");
        }
        v.setImageViewResource(R.id.widget_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (cover != null) {
            v.setImageViewBitmap(R.id.widget_cover, cover);
        } else {
            v.setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher);
        }

        v.setOnClickPendingIntent(R.id.widget_root, openPlayerPending(ctx));
        v.setOnClickPendingIntent(R.id.widget_prev, commandPending(ctx, ACTION_PREV, 1));
        v.setOnClickPendingIntent(R.id.widget_play, commandPending(ctx, ACTION_PLAY, 2));
        v.setOnClickPendingIntent(R.id.widget_next, commandPending(ctx, ACTION_NEXT, 3));
        am.updateAppWidget(ids, v);

        // 没有封面就去取一张，回来再画一次（只取一次，取不到就停在默认图标）
        if (cover == null && song != null && !coverReady) {
            final String k = key;
            new Thread(() -> {
                Bitmap bmp = loadCover(song);
                if (bmp != null) {
                    synchronizedCache(() -> COVER_CACHE.put(k, bmp));
                    paint(ctx, true);
                }
            }).start();
        }
    }

    // ---- 点击处理 ----

    private static void callService(Context ctx, String action) {
        if (sBound && sService != null) {
            runAction(action);
            return;
        }
        sPendingAction = action;
        try {
            ctx.bindService(new Intent(ctx, PlayService.class), sConn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            sPendingAction = null;
        }
    }

    private static void runAction(String action) {
        if (action == null || sService == null) return;
        try {
            if (ACTION_PREV.equals(action)) sService.prev();
            else if (ACTION_NEXT.equals(action)) sService.next();
            else sService.togglePlay();
        } catch (Exception ignore) { }
    }

    private static PendingIntent commandPending(Context ctx, String action, int reqCode) {
        Intent i = new Intent(ctx, MusicWidgetProvider.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, reqCode, i, flags);
    }

    private static PendingIntent openPlayerPending(Context ctx) {
        Intent i = new Intent(ctx, NowPlayingActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, 0, i, flags);
    }

    // ---- 封面 ----

    private static String coverKey(Song s) {
        if (s == null) return null;
        String base = s.path != null ? s.path
                : (s.url == null ? "" : s.url) + "|" + (s.title == null ? "" : s.title);
        return base.isEmpty() ? null : base;
    }

    private static synchronized <T> T synchronizedCache(java.util.concurrent.Callable<T> task) {
        try {
            return task.call();
        } catch (Exception e) {
            return null;
        }
    }

    /** 取封面：本地读内嵌专辑图，在线拉 pic 直链；失败返回 null（用默认图标） */
    private static Bitmap loadCover(Song s) {
        try {
            if (!s.online && s.path != null) {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(s.path);
                    byte[] art = mmr.getEmbeddedPicture();
                    if (art != null) return decodeScaled(art, 192);
                } finally {
                    try { mmr.release(); } catch (Exception ignore) { }
                }
                return null;
            }
            if (s.online && s.pic != null && s.pic.startsWith("http")) {
                return fetchScaled(s.pic, 192);
            }
        } catch (Exception ignore) { }
        return null;
    }

    private static Bitmap fetchScaled(String url, int target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 2 * 1024 * 1024) { // 最多 2MB
                bos.write(buf, 0, n);
                total += n;
            }
            in.close();
            return decodeScaled(bos.toByteArray(), target);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 按目标边长做 inSampleSize 解码，避免桌面进程 OOM */
    private static Bitmap decodeScaled(byte[] data, int target) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            int sample = 1;
            while (opt.outWidth / (sample * 2) >= target
                    && opt.outHeight / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options dec = new BitmapFactory.Options();
            dec.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, dec);
        } catch (Exception e) {
            return null;
        }
    }
}

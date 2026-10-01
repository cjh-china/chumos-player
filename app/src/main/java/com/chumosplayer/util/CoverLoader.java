package com.chumosplayer.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.ImageView;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.WeakHashMap;

/**
 * 轻量封面加载器（不引入图片库，兼容 minSdk 21）：
 * - 本地歌曲：优先 MediaStore 专辑封面 URI，回退读取音频文件内嵌封面
 * - 在线歌曲：加载 pic 网络地址
 * 结果按 path/url 缓存，避免列表滚动重复解码。
 */
public class CoverLoader {

    private static final WeakHashMap<String, Bitmap> CACHE = new WeakHashMap<>();

    /** 封面加载结果回调：hasRealCover 为 false 表示回退到了占位图 */
    public interface CoverCallback {
        void onCoverLoaded(boolean hasRealCover);
    }

    public static void load(ImageView view, Song song) {
        load(view, song, null);
    }

    public static void load(ImageView view, Song song, CoverCallback cb) {
        final String key = song.online ? song.pic : song.path;
        Bitmap cached = key != null ? CACHE.get(key) : null;
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setTag(null);
            if (cb != null) cb.onCoverLoaded(true);
            return;
        }
        view.setTag(key);
        view.setImageResource(R.drawable.ic_launcher); // 占位图（黑胶）
        new LoadTask(view, key, song, cb).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class LoadTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<ImageView> ref;
        private final String key;
        private final Song song;
        private final CoverCallback cb;

        LoadTask(ImageView view, String key, Song song, CoverCallback cb) {
            this.ref = new WeakReference<>(view);
            this.key = key;
            this.song = song;
            this.cb = cb;
        }

        @Override
        protected Bitmap doInBackground(Void... v) {
            try {
                Bitmap bmp = song.online ? loadNetwork(song.pic) : loadLocal(song);
                // 本地歌一张封面都没有：按「歌手 歌名」联网补全（可在设置里关）
                if (bmp == null && !song.online) bmp = fetchFromWeb(song);
                if (bmp != null && key != null) CACHE.put(key, bmp);
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bmp) {
            ImageView iv = ref.get();
            if (iv == null) return;
            // 视图已被复用绑定到别的歌曲，丢弃结果
            if (key != null && !key.equals(iv.getTag())) return;
            if (bmp != null) iv.setImageBitmap(bmp);
            if (cb != null) cb.onCoverLoaded(bmp != null);
        }

        private Bitmap loadNetwork(String url) throws Exception {
            if (url == null || url.isEmpty()) return null;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                conn.disconnect();
            }
        }

        private Bitmap loadLocal(Song s) {
            Context ctx = iv();
            // 0) 下载时保存的同目录同名封面（优先，root 设备自定义目录也能读到）
            Bitmap side = loadSidecar(s);
            if (side != null) return side;
            // 1) MediaStore 专辑封面
            if (ctx != null) {
                try (InputStream in = ctx.getContentResolver()
                        .openInputStream(MusicLoader.albumArtUri(s.id))) {
                    if (in != null) {
                        Bitmap b = BitmapFactory.decodeStream(in);
                        if (b != null) return b;
                    }
                } catch (Exception ignore) { }
            }
            // 2) 音频文件内嵌封面
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(s.path);
                byte[] art = mmr.getEmbeddedPicture();
                if (art != null) return BitmapFactory.decodeByteArray(art, 0, art.length);
            } catch (Exception ignore) {
            } finally {
                try { mmr.release(); } catch (Exception ignore) {}
            }
            return null;
        }

        /** 读取与音频同目录、同名的封面图（.jpg/.jpeg/.png/.webp） */
        private Bitmap loadSidecar(Song s) {
            if (s.path == null) return null;
            java.io.File audio = new java.io.File(s.path);
            java.io.File dir = audio.getParentFile();
            if (dir == null) return null;
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            for (String ext : new String[]{".jpg", ".jpeg", ".png", ".webp"}) {
                java.io.File f = new java.io.File(dir, base + ext);
                if (f.exists()) {
                    Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (b != null) return b;
                }
            }
            return null;
        }

        /**
         * 联网补全封面（APlayer 的 auto download artwork）：
         * 按「歌手 歌名」搜图 → 下载 → 顺手写成同目录同名 .jpg，
         * 下次直接走 sidecar，不再重复联网。
         */
        private Bitmap fetchFromWeb(Song s) {
            try {
                Context ctx = iv();
                if (ctx == null || !SettingsManager.isCoverFetch(ctx)) return null;
                String artist = (s.artist == null || s.artist.isEmpty()
                        || "未知歌手".equals(s.artist)) ? "" : s.artist.trim();
                String kw = (artist.isEmpty() ? s.title : artist + " " + s.title);
                if (kw == null || kw.trim().isEmpty()) return null;
                java.util.List<Song> res = com.chumosplayer.net.MyFreeMP3Client
                        .search(kw.trim(), 1);
                String pic = null;
                if (res != null) {
                    for (Song r : res) {
                        if (r != null && r.pic != null && !r.pic.isEmpty()) {
                            pic = r.pic;
                            break;
                        }
                    }
                }
                if (pic == null) return null;
                Bitmap bmp = loadNetwork(pic);
                if (bmp != null) saveSidecar(s, bmp);
                return bmp;
            } catch (Exception e) {
                return null;
            }
        }

        /** 把补全到的封面写成同目录同名 .jpg（先写临时文件再改名，避免半截图） */
        private void saveSidecar(Song s, Bitmap bmp) {
            if (s.path == null || bmp == null) return;
            try {
                java.io.File audio = new java.io.File(s.path);
                java.io.File dir = audio.getParentFile();
                if (dir == null || !dir.canWrite()) return;
                String name = audio.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                java.io.File out = new java.io.File(dir, base + ".jpg");
                if (out.exists()) return;
                java.io.File tmp = new java.io.File(dir, base + ".jpg.tmp");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                boolean ok;
                try {
                    ok = bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                } finally {
                    fos.close();
                }
                if (ok) {
                    if (!tmp.renameTo(out)) tmp.delete();
                } else {
                    tmp.delete();
                }
            } catch (Exception ignore) { }
        }

        private Context iv() {
            ImageView v = ref.get();
            return v != null ? v.getContext() : null;
        }
    }
}

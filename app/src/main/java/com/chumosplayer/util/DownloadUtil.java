package com.chumosplayer.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;

import androidx.core.app.NotificationCompat;

import com.chumosplayer.R;
import com.chumosplayer.net.MyFreeMP3Client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 联网下载音乐到指定目录（默认 Music/MyFreeMP3），带进度通知。
 *
 * 保存结构：每首歌单独一个以歌名命名的文件夹，音频、封面、歌词同放一处——
 *   Music/MyFreeMP3/周杰伦 - 晴天/
 *       ├── 周杰伦 - 晴天.mp3
 *       ├── 周杰伦 - 晴天.jpg   (封面)
 *       └── 周杰伦 - 晴天.lrc   (歌词)
 *
 * 安全策略（面向 root 设备）：
 * - 原子写入：先写随机命名的临时文件，校验后 rename 成正式文件，避免半截文件
 * - 防路径穿越：净化文件名并校验目标位于保存目录内
 * - 防符号链接：目标若为软链直接拒绝，避免写入被重定向到别处
 * - 重名跳过：目标已存在时不覆盖，抛 FileExistsException 由上层提示
 * - 附属文件（歌词 / 封面）与音频同名，写入失败不影响音频本身
 */
public class DownloadUtil {

    private static final String CHANNEL_ID = "download";
    public static final int NOTI_ID = 1001;
    /** B站 m4s 流开头可能带的哨兵字节数（8 个 0x00），最多剥这么多 */
    private static final int LEADING_ZERO_SKIP = 8;
    /** 压缩目标码率（bps），对语音/ASMR 足够，体积约为原高码率的 1/3~1/2 */
    public static final int COMPRESS_BITRATE = 64000;
    /** 保存目录的偏好键（空 = 默认 Music/MyFreeMP3） */
    public static final String PREFS_DOWNLOAD = "download_prefs";
    public static final String KEY_SAVE_DIR = "save_dir";

    /** 禁止作为保存目录的系统 root 路径（含其子目录） */
    private static final String[] FORBIDDEN_ROOTS = {
            "/", "/system", "/data", "/proc", "/sys", "/dev", "/vendor",
            "/root", "/etc", "/bin", "/sbin", "/lib", "/lib64", "/boot",
            "/init", "/product", "/odm", "/acct", "/config"
    };

    /** 目标文件已存在（重名跳过） */
    public static class FileExistsException extends IOException {
        public final File file;

        FileExistsException(File f) {
            super("文件已存在，已跳过：" + f.getName());
            this.file = f;
        }
    }

    /** 获取当前保存目录（用户自定义或默认 Music/MyFreeMP3） */
    public static File saveDir(Context ctx) {
        String custom = ctx.getSharedPreferences(PREFS_DOWNLOAD, Context.MODE_PRIVATE)
                .getString(KEY_SAVE_DIR, null);
        if (custom != null && !custom.trim().isEmpty()) {
            return new File(custom.trim());
        }
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "MyFreeMP3");
    }

    /** 设置自定义保存目录（空串恢复默认） */
    public static void setSaveDir(Context ctx, String dir) {
        ctx.getSharedPreferences(PREFS_DOWNLOAD, Context.MODE_PRIVATE)
                .edit().putString(KEY_SAVE_DIR, dir == null ? "" : dir.trim()).apply();
    }

    /** 路径是否位于系统 root 目录（禁止保存 / 浏览进入） */
    public static boolean isForbiddenDir(File dir) {
        String p;
        try {
            p = dir.getCanonicalPath();
        } catch (IOException e) {
            return true;
        }
        if ("/".equals(p)) return true;
        for (String f : FORBIDDEN_ROOTS) {
            if ("/".equals(f)) continue;
            if (p.equals(f) || p.startsWith(f + "/")) return true;
        }
        return false;
    }

    /** 同步下载（仅音频），需在子线程调用。返回保存路径 */
    public static String download(Context ctx, String url, String filename,
                                  ProgressListener listener) throws Exception {
        return download(ctx, url, filename, null, null, listener);
    }

    /** 同步下载音频 + 歌词，需在子线程调用。返回音频保存路径 */
    public static String download(Context ctx, String url, String filename, String lrc,
                                  ProgressListener listener) throws Exception {
        return download(ctx, url, filename, lrc, null, listener);
    }

    /** 同步下载音频 + 歌词 + 封面，需在子线程调用。返回音频保存路径 */
    public static String download(Context ctx, String url, String filename, String lrc,
                                  String picUrl, ProgressListener listener) throws Exception {
        return download(ctx, url, filename, lrc, picUrl, false, listener);
    }

    /** 同步下载（可压缩）+ 歌词 + 封面，需在子线程调用。返回音频保存路径 */
    public static String download(Context ctx, String url, String filename, String lrc,
                                  String picUrl, boolean compress,
                                  ProgressListener listener) throws Exception {
        return download(ctx, url, filename, lrc, picUrl, compress, COMPRESS_BITRATE, listener);
    }

    /** 同步下载（可压缩，可指定码率）+ 歌词 + 封面，需在子线程调用。返回音频保存路径 */
    public static String download(Context ctx, String url, String filename, String lrc,
                                  String picUrl, boolean compress, int bitrate,
                                  ProgressListener listener) throws Exception {
        File root = prepareDir(ctx);
        String safe = sanitize(filename);
        String ext = compress ? ".m4a" : ".mp3";
        String base = safe.replaceAll("\\.(mp3|m4a|flac|aac|wav|ogg)$", "");
        // 每首歌一个以歌名命名的独立文件夹，音频 / 封面 / 歌词都放在里面
        File dir = songDir(root, base);
        File out = new File(dir, base + ext);
        ensureInside(dir, out);

        if (out.exists()) throw new FileExistsException(out);
        if (isSymlink(out)) throw new IOException("目标为符号链接，已拒绝：" + out.getName());

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "下载",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification noti = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setOngoing(true).build();
        nm.notify(NOTI_ID, noti);

        // 原子写入音频：先写随机临时文件，校验后提交
        File tmp = File.createTempFile(out.getName() + ".", ".part", dir);
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            ProgressOutputStream pos = new ProgressOutputStream(fos, listener);
            try {
                MyFreeMP3Client.download(url, pos);
            } finally {
                fos.close();
            }
            fixLeadingZeros(tmp);
            if (compress) {
                // 转码为低码率 AAC；失败则保留原文件（不压缩）
                File m4a = new File(dir, out.getName() + "." + System.nanoTime() + ".tmp");
                try {
                    AudioTranscoder.transcodeToAac(tmp, m4a, bitrate);
                    commit(m4a, out);
                    if (!tmp.delete()) tmp.deleteOnExit();
                } catch (Exception ex) {
                    if (!m4a.delete()) m4a.deleteOnExit();
                    commit(tmp, out); // 转码失败，退回未压缩
                }
            } else {
                commit(tmp, out);
            }
        } catch (Exception e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e;
        }

        // 附属文件：歌词 + 封面，失败不影响音频
        if (lrc != null && !lrc.trim().isEmpty()) {
            try {
                writeSidecar(out, ".lrc", lrc.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignore) { }
        }
        if (picUrl != null && !picUrl.isEmpty()) {
            try {
                downloadCover(picUrl, out);
            } catch (Exception ignore) { }
        }

        // 通知媒体库扫描，让新下载的歌曲能出现在本地列表中
        try {
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{out.getAbsolutePath()}, null, null);
        } catch (Exception ignore) { }

        Notification done = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(out.getName())
                .setAutoCancel(true).build();
        nm.notify(NOTI_ID + (int) (out.hashCode() % 1000), done);
        return out.getAbsolutePath();
    }

    /** 校验并创建保存目录 */
    private static File prepareDir(Context ctx) throws IOException {
        File dir = saveDir(ctx);
        if (isForbiddenDir(dir)) {
            throw new IOException("不允许保存到系统目录：" + dir.getAbsolutePath());
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new IOException("不是有效目录：" + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 创建（或复用）以歌名命名的歌曲目录，并校验它位于保存根目录内。
     * 同名路径若是个文件而非目录，直接拒绝，不覆盖用户已有的东西。
     */
    private static File songDir(File root, String base) throws IOException {
        File dir = new File(root, base);
        ensureInside(root, dir);
        if (isSymlink(dir)) {
            throw new IOException("歌曲目录为符号链接，已拒绝：" + base);
        }
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IOException("存在同名文件，无法创建歌曲目录：" + base);
            }
            return dir;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建歌曲目录：" + dir.getAbsolutePath());
        }
        return dir;
    }

    /** 净化文件名：去掉分隔符、非法字符与 ".."，避免路径穿越 */
    static String sanitize(String name) {
        if (name == null) return "audio";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replace("..", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "audio" : s;
    }

    /** 确保目标位于保存目录内 */
    private static void ensureInside(File dir, File out) throws IOException {
        String dp = dir.getCanonicalPath();
        String op = out.getCanonicalPath();
        if (!op.startsWith(dp + File.separator)) {
            throw new IOException("非法保存路径：" + out.getName());
        }
    }

    /** 是否符号链接（root 环境下的软链可能把写入引到别处） */
    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalFile().equals(f.getAbsoluteFile());
        } catch (IOException e) {
            return true;
        }
    }

    /** 把临时文件原子提交为正式文件 */
    private static void commit(File tmp, File target) throws IOException {
        if (isSymlink(target)) {
            throw new IOException("目标为符号链接，已拒绝：" + target.getName());
        }
        if (tmp.renameTo(target)) return;
        copyFile(tmp, target);
        if (!tmp.delete()) tmp.deleteOnExit();
    }

    /** 原子写入与音频同名的附属文件（已存在或为软链则跳过） */
    private static void writeSidecar(File audio, String ext, byte[] data) throws IOException {
        File target = sidecarFile(audio, ext);
        if (target.exists() || isSymlink(target)) return;
        File tmp = File.createTempFile(target.getName() + ".", ".part", audio.getParentFile());
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            commit(tmp, target);
        } catch (Exception e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** 下载封面图，存为与音频同名的图片 */
    private static void downloadCover(String picUrl, File audio) throws IOException {
        File target = sidecarFile(audio, picExt(picUrl));
        if (target.exists() || isSymlink(target)) return;
        File tmp = File.createTempFile(target.getName() + ".", ".part", audio.getParentFile());
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                MyFreeMP3Client.download(picUrl, fos);
            } finally {
                fos.close();
            }
            if (tmp.length() == 0) {
                if (!tmp.delete()) tmp.deleteOnExit();
                return;
            }
            commit(tmp, target);
        } catch (Exception e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** 与音频同目录、同名的附属文件 */
    private static File sidecarFile(File audio, String ext) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(audio.getParentFile(), base + ext);
    }

    /** 由 URL 推断图片扩展名（默认 .jpg） */
    static String picExt(String url) {
        if (url == null) return ".jpg";
        String lower = url.toLowerCase();
        for (String e : new String[]{".jpg", ".jpeg", ".png", ".webp", ".gif"}) {
            if (lower.contains(e)) return e;
        }
        return ".jpg";
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    /**
     * 下载完成后检测文件头：正常音频容器以已知签名开头（ftyp/fLaC/ID3/OggS/EBML/RIFF）；
     * 若开头被 0x00 填充（B站部分流的哨兵字节）则就地剥离前导零修复。
     * 只有检测到异常头时才触发，普通文件一个字节都不动。
     */
    static void fixLeadingZeros(File f) throws java.io.IOException {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
        try {
            long len = raf.length();
            if (len < 12) return;
            byte[] head = new byte[12];
            raf.readFully(head);
            if (looksLikeValidContainer(head)) return; // 正常文件，不动
            // 统计开头连续 0x00 数量
            int zeros = 0;
            while (zeros < head.length && head[zeros] == 0x00) zeros++;
            if (zeros == 0 || zeros > LEADING_ZERO_SKIP) return; // 无填充或异常填充，不处理
            // 剥离 zeros 个字节：把后续内容前移并截断
            long newLen = len - zeros;
            byte[] buf = new byte[8192];
            long readPos = zeros, writePos = 0;
            int n;
            while (readPos < len && (n = raf.read(buf, 0,
                    (int) Math.min(buf.length, len - readPos))) > 0) {
                raf.seek(writePos);
                raf.write(buf, 0, n);
                writePos += n;
                readPos += n;
                raf.seek(readPos);
            }
            raf.setLength(newLen);
        } finally {
            raf.close();
        }
    }

    /** 常见音频/视频容器签名（考虑前导零偏移后位置可能后移，只查前 16 字节内） */
    private static boolean looksLikeValidContainer(byte[] h) {
        String s = new String(h, 4, Math.min(8, h.length - 4));
        if (s.startsWith("ftyp")) return true;                    // MP4/M4A
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return true;
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;   // MP3 (ID3)
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFB) return true; // MP3 裸帧
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return true;
        if (h[0] == 0x1A && h[1] == 0x45 && h[2] == (byte) 0xDF && h[3] == (byte) 0xA3) return true; // EBML/Matroska
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return true; // WAV/FLV-RIFF
        if (h[0] == 'F' && h[1] == 'L' && h[2] == 'V') return true;
        // 前几个字节是零但零后面有签名的情况不算有效头（交给剥离逻辑处理）
        return false;
    }

    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /** 包装输出流，统计已下载字节数 */
    static class ProgressOutputStream extends OutputStream {
        private final OutputStream out;
        private final ProgressListener listener;
        long downloaded = 0;

        ProgressOutputStream(OutputStream out, ProgressListener l) {
            this.out = out;
            this.listener = l;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            out.write(b);
            downloaded++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            downloaded += len;
            if (listener != null) listener.onProgress(downloaded, -1);
        }

        @Override
        public void close() throws java.io.IOException { out.close(); }
        @Override
        public void flush() throws java.io.IOException { out.flush(); }
        @Override
        public void write(byte[] b) throws java.io.IOException { write(b, 0, b.length); }
    }
}

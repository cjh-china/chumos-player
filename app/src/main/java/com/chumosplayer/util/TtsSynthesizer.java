package com.chumosplayer.util;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.Locale;

/**
 * 文本 → WAV 音频：封装系统 TextToSpeech 引擎。
 *
 * 设计要点：
 * 1. 使用 API 21+ 的 synthesizeToFile(CharSequence, Bundle, File, String)，
 *    framework 内部通过 ParcelFileDescriptor 把可写句柄交给 TTS 引擎进程，
 *    因此输出文件可以直接放在本应用私有目录，无需任何存储权限。
 * 2. TTS 初始化是异步的：初始化完成前的合成请求先排队，ready 后自动执行。
 * 3. UtteranceProgressListener 回调运行在 Binder 线程，统一切回主线程再更新 UI。
 * 4. 引擎只接受有限长度的文本，统一在 normalize() 里截断，避免外部 Intent 传入超长内容。
 */
public final class TtsSynthesizer {

    /** 单次合成的最大字符数（主流 TTS 引擎上限约 4000） */
    public static final int MAX_CHARS = 4000;

    public interface Callback {
        void onStatus(String message);
        void onSuccess(File file);
        void onFailure(String message);
    }

    private final Context app;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ready;
    private boolean destroyed;
    private File currentFile;
    private String currentId;
    private Runnable queued;

    public TtsSynthesizer(Context context, Callback callback) {
        this.app = context.getApplicationContext();
        this.callback = callback;
    }

    public void init() {
        emitStatus("正在初始化语音引擎…");
        try {
            tts = new TextToSpeech(app, status -> main.post(() -> onInit(status)));
        } catch (Exception e) {
            emitFailure("无法启动语音引擎：" + e.getMessage());
        }
    }

    private void onInit(int status) {
        if (destroyed) return;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            emitFailure("系统语音引擎不可用，请安装或启用「文字转语音（TTS）」服务");
            return;
        }
        final boolean chinese = setLanguage(Locale.CHINA);
        if (!chinese) {
            if (!setLanguage(Locale.getDefault())) setLanguage(Locale.US);
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override public void onDone(String utteranceId) {
                finish(true, utteranceId, 0);
            }

            @Override public void onError(String utteranceId, int errorCode) {
                finish(false, utteranceId, errorCode);
            }

            /** 旧签名仍是抽象方法，必须覆写；统一转交给带错误码的新签名 */
            @Override @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                finish(false, utteranceId, -1);
            }
        });
        ready = true;
        if (!chinese) emitStatus("当前引擎缺少中文语音包，已回退到系统默认语言");
        Runnable q = queued;
        queued = null;
        if (q != null) q.run();
    }

    private boolean setLanguage(Locale locale) {
        try {
            int r = tts.setLanguage(locale);
            return r != TextToSpeech.LANG_MISSING_DATA
                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isReady() { return ready; }

    /** 生成音频文件；引擎尚未就绪时自动排队 */
    public void synthesize(String text, float rate, float pitch, File out) {
        final String safe = normalize(text);
        if (safe.isEmpty()) {
            emitFailure("文本内容为空，先输入或分享一段文字");
            return;
        }
        if (destroyed) return;
        if (!ready) {
            queued = () -> doSynthesize(safe, rate, pitch, out);
            return;
        }
        doSynthesize(safe, rate, pitch, out);
    }

    /** 清洗外部传入的文本：不间断空格、首尾空白、超长截断 */
    public static String normalize(String text) {
        if (text == null) return "";
        String t = text.replace('\u00A0', ' ').trim();
        if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS);
        return t;
    }

    private void doSynthesize(String text, float rate, float pitch, File out) {
        if (destroyed || tts == null) return;
        try {
            tts.setSpeechRate(Math.max(0.5f, Math.min(2.0f, rate)));
            tts.setPitch(Math.max(0.5f, Math.min(2.0f, pitch)));
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (out.exists() && !out.delete()) {
                emitFailure("无法写入音频文件，请检查存储空间");
                return;
            }
            currentFile = out;
            currentId = "tts_" + System.currentTimeMillis();
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentId);
            emitStatus("正在生成音频…");
            int result = tts.synthesizeToFile(text, params, out, currentId);
            if (result != TextToSpeech.SUCCESS) {
                currentFile = null;
                currentId = null;
                emitFailure("语音引擎拒绝了合成请求，请检查 TTS 引擎设置");
            }
        } catch (Exception e) {
            currentFile = null;
            currentId = null;
            emitFailure("生成失败：" + e.getMessage());
        }
    }

    private void finish(boolean ok, String id, int code) {
        main.post(() -> {
            if (destroyed || currentId == null || !currentId.equals(id)) return;
            File f = currentFile;
            currentFile = null;
            currentId = null;
            // 只有 44 字节 WAV 头说明引擎写出了空文件，视为失败
            if (!ok || f == null || !f.exists() || f.length() < 128) {
                emitFailure("音频生成失败"
                        + (ok ? "（引擎写出了空文件）" : "（引擎错误 " + code + "）")
                        + "，请确认已安装语音数据");
                return;
            }
            callback.onSuccess(f);
        });
    }

    public void shutdown() {
        destroyed = true;
        queued = null;
        ready = false;
        currentFile = null;
        currentId = null;
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignore) { }
            tts = null;
        }
    }

    private void emitStatus(String s) { post(() -> callback.onStatus(s)); }

    private void emitFailure(String s) { post(() -> callback.onFailure(s)); }

    private void post(Runnable r) {
        if (Looper.myLooper() == main.getLooper()) r.run();
        else main.post(r);
    }
}

package com.chumosplayer.net;

import com.chumosplayer.model.Song;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bilibili 备用音源客户端（myfreemp3 无法搜索或结果为空时自动切换）。
 *
 * 流程：
 * 1. 搜索：GET https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=xxx
 *    返回 result[] 视频（bvid / title / author）
 * 2. 取流：GET https://api.bilibili.com/x/player/playurl?bvid=xxx&cid=xxx&fnval=16&qn=64
 *    返回 dash.audio[] 流地址（m4s 音频），直接可播放/下载
 */
public class BilibiliClient {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String SEARCH_API =
            "https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=";
    private static final String PLAY_API =
            "https://api.bilibili.com/x/player/playurl?fnval=16&qn=64&bvid=";

    /**
     * B 站风控用的"设备凭证"。没有 buvid3 cookie 时，接口会间歇性返回
     * HTTP 412 或 code -412/-352，表现为"有时候搜不到"。首次使用时生成，
     * 被风控后重置换一个新的再试。
     */
    private static volatile String buvid3;

    private static String buvid3() {
        String v = buvid3;
        if (v == null) {
            synchronized (BilibiliClient.class) {
                if (buvid3 == null) {
                    buvid3 = java.util.UUID.randomUUID().toString().toUpperCase()
                            .replace("-", "") + "infoc";
                }
                v = buvid3;
            }
        }
        return v;
    }

    private static void resetBuvid3() {
        synchronized (BilibiliClient.class) { buvid3 = null; }
    }

    /** 搜索（同步，需在子线程调用） */
    public static List<Song> search(String keyword) throws IOException {
        String url = SEARCH_API + URLEncoder.encode(keyword, "UTF-8");
        JsonObject root = getJson(url);
        List<Song> list = new ArrayList<>();
        if (root == null || root.get("code") == null || root.get("code").getAsInt() != 0) {
            String msg = (root != null && root.has("message")
                    && root.get("message").isJsonPrimitive())
                    ? root.get("message").getAsString() : "返回异常";
            throw new IOException("B站搜索接口返回异常：" + msg);
        }
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || !data.has("result")) return list;
        JsonArray arr = data.getAsJsonArray("result");
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Song s = new Song();
            s.online = true;
            s.title = cleanTitle(optStr(o, "title"));
            s.artist = optStr(o, "author");
            s.album = "bilibili";
            s.lrc = ""; // B站音源无歌词
            String bvid = optStr(o, "bvid");
            if (bvid.isEmpty() || s.title == null) continue;
            // 播放地址延迟到实际播放/下载时再解析（需要 cid）
            s.pic = optStr(o, "pic");
            s.url = "bilibili://" + bvid; // 占位标记，PlayService/下载前需解析
            s.id = 0;
            // B站搜索结果里的 duration 形如 "3:45"；播放器取不到时长时进度条靠它定标尺
            try {
                s.duration = MyFreeMP3Client.toDurationMs(optStr(o, "duration"));
            } catch (Exception ignore) { }
            list.add(s);
        }
        return list;
    }

    /** 解析 bvid -> 直链（dash 音频 m4s），播放或下载前调用 */
    public static String resolveStreamUrl(String bvid) throws IOException {
        // 1. 由 bvid 取 cid
        JsonObject view = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        if (view == null || !view.has("code") || view.get("code").getAsInt() != 0) {
            throw new IOException("获取视频信息失败：" + bizMessage(view, "未知错误"));
        }
        long cid = view.getAsJsonObject("data").get("cid").getAsLong();
        // 2. 取 dash 音频流
        JsonObject play = getJson(PLAY_API + bvid + "&cid=" + cid);
        if (play == null || !play.has("code") || play.get("code").getAsInt() != 0) {
            throw new IOException("获取播放地址失败：" + bizMessage(play, "未知错误"));
        }
        JsonObject d = play.has("data") && play.get("data").isJsonObject()
                ? play.getAsJsonObject("data") : null;
        if (d == null) throw new IOException("获取播放地址失败：无 data 字段");
        if (d.has("dash")) {
            JsonObject dash = d.getAsJsonObject("dash");
            JsonArray audios = dash.getAsJsonArray("audio");
            if (audios != null && audios.size() > 0) {
                JsonObject a = audios.get(0).getAsJsonObject();
                // 不同接口版本字段名可能是 baseUrl / base_url
                String u = a.has("baseUrl") ? a.get("baseUrl").getAsString()
                        : (a.has("base_url") ? a.get("base_url").getAsString() : "");
                if (!u.isEmpty()) return u;
            }
        }
        // 回退 durl（flv/mp4 直链）
        if (d.has("durl")) {
            JsonArray durl = d.getAsJsonArray("durl");
            if (durl.size() > 0) {
                return durl.get(0).getAsJsonObject().get("url").getAsString();
            }
        }
        throw new IOException("无可用音频流");
    }

    /** Song.url 为 bilibili://bvid 占位时解析为真实直链 */
    public static String ensureResolved(Song s) throws IOException {
        if (s.url != null && s.url.startsWith("bilibili://")) {
            if (s.sourceUrl == null) s.sourceUrl = s.url; // 留着占位，存歌单时用它
            String bvid = s.url.substring("bilibili://".length());
            s.url = resolveStreamUrl(bvid);
        }
        return s.url;
    }

    /** 下载（跟随 302），复用 MyFreeMP3Client 的实现逻辑 */
    public static long download(String url, OutputStream out) throws IOException {
        return MyFreeMP3Client.download(url, out);
    }

    /** 去掉标题里的高亮标签和 B 站常见后缀 */
    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("<[^>]+>", "").trim();
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 风控相关的 HTTP 状态码 / 业务码：换 buvid3 后重试一次 */
    private static boolean isRiskCode(int v) {
        return v == 412 || v == 403 || v == 509
                || v == -412 || v == -352 || v == -504 || v == -509;
    }

    /** 取业务响应里的 message 字段，便于定位失败原因 */
    private static String bizMessage(JsonObject o, String fallback) {
        if (o != null && o.has("message") && o.get("message").isJsonPrimitive()) {
            String m = o.get("message").getAsString();
            if (!m.isEmpty()) return m;
        }
        return fallback;
    }

    /**
     * 请求 B 站接口。
     * 带上 UA / Referer / Cookie 等浏览器同款请求头；命中风控（HTTP 412 或
     * code -412/-352 等）时换一个 buvid3 设备凭证重试一次——"有时候搜不到"
     * 多半就出在这。
     */
    private static JsonObject getJson(String url) throws IOException {
        IOException lastRisk = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                resetBuvid3();
                try { Thread.sleep(400); } catch (InterruptedException ignore) { }
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", "https://search.bilibili.com/");
                conn.setRequestProperty("Origin", "https://www.bilibili.com");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
                conn.setRequestProperty("Cookie",
                        "buvid3=" + buvid3() + "; b_nut=" + (System.currentTimeMillis() / 1000));

                int code = conn.getResponseCode();
                if (isRiskCode(code)) {
                    lastRisk = new IOException("B站接口被风控拦截（HTTP " + code + "）");
                    continue;
                }
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();

                JsonObject obj;
                try {
                    obj = JsonParser.parseString(bos.toString("UTF-8")).getAsJsonObject();
                } catch (Exception parse) {
                    throw new IOException("B站返回数据无法解析");
                }
                if (obj.has("code") && obj.get("code").isJsonPrimitive()) {
                    int biz = obj.get("code").getAsInt();
                    if (isRiskCode(biz)) {
                        lastRisk = new IOException(
                                "B站风控：" + bizMessage(obj, "code " + biz));
                        continue;
                    }
                }
                return obj;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastRisk != null ? lastRisk : new IOException("B站接口请求失败");
    }
}

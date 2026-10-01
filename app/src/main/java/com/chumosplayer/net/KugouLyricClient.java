package com.chumosplayer.net;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 酷狗歌词客户端（三步链路，均已在真机网络下验证可用）：
 *  1. 搜索拿 hash     http://mobilecdn.kugou.com/api/v3/search/song
 *  2. 换歌词 id/accesskey  http://krcs.kugou.com/search
 *  3. 下载 lrc（base64）http://lyrics.kugou.com/download
 * 用 http 而非 https：酷狗这几个域名的证书链在部分环境校验不过，
 * 应用清单已允许明文流量（usesCleartextTraffic）。
 */
public final class KugouLyricClient {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile";
    private static final String SEARCH =
            "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=";
    private static final String KRC_SEARCH =
            "http://krcs.kugou.com/search?ver=1&man=music&client=mobi&keyword=";
    private static final String DOWNLOAD =
            "http://lyrics.kugou.com/download?ver=1&client=pc&id=%s&accesskey=%s&fmt=lrc&charset=utf8";

    private KugouLyricClient() {}

    /**
     * 按关键词取歌词；durationMs 可提高候选匹配准确度（无则传 0）。
     * 任何一步失败都返回 null，由上层换下一个音源。
     */
    public static String fetch(String keyword, long durationMs) {
        if (keyword == null || keyword.trim().isEmpty()) return null;
        try {
            String kw = URLEncoder.encode(keyword.trim(), "UTF-8");

            // 1) 搜索拿 hash
            JsonObject search = getJson(SEARCH + kw + "&page=1&pagesize=1&showtype=1");
            String hash = firstInfoString(search, "hash");
            if (hash == null || hash.isEmpty()) return null;

            // 2) 换歌词候选
            String url = KRC_SEARCH + kw + "&hash=" + hash;
            if (durationMs > 0) url += "&duration=" + durationMs;
            JsonObject krc = getJson(url);
            String id = null;
            String accessKey = null;
            JsonArray cands = krc != null && krc.has("candidates")
                    ? krc.getAsJsonArray("candidates") : null;
            if (cands != null && cands.size() > 0) {
                JsonObject c = cands.get(0).getAsJsonObject();
                id = text(c, "id");
                accessKey = text(c, "accesskey");
            }
            if (id == null || accessKey == null) return null;

            // 3) 下载（base64 内容）
            JsonObject dl = getJson(String.format(java.util.Locale.US, DOWNLOAD, id, accessKey));
            if (dl == null || !dl.has("content") || dl.get("content").isJsonNull()) return null;
            String b64 = dl.get("content").getAsString();
            if (b64.isEmpty()) return null;
            String lrc = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            return lrc.trim().isEmpty() ? null : lrc;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 小工具 ----

    private static String firstInfoString(JsonObject search, String key) {
        try {
            if (search == null || !search.has("data")) return null;
            JsonObject data = search.getAsJsonObject("data");
            if (data == null || !data.has("info")) return null;
            JsonArray info = data.getAsJsonArray("info");
            if (info == null || info.size() == 0) return null;
            return text(info.get(0).getAsJsonObject(), key);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonObject o, String key) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
            String v = o.get(key).getAsString();
            return v == null || v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject getJson(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", UA);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        java.io.InputStream in = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        conn.disconnect();
        try {
            return JsonParser.parseString(bos.toString("UTF-8")).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("歌词返回数据无法解析");
        }
    }
}

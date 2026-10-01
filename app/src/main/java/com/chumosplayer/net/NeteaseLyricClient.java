package com.chumosplayer.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 网易云官方歌词接口（已验证可用）：
 *   https://music.163.com/api/song/lyric?id=&lv=1&kv=1&tv=-1
 * 比走搜索页拿 lrc 更快也更稳，线上歌曲直接用 id 即可。
 */
public final class NeteaseLyricClient {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    private NeteaseLyricClient() {}

    /** 按网易云歌曲 id 取歌词；没有歌词返回 null */
    public static String fetch(long songId) {
        if (songId <= 0) return null;
        try {
            String url = "https://music.163.com/api/song/lyric?id=" + songId
                    + "&lv=1&kv=1&tv=-1";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://music.163.com/");
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

            JsonObject root = JsonParser.parseString(bos.toString("UTF-8")).getAsJsonObject();
            if (root == null || !root.has("lrc") || root.get("lrc").isJsonNull()) return null;
            JsonObject lrc = root.getAsJsonObject("lrc");
            if (lrc == null || !lrc.has("lyric") || lrc.get("lyric").isJsonNull()) return null;
            String text = lrc.get("lyric").getAsString();
            return (text == null || text.trim().isEmpty()) ? null : text;
        } catch (Exception e) {
            return null;
        }
    }
}

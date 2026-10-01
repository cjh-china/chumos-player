package com.chumosplayer.net;

import com.chumosplayer.model.Song;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
 * MyFreeMP3 (https://www.myfreemp3.com.cn/) 客户端。
 *
 * 搜索接口：POST 表单到站点首页（服务端 AJAX），参数：
 *   type=netease & filter=name & page=1 & input=<关键词>
 * 返回 JSON：{ data: { list: [ { id, title, author, lrc, pic, url_kk? ... } ] } }
 * 其中 lrc 为 "data:text/plain;base64,...." 格式，解码即得 LRC 歌词；
 * 网易云源的下载地址为 http://music.163.com/song/media/outer/url?id=<id>.mp3（302 跳转）。
 */
public class MyFreeMP3Client {

    private static final String BASE = "https://www.myfreemp3.com.cn/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 在线搜索（同步，需在子线程调用） */
    public static List<Song> search(String keyword, int page) throws IOException {
        String body = "type=netease&filter=name&page=" + page
                + "&input=" + URLEncoder.encode(keyword, "UTF-8");
        String json = post(BASE, body);
        return parseSearch(json);
    }

    static List<Song> parseSearch(String json) {
        List<Song> list = new ArrayList<>();
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        if (root == null || !root.has("data")) return list;
        JsonObject data = root.getAsJsonObject("data");
        JsonArray arr = data.has("list") && data.get("list").isJsonArray()
                ? data.getAsJsonArray("list") : new JsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Song s = new Song();
            s.online = true;
            s.id = o.has("id") ? o.get("id").getAsLong() : 0;
            s.title = optStr(o, "title");
            s.artist = optStr(o, "author");
            s.album = optStr(o, "album");
            s.pic = optStr(o, "pic");
            String lrc = optStr(o, "lrc");
            s.lrc = decodeLrc(lrc);
            // 网易云源：外链下载/播放地址
            s.url = "http://music.163.com/song/media/outer/url?id=" + s.id + ".mp3";
            // 顺手把时长解析出来：播放器 getDuration() 拿不到时，进度条要靠它定标尺
            s.duration = parseDurationMs(o);
            if (s.title != null) list.add(s);
        }
        return list;
    }

    private static final String[] DURATION_KEYS =
            {"duration", "time", "interval", "length", "dt", "timelen", "songDuration"};

    /**
     * 尽力从搜索结果条目里取时长（毫秒）。
     * 不同源字段名不统一，值也可能是毫秒、秒或 "mm:ss"；解析不出或明显不合理则返回 0。
     */
    static long parseDurationMs(JsonObject o) {
        if (o == null) return 0;
        for (String k : DURATION_KEYS) {
            if (!o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonPrimitive()) continue;
            long ms = toDurationMs(o.get(k).getAsString());
            if (ms > 0) return ms;
        }
        return 0;
    }

    /** 毫秒/秒数字或 "mm:ss"（hh:mm:ss）字符串 -> 毫秒；不合理（<5s 或 >6h）返回 0 */
    static long toDurationMs(String raw) {
        if (raw == null) return 0;
        String v = raw.trim();
        if (v.isEmpty()) return 0;
        try {
            long ms;
            if (v.contains(":")) {
                String[] parts = v.split(":");
                long sec = 0;
                for (String p : parts) sec = sec * 60 + Long.parseLong(p.trim());
                ms = sec * 1000;
            } else {
                double num = Double.parseDouble(v);
                // 数值偏小按秒处理（正常歌曲不会只有几百毫秒）
                ms = (long) (num < 10000 ? num * 1000 : num);
            }
            return (ms >= 5000 && ms <= 6L * 3600 * 1000) ? ms : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 按标题(+歌手)搜索，返回最佳匹配歌曲的歌词；找不到返回 null */
    public static String fetchLyrics(String rawTitle, String artist) throws IOException {
        if (rawTitle == null || rawTitle.trim().isEmpty()) return null;
        String title = rawTitle.trim();
        String art = artist == null ? "" : artist.trim();

        // 标题可能形如 "艺术家 - 歌名"（下载时命名），拆分后更易命中
        if (title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            String maybeArtist = parts[0].trim();
            String maybeTitle = parts[1].trim();
            if (!maybeTitle.isEmpty()) {
                if (art.isEmpty() || "<unknown>".equals(art)) art = maybeArtist;
                title = maybeTitle;
            }
        }

        // 依次尝试若干关键词，命中即返回
        String[] keywords = {title, art.isEmpty() ? title : art + " " + title, rawTitle};
        for (String kw : keywords) {
            if (kw == null || kw.trim().isEmpty()) continue;
            String lrc = searchBestLyric(kw, title, art);
            android.util.Log.d("LrcFetch", "kw=" + kw + " -> "
                    + (lrc == null ? "null" : lrc.length() + " chars"));
            if (lrc != null) return lrc;
        }
        return null;
    }

    private static String searchBestLyric(String keyword, String title, String artist) {
        List<Song> list;
        try {
            list = search(keyword, 1);
        } catch (Exception e) {
            android.util.Log.d("LrcFetch", "search error: " + e.getMessage());
            return null;
        }
        if (list.isEmpty()) return null;
        Song best = null;
        int bestScore = -1;
        for (Song s : list) {
            int score = similarity(title, s.title);
            if (!artist.isEmpty() && s.artist != null
                    && s.artist.contains(artist)) {
                score += 50;
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        // 相似度太低则视为不匹配，避免张冠李戴
        if (best != null && bestScore >= 40
                && best.lrc != null && !best.lrc.trim().isEmpty()) {
            return best.lrc;
        }
        return null;
    }

    /** 归一化：去空白/括号/连字符，转小写，去掉 feat. 之后的内容 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("(?i)(feat|ft)\\..*", "")
                .replaceAll("[\\s\\-_\\(\\)\\[\\]]", "");
    }

    /** 粗略相似度：完全相同 100，包含 70，否则按公共字符比例 */
    private static int similarity(String a, String b) {
        String na = norm(a), nb = norm(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 100;
        if (na.contains(nb) || nb.contains(na)) return 70;
        int common = 0;
        for (int i = 0; i < na.length(); i++) {
            if (nb.indexOf(na.charAt(i)) >= 0) common++;
        }
        return common * 50 / Math.max(na.length(), nb.length());
    }

    /** lrc 字段形如 data:text/plain;base64,xxxx，解码为 LRC 文本 */
    static String decodeLrc(String lrc) {
        if (lrc == null) return "";
        if (lrc.startsWith("data:")) {
            int comma = lrc.indexOf(',');
            if (comma < 0) return "";
            String meta = lrc.substring(0, comma);
            String payload = lrc.substring(comma + 1);
            try {
                if (meta.contains("base64")) {
                    byte[] bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                return java.net.URLDecoder.decode(payload, "UTF-8");
            } catch (Exception ex) {
                return "";
            }
        }
        return lrc;
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 下载音乐文件到输出流（跟随 302 跳转），返回是否成功 */
    public static long download(String url, OutputStream out) throws IOException {
        HttpURLConnection conn = open(url);
        int code = conn.getResponseCode();
        if (code == 301 || code == 302) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            conn = open(loc);
            code = conn.getResponseCode();
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            in.close();
            out.close();
            conn.disconnect();
        }
        return total;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setInstanceFollowRedirects(false); // 手动跟随，兼容旧版本
        return conn;
    }

    /** 公开的连接构造器：供 NeteaseCache 等做 302 直链解析（手动跟随跳转） */
    public static HttpURLConnection openForResolve(String url) throws IOException {
        return open(url);
    }

    private static String post(String url, String formBody) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Referer", BASE);
        conn.setRequestProperty("Origin", "https://www.myfreemp3.com.cn");
        OutputStream os = conn.getOutputStream();
        os.write(formBody.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("搜索失败 HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        conn.disconnect();
        return bos.toString("UTF-8");
    }
}

package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 设置备份 / 恢复：把相关 SharedPreferences 序列化为 JSON，或从 JSON 还原。
 * 备份内容：应用设置、播放历史、下载偏好、黑名单、歌词缓存等。
 */
public final class BackupManager {

    /** 参与备份的 SharedPreferences 文件名 */
    private static final String[] PREFS = {
            "app_settings",     // 深色模式/速度/均衡器等
            "download_prefs",   // 保存目录
            "music_blacklist",  // 黑名单文件夹
            "tips_prefs",       // 首次打开标志等
            "playlists",        // 歌单
            "favorites",        // 我的收藏
            // 说明：netease_cache/play_history 属于可变数据，一并备份
            "netease_cache",
            "play_history",
    };

    private BackupManager() {}

    /** 把全部偏好写成 JSON 到输出流 */
    public static void backup(Context c, OutputStream out) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("_app", "chumo_player");
        root.addProperty("_version", 1);
        for (String name : PREFS) {
            SharedPreferences sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) obj.addProperty(e.getKey(), (Boolean) v);
                else if (v instanceof Integer) obj.addProperty(e.getKey(), (Integer) v);
                else if (v instanceof Long) obj.addProperty(e.getKey(), (Long) v);
                else if (v instanceof Float) obj.addProperty(e.getKey(), (Float) v);
                else if (v instanceof String) obj.addProperty(e.getKey(), (String) v);
                else if (v instanceof java.util.Set) {
                    // StringSet（黑名单）转成 JSON 数组
                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    for (Object o : (java.util.Set<?>) v) arr.add(String.valueOf(o));
                    obj.add(e.getKey(), arr);
                }
            }
            root.add(name, obj);
        }
        String json = new Gson().toJson(root);
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 从输入流读取 JSON 并还原到各偏好；成功返回 true */
    public static boolean restore(Context c, InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String json = bos.toString("UTF-8");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("_app")) return false;

            for (String name : PREFS) {
                if (!root.has(name)) continue;
                JsonObject obj = root.getAsJsonObject(name);
                SharedPreferences.Editor ed =
                        c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                ed.clear();
                for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                    com.google.gson.JsonElement v = e.getValue();
                    if (v.isJsonArray()) {
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (com.google.gson.JsonElement x : v.getAsJsonArray()) {
                            set.add(x.getAsString());
                        }
                        ed.putStringSet(e.getKey(), set);
                    } else if (v.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive p = v.getAsJsonPrimitive();
                        if (p.isBoolean()) ed.putBoolean(e.getKey(), p.getAsBoolean());
                        else if (p.isNumber()) {
                            // 统一按整数存会丢失浮点；按字符串判断
                            String s = p.getAsString();
                            if (s.contains(".")) ed.putFloat(e.getKey(), p.getAsFloat());
                            else ed.putInt(e.getKey(), p.getAsInt());
                        } else ed.putString(e.getKey(), p.getAsString());
                    }
                }
                ed.apply();
            }
            // 恢复深色模式立即生效
            SettingsManager.applyDarkMode(SettingsManager.getDarkMode(c));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

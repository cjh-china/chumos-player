package com.chumosplayer.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.chumosplayer.R;

/**
 * 应用设置：深色模式、播放速度、最近添加天数等偏好。
 */
public final class SettingsManager {

    private static final String PREFS = "app_settings";
    private static final String KEY_DARK_MODE = "dark_mode";       // 0 跟随系统 1 浅色 2 深色 3 纯黑
    private static final String KEY_SPEED = "play_speed";          // 播放速度倍数
    private static final String KEY_RECENT_DAYS = "recent_days";   // 最近添加窗口（天）
    private static final String KEY_GREETING_DELAY = "greeting_delay_ms"; // 问候语弹出延迟（毫秒）
    private static final String KEY_ACCENT = "accent_index";       // 主题色

    public static final int DARK_FOLLOW = 0;
    public static final int DARK_LIGHT = 1;
    public static final int DARK_DARK = 2;
    /** AMOLED 纯黑：夜间模式 + 背景全黑 */
    public static final int DARK_AMOLED = 3;

    /** 主题色数量：0 靛蓝(默认) 1 樱粉 2 薄荷 3 紫罗兰 4 琥珀 5 翠绿 */
    public static final int ACCENT_COUNT = 6;

    /** 在线歌词音源优先级 */
    public static final int LYRIC_SRC_NETEASE = 0;
    public static final int LYRIC_SRC_KUGOU = 1;
    public static final int LYRIC_SRC_COUNT = 2;
    private static final String KEY_LYRIC_SOURCE = "lyric_source";
    private static final String KEY_SCAN_DIRS = "manual_scan_dirs";
    private static final String KEY_COVER_FETCH = "cover_fetch";
    private static final String KEY_HOME_TABS = "home_tabs";
    private static final String KEY_WD_URL = "webdav_url";
    private static final String KEY_WD_USER = "webdav_user";
    private static final String KEY_WD_PASS = "webdav_pass";
    private static final String KEY_SMB_HOST = "smb_host";
    private static final String KEY_SMB_PORT = "smb_port";
    private static final String KEY_SMB_SHARE = "smb_share";
    private static final String KEY_SMB_ROOT = "smb_root";
    private static final String KEY_SMB_USER = "smb_user";
    private static final String KEY_SMB_PASS = "smb_pass";

    /** 首页「本地音乐」的分类 key，顺序即标签顺序 */
    public static final String[] TAB_KEYS = {"song", "artist", "album", "folder", "recent"};

    /** 问候语"不弹出"的哨兵值 */
    public static final int GREETING_NEVER = -1;
    /** 问候语默认延迟：等界面稳定后再弹 */
    public static final int GREETING_DEFAULT_MS = 800;

    private SettingsManager() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 深色模式 ----

    public static int getDarkMode(Context c) {
        return sp(c).getInt(KEY_DARK_MODE, DARK_FOLLOW);
    }

    public static void setDarkMode(Context c, int mode) {
        sp(c).edit().putInt(KEY_DARK_MODE, mode).apply();
        applyDarkMode(mode);
    }

    /** 把设置应用到 AppCompatDelegate（全局生效） */
    public static void applyDarkMode(int mode) {
        switch (mode) {
            case DARK_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case DARK_DARK:
            case DARK_AMOLED:   // 纯黑 = 夜间模式 + 黑色背景主题
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /** 当前是否为 AMOLED 纯黑模式 */
    public static boolean isAmoled(Context c) {
        return getDarkMode(c) == DARK_AMOLED;
    }

    // ---- 主题色 ----

    /** 当前主题色索引（0 = 默认靛蓝） */
    public static int getAccent(Context c) {
        try {
            return sp(c).getInt(KEY_ACCENT, 0);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    public static void setAccent(Context c, int index) {
        if (index < 0 || index >= ACCENT_COUNT) index = 0;
        sp(c).edit().putInt(KEY_ACCENT, index).apply();
    }

    // ---- 在线歌词音源优先级 ----

    /** 优先尝试的在线歌词音源（失败仍会自动落到备选音源） */
    public static int getLyricSource(Context c) {
        try {
            int v = sp(c).getInt(KEY_LYRIC_SOURCE, LYRIC_SRC_NETEASE);
            return (v >= 0 && v < LYRIC_SRC_COUNT) ? v : LYRIC_SRC_NETEASE;
        } catch (ClassCastException e) {
            return LYRIC_SRC_NETEASE;
        }
    }

    public static void setLyricSource(Context c, int src) {
        if (src < 0 || src >= LYRIC_SRC_COUNT) src = LYRIC_SRC_NETEASE;
        sp(c).edit().putInt(KEY_LYRIC_SOURCE, src).apply();
    }

    // ---- 手动扫描目录（APlayer 的「手动扫描目录」）----

    /** 手动添加的扫描目录（canonical path 集合） */
    public static java.util.Set<String> getScanDirs(Context c) {
        try {
            java.util.Set<String> s = sp(c).getStringSet(KEY_SCAN_DIRS, null);
            return s == null ? new java.util.HashSet<String>()
                    : new java.util.HashSet<>(s);
        } catch (Exception e) {
            return new java.util.HashSet<>();
        }
    }

    public static void addScanDir(Context c, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        java.util.Set<String> set = getScanDirs(c);
        set.add(canonicalPath(dir));
        sp(c).edit().putStringSet(KEY_SCAN_DIRS, new java.util.HashSet<>(set)).apply();
    }

    public static void removeScanDir(Context c, String dir) {
        java.util.Set<String> set = getScanDirs(c);
        set.remove(canonicalPath(dir));
        sp(c).edit().putStringSet(KEY_SCAN_DIRS, new java.util.HashSet<>(set)).apply();
    }

    private static String canonicalPath(String p) {
        try {
            return new java.io.File(p).getCanonicalPath();
        } catch (Exception e) {
            return p;
        }
    }

    // ---- 联网补全封面（APlayer 的 auto download artwork）----

    /** 本地歌没有封面时是否联网按「歌手+歌名」补一张（默认开） */
    public static boolean isCoverFetch(Context c) {
        try {
            return sp(c).getBoolean(KEY_COVER_FETCH, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setCoverFetch(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_COVER_FETCH, on).apply();
    }

    // ---- 首页分类标签（APlayer 的 Home Tabs 可配置）----

    /** 启用的分类 key 集合；未设置或为空时全部启用 */
    public static java.util.Set<String> getHomeTabs(Context c) {
        try {
            java.util.Set<String> s = sp(c).getStringSet(KEY_HOME_TABS, null);
            if (s == null || s.isEmpty()) {
                return new java.util.HashSet<>(java.util.Arrays.asList(TAB_KEYS));
            }
            return new java.util.HashSet<>(s);
        } catch (Exception e) {
            return new java.util.HashSet<>(java.util.Arrays.asList(TAB_KEYS));
        }
    }

    /** 保存启用的分类；空集合视为"全不显示"由调用方拦截（至少保留一个） */
    public static void setHomeTabs(Context c, java.util.Set<String> tabs) {
        if (tabs == null || tabs.isEmpty()) return;
        sp(c).edit().putStringSet(KEY_HOME_TABS, new java.util.HashSet<>(tabs)).apply();
    }

    // ---- WebDAV 远程音乐 ----

    public static String getWebDavUrl(Context c) {
        try {
            return sp(c).getString(KEY_WD_URL, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String getWebDavUser(Context c) {
        try {
            return sp(c).getString(KEY_WD_USER, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String getWebDavPass(Context c) {
        try {
            return sp(c).getString(KEY_WD_PASS, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 保存 WebDAV 连接信息（随设置备份一起导出） */
    public static void setWebDav(Context c, String url, String user, String pass) {
        sp(c).edit()
                .putString(KEY_WD_URL, url == null ? "" : url.trim())
                .putString(KEY_WD_USER, user == null ? "" : user.trim())
                .putString(KEY_WD_PASS, pass == null ? "" : pass)
                .apply();
    }

    /** 是否已配置 WebDAV（至少要有服务器地址） */
    public static boolean isWebDavConfigured(Context c) {
        String u = getWebDavUrl(c);
        return u != null && u.trim().startsWith("http");
    }

    // ---- SMB 远程音乐 ----

    public static String getSmbHost(Context c) {
        return str(c, KEY_SMB_HOST);
    }

    public static int getSmbPort(Context c) {
        try {
            int p = sp(c).getInt(KEY_SMB_PORT, 445);
            return p > 0 ? p : 445;
        } catch (Exception e) {
            return 445;
        }
    }

    public static String getSmbShare(Context c) {
        return str(c, KEY_SMB_SHARE);
    }

    /** 共享内根路径（可空，形如 Music） */
    public static String getSmbRoot(Context c) {
        return str(c, KEY_SMB_ROOT);
    }

    public static String getSmbUser(Context c) {
        return str(c, KEY_SMB_USER);
    }

    public static String getSmbPass(Context c) {
        return str(c, KEY_SMB_PASS);
    }

    public static void setSmb(Context c, String host, int port, String share,
                              String root, String user, String pass) {
        sp(c).edit()
                .putString(KEY_SMB_HOST, host == null ? "" : host.trim())
                .putInt(KEY_SMB_PORT, port > 0 ? port : 445)
                .putString(KEY_SMB_SHARE, share == null ? "" : share.trim())
                .putString(KEY_SMB_ROOT, root == null ? "" : root.trim())
                .putString(KEY_SMB_USER, user == null ? "" : user.trim())
                .putString(KEY_SMB_PASS, pass == null ? "" : pass)
                .apply();
    }

    /** 已配置：主机与共享名都填了才算 */
    public static boolean isSmbConfigured(Context c) {
        String h = getSmbHost(c);
        String s = getSmbShare(c);
        return h != null && !h.isEmpty() && s != null && !s.isEmpty();
    }

    private static String str(Context c, String key) {
        try {
            return sp(c).getString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 「深浅/纯黑 + 主题色」组合出的主题资源 id */    public static int themeRes(Context c) {
        int accent = getAccent(c);
        if (accent < 0 || accent >= ACCENT_THEMES.length) accent = 0;
        return isAmoled(c) ? ACCENT_AMOLED_THEMES[accent] : ACCENT_THEMES[accent];
    }

    /**
     * 在 super.onCreate 之前调用：布局里的 ?attr/appBg|appAccent|appCard
     * 都从这里选定的主题取值，换主题即换色。
     */
    public static void applyTheme(android.app.Activity a) {
        if (a == null) return;
        try {
            a.setTheme(themeRes(a));
        } catch (Exception ignore) { }
    }

    // ---- 播放模式（列表循环 / 随机 / 单曲循环）----

    public static final int MODE_LOOP = 0;
    public static final int MODE_SHUFFLE = 1;
    public static final int MODE_SINGLE = 2;
    public static final int MODE_COUNT = 3;
    private static final String KEY_PLAY_MODE = "play_mode";

    public static int getPlayMode(Context c) {
        try {
            int v = sp(c).getInt(KEY_PLAY_MODE, MODE_LOOP);
            return (v >= 0 && v < MODE_COUNT) ? v : MODE_LOOP;
        } catch (Exception e) {
            return MODE_LOOP;
        }
    }

    public static void setPlayMode(Context c, int mode) {
        if (mode < 0 || mode >= MODE_COUNT) mode = MODE_LOOP;
        sp(c).edit().putInt(KEY_PLAY_MODE, mode).apply();
    }

    // ---- 播放速度 ----

    public static float getSpeed(Context c) {
        return sp(c).getFloat(KEY_SPEED, 1.0f);
    }

    public static void setSpeed(Context c, float speed) {
        sp(c).edit().putFloat(KEY_SPEED, speed).apply();
    }

    // ---- 标签编辑器（jaudiotagger）----

    /** 是否启用标签编辑器；默认启用 */
    public static boolean isTagEditorEnabled(Context c) {
        return sp(c).getBoolean("tag_editor_enabled", true);
    }

    public static void setTagEditorEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("tag_editor_enabled", on).apply();
    }

    // ---- 桌面悬浮歌词 ----

    public static boolean isFloatLyric(Context c) {
        return sp(c).getBoolean("float_lyric", false);
    }

    public static void setFloatLyric(Context c, boolean on) {
        sp(c).edit().putBoolean("float_lyric", on).apply();
    }

    // ---- 均衡器 ----

    public static boolean isEqEnabled(Context c) {
        return sp(c).getBoolean("eq_enabled", false);
    }

    public static void setEqEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("eq_enabled", on).apply();
    }

    public static int getEqPreset(Context c) {
        return sp(c).getInt("eq_preset", 0);
    }

    public static void setEqPreset(Context c, int preset) {
        sp(c).edit().putInt("eq_preset", preset).apply();
    }

    public static boolean isBassBoost(Context c) {
        return sp(c).getBoolean("bass_boost", false);
    }

    public static void setBassBoost(Context c, boolean on) {
        sp(c).edit().putBoolean("bass_boost", on).apply();
    }

    public static boolean isVirtualizer(Context c) {
        return sp(c).getBoolean("virtualizer", false);
    }

    public static void setVirtualizer(Context c, boolean on) {
        sp(c).edit().putBoolean("virtualizer", on).apply();
    }

    // ---- 最近添加窗口 ----

    public static int getRecentDays(Context c) {
        return sp(c).getInt(KEY_RECENT_DAYS, 7);
    }

    public static void setRecentDays(Context c, int days) {
        sp(c).edit().putInt(KEY_RECENT_DAYS, days).apply();
    }

    // ---- 问候语弹出时间 ----

    /**
     * 启动问候语的弹出延迟（毫秒）；{@link #GREETING_NEVER} 表示不弹。
     * 用 Int 而非 Long：备份/恢复链路（BackupManager）对数字按 int 还原，
     * 存 Long 会导致恢复后 getLong 读到 int 而崩溃。
     */
    public static int getGreetingDelayMs(Context c) {
        try {
            return sp(c).getInt(KEY_GREETING_DELAY, GREETING_DEFAULT_MS);
        } catch (ClassCastException e) {
            return GREETING_DEFAULT_MS; // 旧备份里类型不一致时兜底
        }
    }

    public static void setGreetingDelayMs(Context c, int ms) {
        sp(c).edit().putInt(KEY_GREETING_DELAY, ms).apply();
    }

    // ---- 主题资源映射（与 styles.xml 中的样式一一对应）----

    /** 普通背景 + 各主题色 */
    private static final int[] ACCENT_THEMES = {
            R.style.Theme_SimpleMusic,
            R.style.Theme_SimpleMusic_Accent_Rose,
            R.style.Theme_SimpleMusic_Accent_Mint,
            R.style.Theme_SimpleMusic_Accent_Violet,
            R.style.Theme_SimpleMusic_Accent_Amber,
            R.style.Theme_SimpleMusic_Accent_Green,
    };

    /** AMOLED 纯黑背景 + 各主题色 */
    private static final int[] ACCENT_AMOLED_THEMES = {
            R.style.Theme_SimpleMusic_Amoled,
            R.style.Theme_SimpleMusic_Accent_Rose_Amoled,
            R.style.Theme_SimpleMusic_Accent_Mint_Amoled,
            R.style.Theme_SimpleMusic_Accent_Violet_Amoled,
            R.style.Theme_SimpleMusic_Accent_Amber_Amoled,
            R.style.Theme_SimpleMusic_Accent_Green_Amoled,
    };
}

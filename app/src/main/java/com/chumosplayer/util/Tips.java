package com.chumosplayer.util;

import java.util.Calendar;
import java.util.Random;

/**
 * 暖心 & 幽默小提示文案池。
 * 分四类：首次欢迎语、时段问候、闲时随机提示、情境触发提示（播放/下载等）。
 */
public final class Tips {

    private static final Random RAND = new Random();

    private Tips() {}

    /** 闲时随机小提示（温和、暖心、偶尔皮一下） */
    private static final String[] IDLE = {
            "🎧 戴上耳机，世界就是你的了。",
            "🌙 夜深了，把音量调小一点，别吵到梦。",
            "☕ 听歌虽好，别忘了喝水哦。",
            "🐱 据说听歌的人运气都不会太差。",
            "🎵 这首歌，送给正在努力的你。",
            "🌿 深呼吸，让旋律替你放松一下。",
            "💡 小提示：长按列表项可以……算了，其实不行。",
            "🧦 有人此刻也在和你听同一首歌，真巧。",
            "🍜 饿了就去吃点东西，音乐不会跑。",
            "✨ 你今天已经很棒了，真的。",
            "🐟 划水一时爽，一直划水……记得保存进度。",
            "🎼 音符不会催你，你也不用催自己。",
            "📻 电台没信号的时候，就听本地音乐吧。",
            "🌈 心情不好的时候，换首歌试试？",
            "🍰 听一首歌的时间，够吃一口蛋糕了。",
    };

    /** 开始播放时的情境提示 */
    private static final String[] ON_PLAY = {
            "▶️ 开始播放，放松一下~",
            "🎶 好歌开场，请系好安全带。",
            "🔊 音乐已就位，心情请跟上。",
    };

    /** 暂停时的情境提示 */
    private static final String[] ON_PAUSE = {
            "⏸️ 暂停一下，喘口气也好。",
            "☕ 去倒杯水吧，我等你。",
            "🛑 停一停，是为了走更远。",
    };

    /** 下载完成时的情境提示 */
    private static final String[] ON_DOWNLOAD = {
            "💾 下载完成，离线也能听啦。",
            "📥 已收入囊中，随时开听。",
            "✅ 保存好啦，流量党狂喜。",
    };

    /** 下载失败时的情境提示 */
    private static final String[] ON_ERROR = {
            "😅 出了点小状况，稍后再试试？",
            "🛠️ 网络打了个盹，重试一下吧。",
            "🙈 这次没成功，不是你的错。",
    };

    /** 首次打开 app 的欢迎语 */
    public static String welcome() {
        String[] w = {
                "👋 欢迎来到初墨播放器，随便听点什么吧~",
                "🎉 初墨播放器已就绪，音乐之旅开始啦！",
                "🌟 欢迎回来，今天想听点什么？",
        };
        return w[RAND.nextInt(w.length)];
    }

    /** 根据当前时段返回问候语 */
    public static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 9) {
            return "🌅 早上好，用一首歌唤醒今天吧。";
        } else if (hour >= 9 && hour < 12) {
            return "☀️ 上午好，专注的时候放点轻音乐。";
        } else if (hour >= 12 && hour < 14) {
            return "🍚 中午好，吃饱了再听歌哦。";
        } else if (hour >= 14 && hour < 18) {
            return "🌤️ 下午好，来点节奏提提神？";
        } else if (hour >= 18 && hour < 23) {
            return "🌆 晚上好，忙完了吧？放松一下。";
        } else {
            return "🌙 夜深了，早点休息，别熬太晚。";
        }
    }

    /** 随机取一条闲时提示 */
    public static String idle() {
        return IDLE[RAND.nextInt(IDLE.length)];
    }

    public static String onPlay() {
        return ON_PLAY[RAND.nextInt(ON_PLAY.length)];
    }

    public static String onPause() {
        return ON_PAUSE[RAND.nextInt(ON_PAUSE.length)];
    }

    public static String onDownload() {
        return ON_DOWNLOAD[RAND.nextInt(ON_DOWNLOAD.length)];
    }

    public static String onError() {
        return ON_ERROR[RAND.nextInt(ON_ERROR.length)];
    }
}

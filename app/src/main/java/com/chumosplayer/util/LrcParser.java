package com.chumosplayer.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 歌词解析：把带 [mm:ss.xx] 时间标签的文本解析为按时间升序的歌词行 */
public final class LrcParser {

    private LrcParser() {}

    /** 一行歌词：时间（毫秒）+ 文本（可能为空，表示间奏） */
    public static class Line implements Comparable<Line> {
        public final long timeMs;
        public final String text;
        /** 逐字歌词（增强型 LRC，形如 <00:12.50>词）；普通歌词为空列表 */
        public final List<Word> words;

        public Line(long timeMs, String text) {
            this(timeMs, text, null);
        }

        public Line(long timeMs, String text, List<Word> words) {
            this.timeMs = timeMs;
            this.text = text;
            this.words = words == null ? Collections.<Word>emptyList() : words;
        }

        /** 是否带逐字时间（可用于逐字高亮） */
        public boolean hasWords() {
            return !words.isEmpty();
        }

        @Override
        public int compareTo(Line o) {
            return Long.compare(timeMs, o.timeMs);
        }
    }

    /** 逐字歌词里的一个词 */
    public static class Word {
        public final long startMs;
        public final String text;

        public Word(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    /** 匹配 [mm:ss]、[mm:ss.xx]、[mm:ss:xx]、[mm:ss,xx] 形式的时间标签 */
    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:,](\\d{1,3}))?\\]");

    /** 匹配增强型 LRC 的逐字标签 <mm:ss.xx> / <mm:ss,xx> / <mm:ss> */
    private static final Pattern WORD_TAG =
            Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:,](\\d{1,3}))?>");

    /** 解析 LRC 文本，返回按时间升序的歌词行；无时间标签的行被忽略 */
    public static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return lines;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TIME_TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                times.add(toMs(m.group(1), m.group(2), m.group(3)));
                lastEnd = m.end();
            }
            if (times.isEmpty()) continue;
            String body = raw.substring(lastEnd);
            for (long t : times) {
                List<Word> words = parseWords(body, t);
                String text = stripWordTags(body);
                lines.add(new Line(t, words.isEmpty() ? text : joinWords(words), words));
            }
        }
        Collections.sort(lines);
        return lines;
    }

    /** 解析一行里的逐字标签：标签之间的文字归上一个标签的时间点 */
    private static List<Word> parseWords(String body, long lineTime) {
        List<Word> words = new ArrayList<>();
        Matcher m = WORD_TAG.matcher(body);
        int lastEnd = 0;
        long lastTime = -1;
        while (m.find()) {
            if (lastTime >= 0) {
                String seg = body.substring(lastEnd, m.start());
                if (!seg.isEmpty()) words.add(new Word(lastTime, seg));
            } else {
                // 第一个逐字标签之前的前缀，归到整行开始时间
                String prefix = body.substring(0, m.start());
                if (!prefix.trim().isEmpty()) words.add(new Word(lineTime, prefix));
            }
            lastTime = toMs(m.group(1), m.group(2), m.group(3));
            lastEnd = m.end();
        }
        if (lastTime >= 0) {
            String seg = body.substring(lastEnd);
            if (!seg.isEmpty()) words.add(new Word(lastTime, seg));
        }
        return words;
    }

    /** 去掉逐字标签，得到可直接显示的整行文本 */
    private static String stripWordTags(String body) {
        return WORD_TAG.matcher(body).replaceAll("").trim();
    }

    private static String joinWords(List<Word> words) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) sb.append(w.text);
        return sb.toString().trim();
    }

    /** 分段时间（毫秒）；frac 可为 1~3 位（0.1s / 0.01s / 1ms） */
    private static long toMs(String min, String sec, String frac) {
        long m = Long.parseLong(min);
        long s = Long.parseLong(sec);
        long ms = 0;
        if (frac != null && !frac.isEmpty()) {
            ms = Long.parseLong(frac);
            if (frac.length() == 1) ms *= 100;
            else if (frac.length() == 2) ms *= 10;
        }
        return m * 60000 + s * 1000 + ms;
    }
}

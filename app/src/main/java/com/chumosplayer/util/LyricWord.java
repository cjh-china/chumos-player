package com.chumosplayer.util;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.List;

/**
 * 逐字歌词高亮：按当前播放时刻算出该亮的词，并渲染成带色文本。
 * 供全屏播放页与桌面悬浮歌词共用。
 */
public final class LyricWord {

    private LyricWord() {}

    /** 当前时刻应高亮的词下标；没有逐字信息或还没到第一个词返回 -1 */
    public static int indexAt(List<LrcParser.Word> words, long curMs) {
        if (words == null || words.isEmpty()) return -1;
        int idx = -1;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).startMs <= curMs) idx = i;
            else break;
        }
        return idx;
    }

    /** 整行文本（与 words 一一对应，供 span 定位） */
    public static String plain(List<LrcParser.Word> words) {
        if (words == null) return "";
        StringBuilder sb = new StringBuilder();
        for (LrcParser.Word w : words) sb.append(w.text);
        return sb.toString();
    }

    /**
     * 渲染整行：当前词用 hiColor，其余用 normalColor；
     * 还没到第一个词时整行 normalColor。
     */
    public static CharSequence render(List<LrcParser.Word> words, long curMs,
                                      int normalColor, int hiColor) {
        if (words == null || words.isEmpty()) return "";
        int hi = indexAt(words, curMs);
        StringBuilder sb = new StringBuilder();
        int pos = 0, start = -1, end = -1;
        for (int i = 0; i < words.size(); i++) {
            String t = words.get(i).text;
            if (i == hi) {
                start = pos;
                end = pos + t.length();
            }
            sb.append(t);
            pos += t.length();
        }
        SpannableString sp = new SpannableString(sb);
        int from = start >= 0 ? start : 0;
        int to = end >= 0 ? end : sp.length();
        if (to > from) {
            sp.setSpan(new ForegroundColorSpan(start >= 0 ? hiColor : normalColor),
                    from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // 其余部分显式设为普通色（TextView 自身颜色可能因整行高亮被改过）
        if (start >= 0) {
            if (start > 0) {
                sp.setSpan(new ForegroundColorSpan(normalColor), 0, start,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (end < sp.length()) {
                sp.setSpan(new ForegroundColorSpan(normalColor), end, sp.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sp;
    }
}

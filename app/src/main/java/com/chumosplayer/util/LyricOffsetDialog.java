package com.chumosplayer.util;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.chumosplayer.R;

/**
 * 歌词时间校准对话框（v2.1）：播放页「更多」与设置页共用。
 * 偏移范围 ±5 秒、步进 100ms，正值表示歌词提前，持久化在 app_settings。
 */
public final class LyricOffsetDialog {

    private LyricOffsetDialog() {}

    public static void show(Context ctx) {
        final int[] value = {SettingsManager.getLyricOffsetMs(ctx)};
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView label = new TextView(ctx);
        label.setTextSize(15);
        label.setTextColor(ctx.getResources().getColor(R.color.text_primary));

        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(100); // -5000ms ~ +5000ms，每格 100ms
        bar.setProgress((value[0] + 5000) / 100);

        final Runnable update = () -> {
            float sec = Math.abs(value[0]) / 1000f;
            label.setText(value[0] == 0 ? "不偏移"
                    : (value[0] > 0 ? "歌词提前 " : "歌词延后 ")
                    + String.format(java.util.Locale.CHINA, "%.1f", sec) + " 秒");
        };
        update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value[0] = p * 100 - 5000;
                update.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        box.addView(label);
        box.addView(bar);

        new android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_lyric_offset)
                .setMessage("歌词比声音快就往「延后」调，慢就往「提前」调。")
                .setView(box)
                .setPositiveButton("保存", (d, w) ->
                        SettingsManager.setLyricOffsetMs(ctx, value[0]))
                .setNeutralButton("归零", (d, w) ->
                        SettingsManager.setLyricOffsetMs(ctx, 0))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

package com.chumosplayer.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.chumosplayer.R;
import com.chumosplayer.model.Song;

/** 歌词查看对话框（LRC 纯文本展示） */
public class LyricsDialogFragment extends DialogFragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_LRC = "lrc";

    public static void show(FragmentManager fm, Song song) {
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, song.title + " - " + song.artist);
        args.putString(ARG_LRC, song.lrc == null ? "" : song.lrc);
        LyricsDialogFragment f = new LyricsDialogFragment();
        f.setArguments(args);
        f.show(fm, "lyrics");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_lyrics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String title = args != null ? args.getString(ARG_TITLE, "") : "";
        String lrc = args != null ? args.getString(ARG_LRC, "") : "";
        ((TextView) view.findViewById(R.id.tv_lyrics_title)).setText(title);
        ((TextView) view.findViewById(R.id.tv_lyrics)).setText(
                lrc.isEmpty() ? "暂无歌词" : stripLrcTags(lrc));
        view.findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.8));
        }
    }

    /** 去掉 [00:12.34] 行时间标签与 <00:12.50> 逐字标签，只留文本行 */
    public static String stripLrcTags(String lrc) {
        return lrc.replaceAll("\\[\\d{1,2}:\\d{1,2}(\\.\\d{1,3})?\\]", "")
                .replaceAll("<\\d{1,3}:\\d{1,2}([.:,]\\d{1,3})?>", "")
                .trim();
    }
}

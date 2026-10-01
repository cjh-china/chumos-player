package com.chumosplayer.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import com.chumosplayer.fragment.LocalMusicFragment;
import com.chumosplayer.fragment.OnlineFragment;

/** 主界面两个标签页：本地音乐 / 在线搜索 */
public class MainPagerAdapter extends FragmentPagerAdapter {

    private final String[] titles;

    public MainPagerAdapter(@NonNull FragmentManager fm, String[] titles) {
        super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        this.titles = titles;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        return position == 0 ? new LocalMusicFragment() : new OnlineFragment();
    }

    @Override
    public int getCount() {
        return 2;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        return titles[position];
    }
}

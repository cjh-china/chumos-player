package com.chumosplayer.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import com.chumosplayer.fragment.LocalMusicFragment;
import com.chumosplayer.fragment.MyMusicFragment;
import com.chumosplayer.fragment.OnlineFragment;

/** 主界面三个一级页面，与底部导航一一对应 */
public class MainPagerAdapter extends FragmentPagerAdapter {

    private final String[] titles;

    public MainPagerAdapter(@NonNull FragmentManager fm, String[] titles) {
        super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        this.titles = titles;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
        switch (position) {
            case 0: return new LocalMusicFragment();
            case 1: return new OnlineFragment();
            default: return new MyMusicFragment();
        }
    }

    @Override
    public int getCount() {
        return 3;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        return position >= 0 && position < titles.length ? titles[position] : "";
    }
}

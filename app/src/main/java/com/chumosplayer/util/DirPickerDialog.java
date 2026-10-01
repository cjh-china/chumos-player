package com.chumosplayer.util;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.ArrayAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 保存目录选择器：支持内置存储与 TF 卡等外置卷，禁止进入系统 root 目录 */
public final class DirPickerDialog {

    private DirPickerDialog() {}

    public interface OnPick {
        void onPicked(File dir);
    }

    public static void show(Context ctx, File start, OnPick cb) {
        List<File> roots = storageRoots(ctx);
        if (roots.isEmpty()) roots.add(Environment.getExternalStorageDirectory());
        File fallback = roots.get(0);
        File cur = (start != null && start.isDirectory()
                && !DownloadUtil.isForbiddenDir(start)) ? start : fallback;
        browse(ctx, cur, roots, cb);
    }

    /** 枚举所有可访问的存储卷根目录（内置存储 + TF 卡等外置卷） */
    public static List<File> storageRoots(Context ctx) {
        List<File> roots = new ArrayList<>();
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (sm != null) {
                for (StorageVolume vol : sm.getStorageVolumes()) {
                    File dir = volumeDir(vol);
                    if (dir != null && dir.isDirectory() && dir.canRead()) {
                        roots.add(dir);
                    }
                }
            }
        } catch (Exception ignore) { }
        if (roots.isEmpty()) {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) roots.add(ext);
        }
        return roots;
    }

    /** 取存储卷根目录：API 30+ 用 getDirectory()，更低版本反射 getPath() */
    @SuppressWarnings("deprecation")
    private static File volumeDir(StorageVolume vol) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                File d = vol.getDirectory();
                if (d != null) return d;
            }
            java.lang.reflect.Method m = StorageVolume.class.getMethod("getPath");
            String path = (String) m.invoke(vol);
            if (path != null) return new File(path);
        } catch (Exception ignore) { }
        return null;
    }

    private static void browse(Context ctx, File dir, List<File> roots, OnPick cb) {
        List<File> dirs = new ArrayList<>();
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File c : children) {
                // 过滤不可读、隐藏目录与系统 root 目录
                if (DownloadUtil.isForbiddenDir(c)) continue;
                if (c.getName().startsWith(".")) continue;
                if (!c.canRead()) continue;
                dirs.add(c);
            }
        }

        boolean atRoot = roots.contains(dir);
        List<String> names = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        names.add("✔ 选择此目录：" + dir.getAbsolutePath());
        actions.add(() -> cb.onPicked(dir));

        if (roots.size() > 1) {
            names.add("💾 切换存储卷");
            actions.add(() -> chooseVolume(ctx, roots, cb));
        }

        if (!atRoot && dir.getParentFile() != null
                && !DownloadUtil.isForbiddenDir(dir.getParentFile())) {
            File parent = dir.getParentFile();
            names.add("⬆ 上级目录");
            actions.add(() -> browse(ctx, parent, roots, cb));
        }

        for (File d : dirs) {
            names.add("📁 " + d.getName());
            actions.add(() -> browse(ctx, d, roots, cb));
        }

        new AlertDialog.Builder(ctx)
                .setTitle("选择目录")
                .setAdapter(new ArrayAdapter<>(ctx,
                        android.R.layout.simple_list_item_1, names),
                        (dlg, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 在多个存储卷之间切换（内置 / TF 卡等） */
    private static void chooseVolume(Context ctx, List<File> roots, OnPick cb) {
        File primary = Environment.getExternalStorageDirectory();
        List<String> labels = new ArrayList<>();
        for (File r : roots) {
            String type = r.equals(primary) ? "内置存储" : "外置存储(TF卡等)";
            labels.add(type + "：" + r.getAbsolutePath());
        }
        new AlertDialog.Builder(ctx)
                .setTitle("选择存储卷")
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        browse(ctx, roots.get(which), roots, cb))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

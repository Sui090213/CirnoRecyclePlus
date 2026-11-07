package nep.timeline.cirno.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import nep.timeline.cirno.services.ActivityManagerService;

/**
 * 文件选择器 / 文档提供器保护.
 *
 * <p>文件选择链路不是普通后台应用: 文件管理器或文档提供器进程一旦被 cgroup 冻住,
 * 系统启动它的 Activity / 查询它的 ContentProvider 时就会一直等, 用户看到的是
 * 「刚点开文件管理器就卡住」. 这里把常见文件选择器和当前默认文档选择器整体排除在
 * 自动冻结之外.
 *
 * <p>为什么还要动态查默认处理者: 用户可能把第三方文件管理器设为默认
 * ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT 处理者, 无法只靠包名枚举覆盖.
 */
public final class FilePickerGuard {
    private static final long REFRESH_INTERVAL_MS = 60_000L;

    /** 常见文件管理器 / 文档提供器; 系统应用本来也会被 isSystemApp 挡住, 这里主要覆盖 OEM / 第三方. */
    private static final Set<String> KNOWN_PICKERS = Set.of(
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.filemanager",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.downloads",
            "com.android.externalstorage",
            "com.miui.filemanager",
            "com.mi.android.globalFileexplorer",
            "com.coloros.filemanager",
            "com.oplus.filemanager",
            "com.oneplus.filemanager",
            "com.realme.filemanager",
            "com.sec.android.app.myfiles",
            "com.google.android.apps.nbu.files",
            "com.estrongs.android.pop",
            "com.ghisler.android.TotalCommander",
            "bin.mt.plus"
    );

    private static volatile long lastRefreshMs = 0L;
    private static volatile Set<String> resolvedPickers = Collections.emptySet();

    private FilePickerGuard() {
    }

    public static boolean isFilePicker(String packageName) {
        if (packageName == null || packageName.isEmpty())
            return false;
        if (KNOWN_PICKERS.contains(packageName))
            return true;

        refreshIfNeeded();
        return resolvedPickers.contains(packageName);
    }

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < REFRESH_INTERVAL_MS)
            return;

        synchronized (FilePickerGuard.class) {
            now = System.currentTimeMillis();
            if (now - lastRefreshMs < REFRESH_INTERVAL_MS)
                return;

            Set<String> pickers = new HashSet<>();
            try {
                Context context = ActivityManagerService.getContext();
                if (context != null) {
                    PackageManager packageManager = context.getPackageManager();
                    if (packageManager != null) {
                        addResolved(packageManager, pickers, Intent.ACTION_GET_CONTENT);
                        addResolved(packageManager, pickers, Intent.ACTION_OPEN_DOCUMENT);
                        addResolved(packageManager, pickers, Intent.ACTION_CREATE_DOCUMENT);
                    }
                }
            } catch (Throwable ignored) {
                // 系统还没就绪 / PackageManager 暂时不可用时保持空集合, 下一分钟再试.
            }

            resolvedPickers = Collections.unmodifiableSet(pickers);
            lastRefreshMs = now;
        }
    }

    private static void addResolved(PackageManager packageManager, Set<String> out, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setType("*/*");
            ResolveInfo info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null)
                out.add(info.activityInfo.packageName);
        } catch (Throwable ignored) {
        }
    }
}

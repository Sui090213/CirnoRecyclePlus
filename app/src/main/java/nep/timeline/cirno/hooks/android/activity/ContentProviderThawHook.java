package nep.timeline.cirno.hooks.android.activity;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

import java.lang.reflect.Method;

import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.ActivityManagerService;
import nep.timeline.cirno.services.FreezerService;

/**
 * ContentProvider 查询前解冻提供方.
 *
 * <p>很多文件选择器 / 分享面板在打开后会立刻 query 调用方的 ContentProvider.
 * 如果提供方进程已经被冻结, 这个同步 Binder 查询会一直阻塞, 文件管理器界面看起来
 * 就是「刚打开就卡住」. 这里在 ActivityManagerService / ContentProviderHelper
 * 获取 provider 前, 按 authority 反查包名并先解冻.
 */
public class ContentProviderThawHook {
    private static final String[] TARGET_CLASSES = {
            "com.android.server.am.ContentProviderHelper",
            "com.android.server.am.ActivityManagerService"
    };

    private static final String[] TARGET_METHODS = {
            "getContentProvider",
            "getContentProviderExternal",
            "getContentProviderImpl"
    };

    public ContentProviderThawHook(ClassLoader classLoader) {
        int count = 0;
        for (String className : TARGET_CLASSES) {
            try {
                Class<?> clazz = CakeReflection.findClassIfExists(className, classLoader);
                if (clazz == null)
                    continue;

                for (Method method : clazz.getDeclaredMethods()) {
                    if (!isTargetMethod(method) || !hasStringParameter(method))
                        continue;
                    CakeHooker.hookBefore(method, callback -> handle(callback.getArgs()));
                    count++;
                }
            } catch (Throwable throwable) {
                Log.w("安装 ContentProvider 解冻 Hook 失败: " + className, throwable);
            }
        }

        if (count > 0)
            Log.i("ContentProvider 查询前解冻 Hook 安装成功: " + count + " 个方法");
        else
            Log.w("没有找到 ContentProvider 获取方法, 查询前解冻 Hook 未安装");
    }

    private static boolean isTargetMethod(Method method) {
        String name = method.getName();
        for (String target : TARGET_METHODS) {
            if (target.equals(name))
                return true;
        }
        return false;
    }

    private static boolean hasStringParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == String.class)
                return true;
        }
        return false;
    }

    private static void handle(Object[] args) {
        try {
            String authority = null;
            for (Object arg : args) {
                if (arg instanceof String value && !value.isEmpty()) {
                    authority = value;
                    break;
                }
            }
            if (authority == null)
                return;

            String packageName = resolveProviderPackage(authority);
            if (packageName != null && !packageName.isEmpty())
                FreezerService.temporaryUnfreezeIfFrozenByPackage(packageName, "查询 ContentProvider", 15_000);
        } catch (Throwable ignored) {
            // Hook 回调里的异常不能抛回 system_server.
        }
    }

    private static String resolveProviderPackage(String authority) {
        try {
            Context context = ActivityManagerService.getContext();
            if (context == null)
                return null;
            PackageManager packageManager = context.getPackageManager();
            if (packageManager == null)
                return null;
            ProviderInfo info = packageManager.resolveContentProvider(authority, 0);
            if (info == null)
                return null;
            return info.packageName;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

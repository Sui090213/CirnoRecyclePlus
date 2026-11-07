package nep.timeline.cirno.hooks.android.activity;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;

import java.lang.reflect.Method;

import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.ActivityManagerService;
import nep.timeline.cirno.services.FreezerService;

/**
 * Activity 启动 / 返回结果前解冻.
 *
 * <p>只在 ACTIVITY_RESUMED 之后解冻已经太晚: 目标进程被 cgroup 冻住时, 系统发过去的
 * Activity 启动事务不能被处理, Activity 永远不会 RESUMED, 形成死锁. 文件选择器、
 * 第三方应用冷启动都会表现成「刚点开就卡住」.
 *
 * <p>这里在 ActivityTaskManagerService 真正启动 Activity 前, 以及把 Activity 结果
 * 送回调用方前, 先解冻目标包.
 */
public class ActivityLaunchThawHook {
    private static final String TARGET_CLASS = "com.android.server.wm.ActivityTaskManagerService";

    public ActivityLaunchThawHook(ClassLoader classLoader) {
        install(classLoader);
    }

    private static void install(ClassLoader classLoader) {
        try {
            Class<?> clazz = CakeReflection.findClassIfExists(TARGET_CLASS, classLoader);
            if (clazz == null) {
                Log.w("找不到 ActivityTaskManagerService, 启动前解冻 Hook 未安装");
                return;
            }

            int launchCount = 0;
            int resultCount = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if (name.startsWith("startActivity") && hasIntentParameter(method)) {
                    CakeHooker.hookBefore(method, callback -> handleLaunch(callback.getArgs()));
                    launchCount++;
                } else if (name.startsWith("sendActivityResult") && hasFirstBinderParameter(method)) {
                    CakeHooker.hookBefore(method, callback -> handleResult(callback.getArgs()));
                    resultCount++;
                }
            }

            Log.i("Activity 启动前解冻 Hook: " + launchCount + " 个启动方法, "
                    + resultCount + " 个结果方法");
        } catch (Throwable throwable) {
            Log.e("安装 Activity 启动前解冻 Hook 失败", throwable);
        }
    }

    private static boolean hasIntentParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(type))
                return true;
        }
        return false;
    }

    private static boolean hasFirstBinderParameter(Method method) {
        Class<?>[] types = method.getParameterTypes();
        return types.length > 0 && IBinder.class.isAssignableFrom(types[0]);
    }

    private static void handleLaunch(Object[] args) {
        try {
            Intent intent = null;
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    intent = (Intent) arg;
                    break;
                }
            }
            String packageName = resolvePackage(intent);
            if (packageName != null && !packageName.isEmpty())
                FreezerService.temporaryUnfreezeIfFrozenByPackage(packageName, "启动 Activity", 15_000);
        } catch (Throwable ignored) {
            // 绝对不能把异常抛回 system_server.
        }
    }

    private static void handleResult(Object[] args) {
        try {
            if (args.length == 0 || !(args[0] instanceof IBinder token))
                return;
            ActivityStatsHook.thawForToken(token, "返回 Activity 结果");
        } catch (Throwable ignored) {
        }
    }

    private static String resolvePackage(Intent intent) {
        if (intent == null)
            return null;

        if (intent.getComponent() != null && intent.getComponent().getPackageName() != null)
            return intent.getComponent().getPackageName();

        String packageName = intent.getPackage();
        if (packageName != null && !packageName.isEmpty())
            return packageName;

        // 隐式 Intent 只在文档选择场景做解析, 避免把每次普通启动都变成 PackageManager 查询.
        if (!isDocumentAction(intent.getAction()))
            return null;

        try {
            Context context = ActivityManagerService.getContext();
            if (context == null)
                return null;
            PackageManager packageManager = context.getPackageManager();
            if (packageManager == null)
                return null;
            ResolveInfo info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null)
                return info.activityInfo.packageName;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isDocumentAction(String action) {
        return Intent.ACTION_GET_CONTENT.equals(action)
                || Intent.ACTION_OPEN_DOCUMENT.equals(action)
                || Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)
                || Intent.ACTION_CREATE_DOCUMENT.equals(action)
                || Intent.ACTION_PICK.equals(action);
    }
}

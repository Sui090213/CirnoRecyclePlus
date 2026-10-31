package nep.timeline.cirno.hooks.android.broadcast;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.log.XposedLog;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.utils.PushUtils;

/**
 * 监听广播意图: 推送到达时先把目标应用解冻, 再让系统投递.
 *
 * <p>FCM 的链路是 GMS 发出 {@code com.google.android.c2dm.intent.RECEIVE} 广播, 系统再投递给
 * 目标应用. 如果目标应用被冻结, 广播会卡在冻结进程的 Binder 队列里, 表现为「推送收不到」.
 *
 * <p>v1.2.1 的加固:
 * <ul>
 *     <li>不再用硬编码的参数下标找 Intent, 而是遍历参数找 {@link Intent}: 不同 OEM 的
 *         {@code broadcastIntentLocked} 参数布局不一样 (ColorOS 16/17 就改过), 下标一错就
 *         完全解冻不了;</li>
 *     <li>除了 {@code BroadcastController.broadcastIntentLocked}, 再兜底 hook
 *         {@code ActivityManagerService} 的 {@code broadcastIntentWithFeature} /
 *         {@code broadcastIntent} 等 binder 入口, 防止 ROM 把内部方法改名 / 换类;</li>
 *     <li>只按包名解冻, 不依赖 userId 参数;</li>
 *     <li>推送类 action 统一由 {@link PushUtils} 判断, 同时覆盖 UnifiedPush / 厂商推送.</li>
 * </ul>
 */
public class BroadcastIntentHook {
    public BroadcastIntentHook(ClassLoader classLoader) {
        boolean hooked = hookBroadcastController(classLoader);
        hooked |= hookActivityManagerService(classLoader);

        if (hooked)
            Log.i("监听广播意图");
        else
            Log.e("无法监听广播意图!");
    }

    /** 第一优先级: 系统内部的 broadcastIntentLocked (所有广播都会经过这里). */
    private boolean hookBroadcastController(ClassLoader classLoader) {
        try {
            Class<?> clazz = CakeReflection.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Class<?> controller = CakeReflection.findClassIfExists("com.android.server.am.BroadcastController", classLoader);
                if (controller != null)
                    clazz = controller;
            }

            if (clazz == null)
                return false;

            Method targetMethod = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals("broadcastIntentLocked"))
                    continue;
                if (targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length)
                    targetMethod = method;
            }

            if (targetMethod == null)
                return false;

            CakeHooker.hookBefore(targetMethod, callback -> handle(callback.getArgs()));
            return true;
        } catch (Throwable throwable) {
            XposedLog.e("无法监听广播意图 (BroadcastController), 异常:", throwable);
            Log.e("监听广播意图失败 (BroadcastController)", throwable);
            return false;
        }
    }

    /**
     * 兜底: 直接 hook AMS 的 binder 入口.
     *
     * <p>GMS 的 FCM 广播走 {@code broadcastIntentWithFeature}; 某些 ROM 会把内部方法挪到别的类,
     * 但 binder 入口一般不变.
     */
    private boolean hookActivityManagerService(ClassLoader classLoader) {
        try {
            Class<?> clazz = CakeReflection.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
            if (clazz == null)
                return false;

            Set<String> names = new HashSet<>();
            names.add("broadcastIntentWithFeature");
            names.add("broadcastIntent");
            names.add("broadcastIntentWithCallback");

            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!names.contains(method.getName()))
                    continue;
                if (!hasIntentParameter(method))
                    continue;

                CakeHooker.hookBefore(method, callback -> handle(callback.getArgs()));
                count++;
            }

            if (count > 0)
                Log.i("广播 binder 入口兜底 Hook: " + count + " 个方法");
            return count > 0;
        } catch (Throwable throwable) {
            XposedLog.e("无法监听广播 binder 入口, 异常:", throwable);
            Log.e("监听广播 binder 入口失败", throwable);
            return false;
        }
    }

    private static boolean hasIntentParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(type))
                return true;
        }
        return false;
    }

    /** 广播参数里找 Intent, 找到推送 action 就按包名解冻. */
    private static void handle(Object[] args) {
        try {
            Intent intent = null;
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    intent = (Intent) arg;
                    break;
                }
            }

            if (intent == null)
                return;

            String action = intent.getAction();
            if (!PushUtils.isPushAction(action))
                return;

            String packageName = PushUtils.packageOf(intent);
            if (packageName == null)
                return;

            FreezerService.temporaryUnfreezeIfNeedByPackage(packageName, "MESSAGE PUSH", PushUtils.PUSH_HOLD_MS);
        } catch (Throwable ignored) {
            // Hook 回调里的异常绝对不能抛回 system_server.
        }
    }
}

package nep.timeline.cirno.hooks.android.activity;

import android.content.Intent;

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
 * 服务启动 / 绑定唤醒.
 *
 * <p>FCM 在部分版本的 GMS 上不是发广播, 而是直接 {@code startService}/{@code bindService}
 * 到应用的 {@code FirebaseMessagingService}; 其他推送通道也可能这样. 如果应用被冻结,
 * system_server 发出的服务启动 Binder 会排在冻结进程的队列里, 服务起不来.
 *
 * <p>这里 hook AMS 的服务入口, 在真正调度服务前先把目标应用解冻, 并给 10 秒窗口,
 * 窗口结束后再按正常逻辑冻结.
 */
public class ServiceStartHook {
    private static final long SERVICE_WAKE_HOLD_MS = PushUtils.PUSH_HOLD_MS;

    public ServiceStartHook(ClassLoader classLoader) {
        try {
            Class<?> clazz = CakeReflection.findClassIfExists("com.android.server.am.ActivityManagerService", classLoader);
            if (clazz == null) {
                Log.w("找不到 ActivityManagerService, 服务唤醒 Hook 未安装");
                return;
            }

            Set<String> names = new HashSet<>();
            names.add("startService");
            names.add("bindService");
            names.add("bindServiceInstance");

            int count = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!names.contains(method.getName()))
                    continue;
                if (!hasIntentParameter(method))
                    continue;

                CakeHooker.hookBefore(method, callback -> handle(callback.getArgs()));
                count++;
            }

            if (count == 0)
                Log.w("ActivityManagerService 没有找到带 Intent 的服务入口方法");
            else
                Log.i("服务启动唤醒 Hook 安装成功: " + count + " 个方法");
        } catch (Throwable throwable) {
            XposedLog.e("安装服务启动唤醒 Hook 失败, 异常:", throwable);
            Log.e("安装服务启动唤醒 Hook 失败", throwable);
        }
    }

    private static boolean hasIntentParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(type))
                return true;
        }
        return false;
    }

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

            String packageName = PushUtils.packageOf(intent);
            if (packageName == null || packageName.isEmpty())
                return;

            FreezerService.temporaryUnfreezeIfFrozenByPackage(packageName, "启动服务", SERVICE_WAKE_HOLD_MS);
        } catch (Throwable ignored) {
            // Hook 回调里的异常绝对不能抛回 system_server: 那会导致系统进程直接崩溃.
        }
    }
}

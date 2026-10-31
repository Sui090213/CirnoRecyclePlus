package nep.timeline.cirno.hooks.android.broadcast;

import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;

import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.log.XposedLog;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.services.ProcessService;
import nep.timeline.cirno.utils.PushUtils;
import nep.timeline.cirno.virtuals.ProcessRecord;

/**
 * Android 14+ 的广播投递拦截点.
 *
 * <p>系统在 {@code BroadcastSkipPolicy.shouldSkipMessage} 里决定「这条广播要不要跳过」.
 * 本模块原本在这里跳过所有发给冻结进程的广播, 用来省电; 但 FCM 推送也是广播, 一旦跳过,
 * 推送就永远不会到达.
 *
 * <p>v1.2.1:
 * <ul>
 *     <li>推送类广播**永不跳过**; 而且在投递前再解冻一次 (双保险, 防止前面的广播意图 Hook
 *         因为 ROM 改了参数而失效);</li>
 *     <li>同时适配 AOSP 14+/16 的 {@code shouldSkipMessage(BroadcastRecord, Object)} 与
 *         部分 ROM 的 {@code shouldSkipMessage(BroadcastRecord, BroadcastFilter)} 两种签名
 *         (原实现只匹配后者, 在 AOSP 16 上其实没装上);</li>
 *     <li>非推送广播仍然跳过冻结进程, 保持原来的省电行为.</li>
 * </ul>
 */
public class BroadcastSkipHook {
    public BroadcastSkipHook(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            return;

        try {
            Class<?> clazz = CakeReflection.findClassIfExists("com.android.server.am.BroadcastSkipPolicy", classLoader);
            if (clazz == null) {
                Log.w("找不到 BroadcastSkipPolicy, 跳过广播投递 Hook");
                return;
            }

            int hooked = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals("shouldSkipMessage"))
                    continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2)
                    continue;
                if (!params[0].getName().endsWith("BroadcastRecord"))
                    continue;

                CakeHooker.hookAfter(method, callback -> handle(callback));
                hooked++;
            }

            if (hooked == 0)
                Log.w("BroadcastSkipPolicy 没有找到 2 参数 shouldSkipMessage, 推送跳过保护未生效");
            else
                Log.i("广播跳过 Hook 安装成功: " + hooked + " 个方法");
        } catch (Throwable throwable) {
            XposedLog.e("安装广播跳过 Hook 失败, 异常:", throwable);
            Log.e("安装广播跳过 Hook 失败", throwable);
        }
    }

    private static void handle(CakeHooker.AfterHookCallback callback) {
        try {
            Object[] args = callback.getArgs();
            if (args.length < 2 || args[0] == null)
                return;

            Object record = args[0];
            Object target = args[1];

            Intent intent = null;
            try {
                Object field = CakeReflection.getObjectField(record, "intent");
                if (field instanceof Intent)
                    intent = (Intent) field;
            } catch (Throwable ignored) {
            }

            String action = intent == null ? null : intent.getAction();

            // 推送广播: 先解冻, 再取消「跳过」决定. 即使前面的 BroadcastIntentHook 没生效,
            // 这里也能保证推送被投递到目标应用.
            if (PushUtils.isPushAction(action)) {
                String packageName = PushUtils.packageOf(intent);
                if (packageName != null)
                    FreezerService.temporaryUnfreezeIfNeedByPackage(packageName, "MESSAGE PUSH", PushUtils.PUSH_HOLD_MS);

                if (callback.result != null) {
                    Log.d("放行推送广播: " + packageName + " (系统原跳过原因: " + callback.result + ")");
                    callback.result = null;
                }
                return;
            }

            if (callback.result != null)
                return;

            // 非推送: 沿用原逻辑, 冻结进程的广播不投递. 只处理 BroadcastFilter
            // (注册接收器); 静态接收器交给系统自己的策略.
            try {
                Object receiverList = CakeReflection.getObjectField(target, "receiverList");
                if (receiverList == null)
                    return;

                Object app = CakeReflection.getObjectField(receiverList, "app");
                if (app == null)
                    return;

                ProcessRecord processRecord = ProcessService.getProcessRecord(app);
                if (processRecord != null && processRecord.isFrozen())
                    callback.result = "Skipping deliver [Cirno Recycle+]: frozen process";
            } catch (Throwable ignored) {
                // target 不是 BroadcastFilter / 字段不存在: 保持系统原行为.
            }
        } catch (Throwable ignored) {
            // Hook 回调里的异常绝对不能抛回 system_server.
        }
    }
}

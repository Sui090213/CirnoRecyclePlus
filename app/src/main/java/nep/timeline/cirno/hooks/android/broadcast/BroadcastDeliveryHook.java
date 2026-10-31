package nep.timeline.cirno.hooks.android.broadcast;

import android.content.Intent;
import android.os.Build;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.services.ProcessService;
import nep.timeline.cirno.utils.PushUtils;
import nep.timeline.cirno.utils.SystemChecker;
import nep.timeline.cirno.virtuals.BroadcastRecord;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class BroadcastDeliveryHook extends MethodHook {
    public BroadcastDeliveryHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) ? "com.android.server.am.BroadcastQueueImpl" : "com.android.server.am.BroadcastQueue";
    }

    @Override
    public String getTargetMethod() {
        return "deliverToRegisteredReceiverLocked";
    }

    @Override
    public Object[] getTargetParam() {
        if (SystemChecker.isHuawei(classLoader))
            return new Object[] { "com.android.server.am.BroadcastRecord", "com.android.server.am.BroadcastFilter", boolean.class, int.class, "com.android.server.am.BroadcastRecordEx" };

        return new Object[] { "com.android.server.am.BroadcastRecord", "com.android.server.am.BroadcastFilter", boolean.class, int.class };
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                Object record = callback.getArgs()[0];
                if (record == null)
                    return;

                // 推送广播永远不能跳过: 先解冻目标应用, 再让系统正常投递.
                try {
                    Object intentField = CakeReflection.getObjectField(record, "intent");
                    if (intentField instanceof Intent) {
                        Intent intent = (Intent) intentField;
                        if (PushUtils.isPushAction(intent.getAction())) {
                            String packageName = PushUtils.packageOf(intent);
                            if (packageName != null)
                                FreezerService.temporaryUnfreezeIfNeedByPackage(packageName, "MESSAGE PUSH", PushUtils.PUSH_HOLD_MS);
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                    // 字段不存在时继续走原来的跳过逻辑.
                }

                BroadcastRecord broadcastRecord = new BroadcastRecord(record);

                Object filter = callback.getArgs()[1];
                if (filter == null)
                    return;

                Object receiver = CakeReflection.getObjectField(filter, "receiverList");
                if (receiver == null)
                    return;

                Object app = CakeReflection.getObjectField(receiver, "app");
                if (app == null)
                    return;

                ProcessRecord processRecord = ProcessService.getProcessRecord(app);
                if (processRecord == null)
                    return;

                if (processRecord.isFrozen()) {
                    broadcastRecord.skippedDelivery((int) callback.getArgs()[3]);
                    callback.returnAndSkip(null);
                }
            }
        };
    }
}

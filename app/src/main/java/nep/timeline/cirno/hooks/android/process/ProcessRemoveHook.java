package nep.timeline.cirno.hooks.android.process;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.services.ProcessService;

public class ProcessRemoveHook extends MethodHook {
    public ProcessRemoveHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.am.ProcessList";
    }

    @Override
    public String getTargetMethod() {
        return "removeProcessNameLocked";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[] { String.class, int.class, "com.android.server.am.ProcessRecord" };
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                // 用 AMS 传进来的 ProcessRecord 原始对象做身份校验: 同 name/uid 的新进程
                // 可能已经注册, 只按 name+uid 删除会误删新记录并解冻新 pid.
                ProcessService.removeProcessRecordIfCurrent(callback.getArgs()[2]);
            }
        };
    }
}
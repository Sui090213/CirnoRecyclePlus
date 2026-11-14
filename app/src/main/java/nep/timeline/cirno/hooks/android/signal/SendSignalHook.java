package nep.timeline.cirno.hooks.android.signal;

import android.os.Process;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.services.ProcessService;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class SendSignalHook extends MethodHook {
    public SendSignalHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return Process.class.getTypeName();
    }

    @Override
    public String getTargetMethod() {
        return "sendSignal";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[] { int.class, int.class };
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                int pid = (int) callback.getArgs()[0];
                int signal = (int) callback.getArgs()[1];
                if (signal != Process.SIGNAL_KILL)
                    return;

                ProcessRecord processRecord = ProcessService.getProcessRecordByPid(pid);
                if (processRecord == null || processRecord.isDeathProcess())
                    return;

                // 正在被 SIGKILL 的进程已经要死了: 这里只做记录清理 + 幂等写 freeze=0,
                // 不能走普通 remove 路径打日志/重新安排冻结.
                ProcessService.removeProcessRecordWithoutThawIfCurrent(processRecord, "SIGKILL");
            }
        };
    }
}

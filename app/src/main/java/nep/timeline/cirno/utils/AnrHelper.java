package nep.timeline.cirno.utils;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.services.ProcessService;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class AnrHelper {
    public static boolean blockANR(Object app) {
        if (app == null)
            return false;
        ProcessRecord processRecord = ProcessService.getProcessRecord(app);
        if (processRecord == null)
            return false;
        AppRecord appRecord = processRecord.getAppRecord();
        if (appRecord == null)
            return false;

        // 只吞掉「确实被我们冻结」的进程的 ANR. 以前对所有第三方 App 都返回 true,
        // 文件管理器/provider 被冻住卡死时连系统 ANR 提示都没有, 用户只看到纯卡死.
        return appRecord.isFrozen() || FreezerService.hasFrozenProcess(appRecord);
    }
}

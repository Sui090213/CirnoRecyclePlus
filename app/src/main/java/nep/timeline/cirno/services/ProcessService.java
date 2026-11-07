package nep.timeline.cirno.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.utils.FrozenRW;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class ProcessService {
    private static final Map<String, Map<Integer, ProcessRecord>> PROCESS_NAME_MAP = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    public static void addProcessRecord(Object record) {
        ProcessRecord processRecord = new ProcessRecord(record);
        AppRecord appRecord = processRecord.getAppRecord();
        if (appRecord == null)
            return;

        synchronized (lock) {
            PROCESS_NAME_MAP.computeIfAbsent(processRecord.getProcessName(), k -> new ConcurrentHashMap<>()).put(processRecord.getRunningUid(), processRecord);
            appRecord.getProcessRecords().add(processRecord);
        }

        // 新进程可能正是被 Activity 启动起来的. 3 秒太短, 冷启动 / 低内存时 Activity
        // 还没 RESUMED 就被冻住, 会直接卡在启动窗口. 给 15 秒让 Activity/Provider 先就绪;
        // 真正退到后台时 ActivityStatsHook 还会重新安排一次冻结.
        FreezerHandler.sendFreezeMessage(appRecord, 15_000);
    }

    public static void removeProcessRecord(ProcessRecord processRecord) {
        removeProcessRecord(processRecord.getProcessName(), processRecord.getRunningUid());
    }

    public static void removeProcessRecord(String name, int uid) {
        synchronized (lock) {
            if (PROCESS_NAME_MAP.containsKey(name)) {
                ProcessRecord processRecord = PROCESS_NAME_MAP.computeIfAbsent(name, k -> new ConcurrentHashMap<>()).remove(uid);
                if (processRecord == null)
                    return;
                if (processRecord.isFrozen())
                    FrozenRW.thaw(processRecord.getRunningUid(), processRecord.getPid());
                AppRecord appRecord = processRecord.getAppRecord();
                if (appRecord == null)
                    return;
                appRecord.getProcessRecords().remove(processRecord);
                if (appRecord.getProcessRecords().isEmpty())
                    appRecord.reset();
            }
        }
    }

    public static ProcessRecord getProcessRecord(Object record) {
        if (record == null)
            return null;
        ProcessRecord processRecord = new ProcessRecord(record);
        return getProcessRecord(processRecord.getProcessName(), processRecord.getRunningUid());
    }

    public static ProcessRecord getProcessRecord(String processName, int uid) {
        if (processName == null || processName.isEmpty())
            return null;
        Map<Integer, ProcessRecord> map = PROCESS_NAME_MAP.get(processName);
        if (map == null)
            return null;
        return map.get(uid);
    }

    public static ProcessRecord getProcessRecordByPid(int pid) {
        ProcessRecord processRecord;
        Object mPidsSelfLocked = ActivityManagerService.getPidsSelfLocked();
        synchronized (mPidsSelfLocked) {
            processRecord = getProcessRecord(CakeReflection.callMethod(mPidsSelfLocked, "get", pid));
        }
        return processRecord;
    }
}

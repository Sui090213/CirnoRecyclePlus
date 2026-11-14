package nep.timeline.cirno.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.utils.FrozenRW;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class ProcessService {
    private static final Map<String, Map<Integer, ProcessRecord>> PROCESS_NAME_MAP = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    public static ProcessRecord addProcessRecord(Object record) {
        return addProcessRecord(record, true);
    }

    private static ProcessRecord addProcessRecord(Object record, boolean scheduleFreeze) {
        if (record == null)
            return null;

        ProcessRecord processRecord = new ProcessRecord(record);
        AppRecord appRecord = processRecord.getAppRecord();
        if (appRecord == null)
            return null;

        ProcessRecord oldRecord;
        synchronized (lock) {
            Map<Integer, ProcessRecord> records = PROCESS_NAME_MAP.computeIfAbsent(
                    processRecord.getProcessName(), k -> new ConcurrentHashMap<>());
            oldRecord = records.put(processRecord.getRunningUid(), processRecord);
            if (oldRecord != null) {
                AppRecord oldAppRecord = oldRecord.getAppRecord();
                if (oldAppRecord != null)
                    oldAppRecord.getProcessRecords().remove(oldRecord);
            }
            appRecord.getProcessRecords().add(processRecord);
        }

        // 同名同 uid 的进程被新 pid 替换 (进程死亡后立刻重启) 时, 旧记录可能还带着「已冻结」标记.
        // 旧 pid 已经被内核回收, 甚至可能复用给其它进程; 必须把旧 pid 解冻掉, 否则会误伤新进程.
        if (oldRecord != null && oldRecord.isFrozen()) {
            int oldPid = oldRecord.getPid();
            if (oldPid > 0 && oldPid != processRecord.getPid()) {
                FrozenRW.thaw(oldRecord.getRunningUid(), oldPid);
                Log.d(oldRecord.getPackageName() + " 旧进程 pid=" + oldPid + " 已被替换, 解冻旧记录");
            }
        }

        // 新进程可能正是被 Activity 启动起来的. 3 秒太短, 冷启动 / 低内存时 Activity
        // 还没 RESUMED 就被冻住, 会直接卡在启动窗口. 给 15 秒让 Activity/Provider 先就绪;
        // 真正退到后台时 ActivityStatsHook 还会重新安排一次冻结.
        if (scheduleFreeze)
            FreezerHandler.sendFreezeMessage(appRecord, 15_000);

        return processRecord;
    }

    public static void removeProcessRecord(ProcessRecord processRecord) {
        removeProcessRecord(processRecord == null ? null : processRecord.getProcessName(),
                processRecord == null ? -1 : processRecord.getRunningUid(), null, null, null);
    }

    public static void removeProcessRecord(String name, int uid) {
        removeProcessRecord(name, uid, null, null, null);
    }

    /**
     * 进程死亡 / 被杀路径: 仍然写一次 cgroup.freeze=0, 但不打「取消管理」日志.
     *
     * <p>写 0 是幂等的: 进程已经死了就是 no-op; 但如果在 AMS 移除记录之后才写, 就可能把
     * 复用同一个 pid 的新进程错误解冻, 所以必须在 AMS 还持有 ProcessRecord 时执行 (BeforeHook).
     */
    public static void removeProcessRecordWithoutThaw(ProcessRecord processRecord, String path) {
        removeProcessRecord(processRecord == null ? null : processRecord.getProcessName(),
                processRecord == null ? -1 : processRecord.getRunningUid(), path, null, null);
    }

    /** 只移除「仍是当前记录」的那一条, 防止竞态下删掉同名新进程. */
    public static void removeProcessRecordWithoutThawIfCurrent(ProcessRecord processRecord, String path) {
        removeProcessRecord(processRecord == null ? null : processRecord.getProcessName(),
                processRecord == null ? -1 : processRecord.getRunningUid(), path, processRecord, null);
    }

    /**
     * 按 AMS 的 ProcessRecord 原始对象移除记录 (ProcessList.removeProcessNameLocked 的 args[2]).
     *
     * <p>用底层实例做身份校验: 同 name/uid 的新进程可能已经注册, 只按 name/uid 删除会把新记录
     * 误删并解冻新 pid.
     */
    public static void removeProcessRecordIfCurrent(Object systemProcessRecord) {
        if (systemProcessRecord == null)
            return;
        ProcessRecord processRecord;
        try {
            processRecord = new ProcessRecord(systemProcessRecord);
        } catch (Throwable throwable) {
            return;
        }
        removeProcessRecord(processRecord.getProcessName(), processRecord.getRunningUid(),
                null, null, systemProcessRecord);
    }

    private static AppRecord removeProcessRecord(String name, int uid, String path,
                                                 ProcessRecord expected,
                                                 Object expectedSystemRecord) {
        if (name == null || uid < 0)
            return null;

        ProcessRecord processRecord;
        AppRecord appRecord;
        int thawPid;
        String processName;
        boolean appEmpty = false;
        synchronized (lock) {
            Map<Integer, ProcessRecord> records = PROCESS_NAME_MAP.get(name);
            if (records == null)
                return null;
            processRecord = records.remove(uid);
            if (processRecord == null)
                return null;
            if (expected != null && processRecord != expected) {
                // 同名新进程已经注册, 不能把新记录删掉; 把旧记录放回也不会被使用, 直接放弃本次删除.
                records.put(uid, processRecord);
                return null;
            }
            if (expectedSystemRecord != null && processRecord.getInstance() != expectedSystemRecord) {
                records.put(uid, processRecord);
                return null;
            }
            if (records.isEmpty())
                PROCESS_NAME_MAP.remove(name, records);

            thawPid = processRecord.getPid();
            processName = processRecord.getProcessName();
            appRecord = processRecord.getAppRecord();
            if (appRecord != null) {
                appRecord.getProcessRecords().remove(processRecord);
                if (appRecord.getProcessRecords().isEmpty()) {
                    // 先不 reset: 还要根据 reportedFrozen 给系统补一次 unfrozen 上报,
                    // reset 会把 reportedFrozen 清掉, 系统会一直以为这个 uid 还冻着.
                    appEmpty = true;
                } else {
                    appRecord.setFrozen(hasFrozenProcess(appRecord));
                }
            }
        }

        // cgroup 读写放到锁外: PROCESS_NAME_MAP 的锁会被 ProcessAdd/Remove 热路径使用,
        // 在里面做文件 I/O 会阻塞 AMS 的进程管理线程.
        if (thawPid > 0)
            FrozenRW.thaw(uid, thawPid);

        if (path != null) {
            String label = appRecord == null || appRecord.getPackageName() == null
                    ? name : appRecord.getPackageNameWithUser();
            Log.d(label + " 进程 " + processName + "(pid=" + thawPid + ") 被取消管理, 路径: " + path);
        }

        // 进程自然退出 / 被杀后重新对齐「整应用冻结标记 + 系统上报状态」:
        // 否则最后一个冻结进程死亡时 reportedFrozen 会一直为 true, 下次冻结的上报会被去重逻辑跳过,
        // frozenSinceMs 也会停留在旧值 (深度回收会用到它).
        if (appRecord != null) {
            try {
                FreezerService.updateReportedState(appRecord);
            } catch (Throwable throwable) {
                Log.w("更新应用冻结状态失败: " + throwable);
            }
            if (appEmpty)
                appRecord.reset();
        }

        return appRecord;
    }

    private static boolean hasFrozenProcess(AppRecord appRecord) {
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord != null && !processRecord.isDeathProcess() && processRecord.isFrozen())
                return true;
        }
        return false;
    }

    public static ProcessRecord getProcessRecord(Object record) {
        if (record == null)
            return null;
        // 广播分发等热路径高频调用: 直接读两个字段做 map 查询, 不构造完整 ProcessRecord 包装.
        try {
            String processName = (String) CakeReflection.getObjectField(record, "processName");
            int uid = CakeReflection.getIntField(record, "uid");
            return getProcessRecord(processName, uid);
        } catch (Throwable ignored) {
            // 字段名在个别 ROM 上可能不同: 退回完整包装 (更慢, 但至少不丢事件).
        }
        try {
            ProcessRecord processRecord = new ProcessRecord(record);
            return getProcessRecord(processRecord.getProcessName(), processRecord.getRunningUid());
        } catch (Throwable ignored) {
            return null;
        }
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
        if (mPidsSelfLocked == null)
            return null;
        synchronized (mPidsSelfLocked) {
            processRecord = getProcessRecord(CakeReflection.callMethod(mPidsSelfLocked, "get", pid));
        }
        return processRecord;
    }
}

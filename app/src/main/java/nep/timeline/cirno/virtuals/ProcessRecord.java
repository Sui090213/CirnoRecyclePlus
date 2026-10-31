package nep.timeline.cirno.virtuals;

import android.content.pm.ApplicationInfo;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.utils.ProcessStat;

@Getter
public class ProcessRecord {
    private final Object instance;
    private final int userId;
    private final int runningUid;
    private final ApplicationInfo applicationInfo;
    private final int uid;
    private final String packageName;
    private final String processName;
    @Getter(AccessLevel.NONE)
    private AppRecord appRecord;
    /**
     * 进程冻结状态: 会被 Freezer 线程 / 手动操作线程写, 被状态服务 (UI 显示) 和内存回收线程读,
     * 必须 volatile, 否则界面可能显示过期状态.
     */
    @Setter
    private volatile boolean frozen;
    /**
     * 是否是通过「手动冻结」冻上的 (而不是自动冻结).
     *
     * <p>白名单应用整应用不参与自动冻结, 但用户手动冻掉的子进程要保留;
     * reconcile 清理白名单应用里「自动冻结」残留时靠这个标记区分, 不能把手动冻结的也解掉.
     */
    @Setter
    private volatile boolean manuallyFrozen;
    /** 进程启动时间缓存: -2 表示还没读过; 同一个进程生命周期内不会变, 读一次就够. */
    @Getter(AccessLevel.NONE)
    private volatile long startTimeCache = -2L;

    public ProcessRecord(Object instance) {
        this.instance = instance;
        this.userId = CakeReflection.getIntField(instance, "userId");
        this.runningUid = CakeReflection.getIntField(instance, "uid");
        this.applicationInfo = (ApplicationInfo) CakeReflection.getObjectField(instance, "info");
        this.uid = applicationInfo.uid;
        this.packageName = applicationInfo.packageName;
        this.processName = (String) CakeReflection.getObjectField(instance, "processName");
        this.appRecord = AppService.get(packageName, userId);
    }

    public int getPid() {
        return (int) CakeReflection.getObjectField(instance, "mPid");
    }

    /**
     * 进程启动时间 (/proc/&lt;pid&gt;/stat 第 22 列, 时钟滴答数).
     *
     * <p>用于防止 pid 被系统复用后手动冻结错进程; 进程生命周期内不变, 所以缓存起来,
     * 避免状态服务每 2 秒对所有进程都读一次 /proc.
     */
    public long getStartTime() {
        long cached = startTimeCache;
        if (cached == -2L) {
            cached = ProcessStat.getStartTime(getPid());
            startTimeCache = cached;
        }
        return cached;
    }

    public boolean isDeathProcess() {
        return getPid() <= 0;
    }

    public AppRecord getAppRecord() {
        if (appRecord == null)
            appRecord = AppService.get(packageName, userId);
        return appRecord;
    }
}

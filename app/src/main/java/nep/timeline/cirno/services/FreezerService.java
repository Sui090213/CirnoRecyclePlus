package nep.timeline.cirno.services;

import android.os.Build;

import java.util.List;

import nep.timeline.cirno.configs.checkers.AppConfigs;
import nep.timeline.cirno.configs.settings.AppOptions;
import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.entity.AppState;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.ForceAppStandbyListener;
import nep.timeline.cirno.utils.FrozenRW;
import nep.timeline.cirno.virtuals.ProcessRecord;

public class FreezerService {
    /**
     * 冻结 / 解冻的串行锁.
     *
     * <p>自动冻结 (FreezerHandler 线程)、Binder / 广播唤醒触发的临时解冻 (各 Hook 线程)、
     * 手动冻结 (运行时状态线程) 可能同时操作同一个应用的 cgroup 状态; 不加锁会出现
     * 「进程级 frozen 标记」和「cgroup.freeze 实际值」对不上, 以及重复断网 / 移闹钟.
     */
    private static final Object LOCK = new Object();

    /**
     * 冻结一个应用 (自动冻结入口, 由 FreezerHandler 在应用退到后台一段时间后调用).
     *
     * <p>与上游 Cirno 的区别:
     * <ul>
     *     <li>应用级选项: 可以整个应用「不冻结」, 可以「后台播放音频时不冻结」;</li>
     *     <li>进程级规则: 可以只冻结部分进程 (例如只冻结推送进程, 保留主进程不冻);</li>
     *     <li>冻结时可以选择保留网络连接 / 闹钟.</li>
     * </ul>
     *
     * <p>只有全部存活进程都冻结成功时, 才把应用标记为 frozen 并通知系统
     * (reportOneUidFrozenStateChanged / socketDestroy); 只冻结了部分进程时只记录
     * 进程级冻结标记, 避免系统以为整个 uid 被冻结后做出错误调度.
     */
    public static void freezer(AppRecord appRecord) {
        synchronized (LOCK) {
            freezerLocked(appRecord);
        }
    }

    private static void freezerLocked(AppRecord appRecord) {
        if (appRecord == null || appRecord.isSystem())
            return;

        AppState appState = appRecord.getAppState();
        if (appState == null)
            return;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        // 应用级总开关: 长按面板里关掉「参与自动冻结」后, 这个应用永远不冻结.
        if (!options.enabled)
            return;

        if (appState.isVisible() || appState.isLocation() || appState.isRecording() || appState.isVpn())
            return;
        // 正在后台播放音频: 默认跳过 (关掉该应用的「播放音频时不冻结」后才会冻).
        if (appState.isAudio() && options.keepAudio)
            return;

        // 先统计存活 / 已冻结进程. 注意不能像上游那样直接 `if (appRecord.isFrozen()) return`:
        // 应用整体冻结后如果又启动了新进程, 新进程需要按规则补冻, 否则会一直漏在外面.
        int liveProcesses = 0;
        int frozenProcesses = 0;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            liveProcesses++;
            if (processRecord.isFrozen())
                frozenProcesses++;
        }

        // 连一个存活进程都没有: 不标记冻结, 等应用真的启动进程后 ProcessAddHook 会再次触发.
        if (liveProcesses == 0) {
            appRecord.setFrozen(false);
            return;
        }
        // 已经全部冻结: 不重复冻结, 但「通知系统」这一步可能还没做过 (例如最后一个未冻结进程
        // 自然退出 / 手动只冻结了最后一个进程), 这里补一次, 保证系统状态和实际一致.
        if (frozenProcesses >= liveProcesses) {
            appRecord.setFrozen(true);
            notifyFrozenLocked(appRecord);
            return;
        }

        boolean anyFrozen = frozenProcesses > 0;
        boolean allFrozen = true;

        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            if (processRecord.isFrozen())
                continue;

            if (!AppConfigs.shouldFreezeProcess(options, processRecord.getProcessName())) {
                allFrozen = false;
                continue;
            }

            if (FrozenRW.frozen(processRecord.getRunningUid(), processRecord.getPid())) {
                processRecord.setFrozen(true);
                anyFrozen = true;
            } else {
                allFrozen = false;
            }
        }

        // 一个进程都没冻上: 什么都不做, 避免把应用误标成「已冻结」.
        if (!anyFrozen)
            return;

        if (!allFrozen) {
            // 部分冻结 (进程规则排除了部分进程, 或某些进程写 cgroup.freeze 失败):
            // 不通知系统整个 uid 冻结, 也不动闹钟 / 网络, 只记录进程级冻结标记.
            Log.d(appRecord.getPackageNameWithUser() + " 已按进程规则部分冻结");
            syncReportedStateLocked(appRecord);
            return;
        }

        appRecord.setFrozen(true);
        notifyFrozenLocked(appRecord);
    }

    /**
     * 重新计算「整应用冻结标记 + 系统上报状态」.
     *
     * <p>进程级手动冻结 / 解冻、进程规则变化之后都要调用: 既可能从「部分冻结」变成「全部冻结」
     * (需要补上报), 也可能从「全部冻结」变成「部分冻结」(需要补上报 unfrozen), 否则系统侧状态会一直错下去.
     */
    public static void updateReportedState(AppRecord appRecord) {
        synchronized (LOCK) {
            syncReportedStateLocked(appRecord);
        }
    }

    private static void syncReportedStateLocked(AppRecord appRecord) {
        if (appRecord == null)
            return;
        updateFrozenFlagLocked(appRecord);
        if (appRecord.isFrozen())
            notifyFrozenLocked(appRecord);
        else if (appRecord.isReportedFrozen())
            reportUnfrozenLocked(appRecord);
    }

    /** 上报「uid 已解冻」(只有之前真的上报过 frozen 才需要). */
    private static void reportUnfrozenLocked(AppRecord appRecord) {
        if (appRecord == null || !appRecord.isReportedFrozen())
            return;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM)
            Handlers.network.post(() -> CachedAppOptimizer.reportOneUidFrozenStateChanged(appRecord.getUid(), false));
        appRecord.setReportedFrozen(false);
    }

    /**
     * 第一次「全部进程都冻结」时通知系统: 移除闹钟、断网 / 上报 uid 冻结.
     *
     * <p>用 {@link AppRecord#isReportedFrozen()} 去重: 重复上报会让系统按错误的时机做调度,
     * 也会让日志被重复的 socketDestroy / removeAlarms 刷屏.
     */
    private static void notifyFrozenLocked(AppRecord appRecord) {
        if (appRecord == null || appRecord.isReportedFrozen())
            return;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        if (!options.keepAlarms)
            Handlers.alarms.post(() -> ForceAppStandbyListener.removeAlarmsForUid(appRecord));

        Handlers.network.post(() -> {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                CachedAppOptimizer.reportOneUidFrozenStateChanged(appRecord.getUid(), true);
                return;
            }
            // 保留网络连接时跳过 socketDestroy (只对 Android 15 及以下的实现有影响).
            if (!options.keepNetwork)
                NetworkManagementService.socketDestroy(appRecord);
        });

        appRecord.setReportedFrozen(true);
    }

    /**
     * 重新计算应用的整应用冻结标记: 只有「存在存活进程且全部冻结」才算冻结.
     *
     * <p>进程级手动冻结 / 解冻后也要调用它, 避免应用状态和进程状态对不上.
     */
    public static void updateFrozenFlag(AppRecord appRecord) {
        synchronized (LOCK) {
            updateFrozenFlagLocked(appRecord);
        }
    }

    private static void updateFrozenFlagLocked(AppRecord appRecord) {
        if (appRecord == null)
            return;

        int liveProcesses = 0;
        int frozenProcesses = 0;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            liveProcesses++;
            if (processRecord.isFrozen())
                frozenProcesses++;
        }

        appRecord.setFrozen(liveProcesses > 0 && frozenProcesses >= liveProcesses);
    }

    /**
     * 解冻一个应用 (会解冻它所有被冻结的进程).
     *
     * @return 是否真的有进程被解冻 / 应用原来是冻结状态
     */
    public static boolean thaw(AppRecord appRecord) {
        synchronized (LOCK) {
            return thawLocked(appRecord);
        }
    }

    private static boolean thawLocked(AppRecord appRecord) {
        if (appRecord == null)
            return false;

        FreezerHandler.removeAppMessage(appRecord);

        boolean wasFrozen = appRecord.isFrozen();
        boolean reported = appRecord.isReportedFrozen();
        boolean anyThawed = false;

        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
                continue;

            FrozenRW.thaw(processRecord.getRunningUid(), processRecord.getPid());
            processRecord.setFrozen(false);
            anyThawed = true;
        }

        if (!wasFrozen && !anyThawed && !reported)
            return false;

        // 只有真正上报过「uid 冻结」才需要上报解冻 (部分冻结从来没上报过).
        reportUnfrozenLocked(appRecord);
        appRecord.setFrozen(false);
        return true;
    }

    /** 手动冻结单个进程 (调用方负责更新整应用冻结标记). */
    public static boolean freezeProcess(ProcessRecord processRecord) {
        synchronized (LOCK) {
            if (processRecord == null || processRecord.isDeathProcess() || processRecord.isFrozen())
                return false;
            if (!FrozenRW.frozen(processRecord.getRunningUid(), processRecord.getPid()))
                return false;
            processRecord.setFrozen(true);
            return true;
        }
    }

    /** 手动解冻单个进程 (调用方负责更新整应用冻结标记). */
    public static boolean thawProcess(ProcessRecord processRecord) {
        synchronized (LOCK) {
            if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
                return false;
            FrozenRW.thaw(processRecord.getRunningUid(), processRecord.getPid());
            processRecord.setFrozen(false);
            return true;
        }
    }

    /**
     * 配置变化后的对齐 (每 2 秒由 RuntimeStateService 调用一次):
     *
     * <ul>
     *     <li>应用被设为「不冻结」/ 加入白名单 / 变成系统应用: 解冻它已经冻结的进程,
     *         否则这些进程会一直冻着 (Binder 唤醒也被拦截), 表现为应用打不开 / 推送收不到;</li>
     *     <li>进程规则从「全部」改成「只冻选中 / 排除选中」: 把不再符合规则的已冻结进程解冻;</li>
     *     <li>反过来的情况 (规则变宽 / 进程自然退出导致已经全冻结) 补一次系统通知.</li>
     * </ul>
     */
    public static void reconcile(AppRecord appRecord) {
        synchronized (LOCK) {
            reconcileLocked(appRecord);
        }
    }

    private static void reconcileLocked(AppRecord appRecord) {
        if (appRecord == null || appRecord.getPackageName() == null)
            return;

        boolean hasFrozenProcess = false;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord != null && !processRecord.isDeathProcess() && processRecord.isFrozen()) {
                hasFrozenProcess = true;
                break;
            }
        }

        boolean blocked = appRecord.isSystem()
                || !AppConfigs.isFreezeEnabled(appRecord.getPackageName(), appRecord.getUserId());
        if (blocked) {
            if (hasFrozenProcess || appRecord.isFrozen() || appRecord.isReportedFrozen()) {
                Log.i(appRecord.getPackageNameWithUser() + " 已设置为不冻结 / 白名单, 解冻已冻结进程");
                thawLocked(appRecord);
            }
            return;
        }

        if (!hasFrozenProcess) {
            if (appRecord.isFrozen())
                updateFrozenFlagLocked(appRecord);
            return;
        }

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
                continue;
            if (AppConfigs.shouldFreezeProcess(options, processRecord.getProcessName()))
                continue;

            FrozenRW.thaw(processRecord.getRunningUid(), processRecord.getPid());
            processRecord.setFrozen(false);
            Log.i(appRecord.getPackageNameWithUser() + " 进程规则已排除 " + processRecord.getProcessName() + ", 解冻");
        }

        // 重新对齐「整应用冻结标记 + 系统上报状态」: 处理进程死亡 / 手动操作导致的
        // 「全冻结但没上报」和「不再全冻结但已上报」两种情况.
        syncReportedStateLocked(appRecord);
    }

    public static void temporaryUnfreezeIfNeed(int uid, String reason, long interval) {
        List<AppRecord> appRecords = AppService.getByUid(uid);

        if (appRecords == null || appRecords.isEmpty())
            return;

        for (AppRecord appRecord : appRecords) {
            if (appRecord == null)
                continue;

            temporaryUnfreezeIfNeed(appRecord, reason, interval);
        }
    }

    public static void temporaryUnfreezeIfNeed(String packageName, int userId, String reason, long interval) {
        temporaryUnfreezeIfNeed(AppService.get(packageName, userId), reason, interval);
    }

    /**
     * 应用被外部事件 (Binder / 广播 / 媒体按键等) 唤醒时的临时解冻.
     *
     * <p>关键点: **解冻这一步永远要做**, 只有「解冻后再重新安排冻结」受应用选项控制.
     * 否则用户关掉某个应用的「参与自动冻结」后, 该应用仍然冻着, 而所有唤醒路径都被跳过,
     * 在依赖 Binder 解冻的 ROM (小米 Greeze / OPlus Hans / ReKernel) 上会表现为打不开 / ANR.
     */
    public static void temporaryUnfreezeIfNeed(AppRecord appRecord, String reason, long interval) {
        if (appRecord == null || appRecord.isSystem())
            return;

        synchronized (LOCK) {
            if (appRecord.isFrozen())
                Log.i(appRecord.getPackageNameWithUser() + " " + reason);

            // 部分冻结 / 只是有零散冻结进程时也要解冻, 保证唤醒事件不会被冻住的进程挡住.
            thawLocked(appRecord);

            // 只有还允许自动冻结时才重新安排冻结.
            if (AppConfigs.isFreezeEnabled(appRecord.getPackageName(), appRecord.getUserId()))
                FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, interval);
        }
    }
}

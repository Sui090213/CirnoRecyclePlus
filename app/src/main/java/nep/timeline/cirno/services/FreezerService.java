package nep.timeline.cirno.services;

import android.os.Build;
import android.os.SystemClock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.CommonConstants;
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

    /** 配置对齐时补发自动冻结调度的最小间隔. */
    private static final long AUTO_FREEZE_KICK_INTERVAL_MS = 30_000L;

    /**
     * 推送唤醒后的「免冻结窗口」: packageName -> 截止时间 (elapsedRealtime).
     *
     * <p>FCM / 厂商推送广播到达时目标应用可能还没创建进程，此时拿不到 AppRecord 来安排
     * 重新冻结；用包名记一个窗口，让 ProcessAddHook 3 秒后触发的冻结在窗口内直接跳过，
     * 窗口结束后再冻结。否则「推送刚拉起进程，3 秒后就被冻住」会表现为收不到推送.
     */
    private static final Map<String, Long> FREEZE_HOLD_UNTIL_MS = new ConcurrentHashMap<>();

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
        if (appRecord == null || appRecord.isProtected())
            return;

        // v1.2.3: 白名单是绝对保护, 主进程和所有子进程都不允许自动冻结.
        // 以前只有「白名单 + 全部进程」才整应用跳过; 使用「排除选中」时子进程仍会被冻,
        // 悬浮窗跑在子进程里时就会表现为「正常几秒后悬浮窗不动」.
        if (appRecord.isWhiteListed())
            return;

        // 推送窗口内不冻结: 重新安排一次「窗口结束后的冻结」.
        long holdRemaining = freezeHoldRemaining(appRecord.getPackageName());
        if (holdRemaining > 0) {
            FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, holdRemaining + 200);
            return;
        }

        AppState appState = appRecord.getAppState();
        if (appState == null)
            return;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        // 应用级总开关: 长按面板里关掉「参与自动冻结」后, 这个应用永远不冻结.
        if (!options.enabled)
            return;

        // 用户显式允许冻结 Google 服务时, 跳过定位 / 录音 / VPN 保护; 但不跳过可见性判断.
        boolean forceGoogle = options.allowFreezeGoogle
                && CommonConstants.isGoogleFamily(appRecord.getPackageName());

        if (appState.isVisible())
            return;
        if (!forceGoogle && (appState.isLocation() || appState.isRecording() || appState.isVpn()))
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
            appRecord.clearFrozenSince();
            return;
        }
        // 已经全部冻结: 不重复冻结, 但「通知系统」这一步可能还没做过 (例如最后一个未冻结进程
        // 自然退出 / 手动只冻结了最后一个进程), 这里补一次, 保证系统状态和实际一致.
        if (frozenProcesses >= liveProcesses) {
            appRecord.setFrozen(true);
            appRecord.markFrozenNow();
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

            if (!shouldFreezeProcessLocked(appRecord, options, processRecord.getProcessName())) {
                allFrozen = false;
                continue;
            }

            if (FrozenRW.frozen(processRecord.getRunningUid(), processRecord.getPid())) {
                processRecord.setFrozen(true);
                processRecord.setManuallyFrozen(false);
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
        appRecord.markFrozenNow();
        notifyFrozenLocked(appRecord);
    }

    /**
     * 判断某个进程是否允许被冻结 (自动 / 手动共用).
     *
     * <p>用户白名单应用只允许冻结子进程: 主进程一旦被冻住, 白名单应用会跳过 Binder
     * 临时解冻逻辑, 应用会直接无响应, 所以主进程永远排除在冻结之外.
     */
    public static boolean shouldFreezeProcess(AppRecord appRecord, AppOptions options, String processName) {
        // v1.2.3: 白名单是绝对保护, 任何进程都不允许冻.
        if (appRecord != null && appRecord.isWhiteListed())
            return false;
        return AppConfigs.shouldFreezeProcess(options, processName);
    }

    private static boolean shouldFreezeProcessLocked(AppRecord appRecord, AppOptions options, String processName) {
        return shouldFreezeProcess(appRecord, options, processName);
    }

    /** 进程名是否等于应用的主进程名 (Android 主进程名就是包名). */
    public static boolean isMainProcess(AppRecord appRecord, String processName) {
        return appRecord != null && (processName == null || processName.equals(appRecord.getPackageName()));
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

        // 白名单应用只做进程级部分冻结, 不上报「整个 uid 冻结」, 也不断网 / 移闹钟:
        // 它整体仍被用户视为「不冻结」, 上报会让系统按整个应用冻结去调度.
        // 注意不要把 reportedFrozen 清成 false: 如果它之前 (白名单之前) 真的上报过,
        // 这里清掉标记会导致以后解冻时不再上报 unfrozen, 系统侧状态会一直错着.
        if (appRecord.isWhiteListed())
            return;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        if (!options.keepAlarms)
            Handlers.alarms.post(() -> ForceAppStandbyListener.removeAlarmsForUid(appRecord));

        if (!options.keepNetwork) {
            Handlers.network.post(() -> {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    CachedAppOptimizer.reportOneUidFrozenStateChanged(appRecord.getUid(), true);
                    return;
                }
                NetworkManagementService.socketDestroy(appRecord);
            });
        } else {
            // keepNetwork 打开时:
            // - Android 15 及以下: 跳过 socketDestroy;
            // - Android 16+: 不上报 uid frozen, 否则系统的冻结网络策略仍会切断网络,
            //   「保留网络连接」就形同虚设.
            Log.d(appRecord.getPackageNameWithUser() + " 保留网络连接");
        }

        // 仍然记为「已通知系统」, 只用于去重: 避免每 2 秒的配置对齐重复移闹钟 / 重复上报.
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

        boolean frozen = liveProcesses > 0 && frozenProcesses >= liveProcesses;
        appRecord.setFrozen(frozen);
        if (frozen)
            appRecord.markFrozenNow();
        else
            appRecord.clearFrozenSince();
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
            processRecord.setManuallyFrozen(false);
            anyThawed = true;
        }

        if (!wasFrozen && !anyThawed && !reported)
            return false;

        // 只有真正上报过「uid 冻结」才需要上报解冻 (部分冻结从来没上报过).
        reportUnfrozenLocked(appRecord);
        appRecord.setFrozen(false);
        appRecord.clearFrozenSince();
        appRecord.resetChildReapState();
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
            processRecord.setManuallyFrozen(true);
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
            processRecord.setManuallyFrozen(false);
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

        // 系统应用 / 输入法 / 内置保护应用: 任何情况下都不允许保留冻结进程.
        if (appRecord.isProtected()) {
            if (hasFrozenProcess(appRecord) || appRecord.isFrozen() || appRecord.isReportedFrozen()) {
                Log.i(appRecord.getPackageNameWithUser() + " 是系统应用 / 内置保护应用, 解冻已冻结进程");
                thawLocked(appRecord);
            }
            return;
        }

        // v1.2.3: 白名单是绝对保护, 自动冻结的手动冻结的子进程也统一解冻.
        if (appRecord.isWhiteListed()) {
            if (hasFrozenProcess(appRecord) || appRecord.isFrozen() || appRecord.isReportedFrozen()) {
                Log.i(appRecord.getPackageNameWithUser() + " 是白名单, 解冻全部进程");
                thawLocked(appRecord);
            }
            return;
        }

        boolean enabled = AppConfigs.isFreezeEnabled(appRecord.getPackageName(), appRecord.getUserId());
        if (!enabled) {
            if (hasFrozenProcess(appRecord) || appRecord.isFrozen() || appRecord.isReportedFrozen()) {
                Log.i(appRecord.getPackageNameWithUser() + " 已设置为不冻结, 解冻已冻结进程");
                thawLocked(appRecord);
            }
            return;
        }

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());

        if (!hasFrozenProcess(appRecord)) {
            if (appRecord.isFrozen())
                updateFrozenFlagLocked(appRecord);

            // 配置从「不冻结 / 白名单 / 规则排除」改成允许冻结时, 应用可能已经在后台了,
            // 不会再触发一次「进入后台」事件. 这里补一次冻结调度, 让设置即时生效
            // (30 秒内最多补一次, 避免写 cgroup 失败时每 2 秒重试).
            if (canAutoFreezeNow(appRecord, options)) {
                long now = SystemClock.elapsedRealtime();
                if (now - appRecord.getLastAutoFreezeKickMs() > AUTO_FREEZE_KICK_INTERVAL_MS) {
                    appRecord.setLastAutoFreezeKickMs(now);
                    FreezerHandler.sendFreezeMessage(appRecord, 2000);
                }
            }
            return;
        }

        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
                continue;
            if (shouldFreezeProcessLocked(appRecord, options, processRecord.getProcessName()))
                continue;

            FrozenRW.thaw(processRecord.getRunningUid(), processRecord.getPid());
            processRecord.setFrozen(false);
            processRecord.setManuallyFrozen(false);
            Log.i(appRecord.getPackageNameWithUser() + " 进程规则已排除 " + processRecord.getProcessName() + ", 解冻");
        }

        // 重新对齐「整应用冻结标记 + 系统上报状态」: 处理进程死亡 / 手动操作导致的
        // 「全冻结但没上报」和「不再全冻结但已上报」两种情况.
        syncReportedStateLocked(appRecord);
    }

    /** 是否有存活且已冻结的进程. */
    private static boolean hasFrozenProcess(AppRecord appRecord) {
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord != null && !processRecord.isDeathProcess() && processRecord.isFrozen())
                return true;
        }
        return false;
    }

    /**
     * 当前是否「值得」补发一次自动冻结调度: 应用在后台, 没有音频 / 定位 / 录音 / VPN 保护,
     * 且至少有一个进程按规则应该被冻结.
     */
    private static boolean canAutoFreezeNow(AppRecord appRecord, AppOptions options) {
        if (appRecord == null || options == null || !options.enabled)
            return false;
        if (appRecord.isProtected() || appRecord.isWhiteListed())
            return false;

        AppState appState = appRecord.getAppState();
        if (appState == null)
            return false;
        if (appState.isVisible() || appState.isLocation() || appState.isRecording() || appState.isVpn())
            return false;
        if (appState.isAudio() && options.keepAudio)
            return false;

        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess() || processRecord.isFrozen())
                continue;
            if (shouldFreezeProcessLocked(appRecord, options, processRecord.getProcessName()))
                return true;
        }
        return false;
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
     * 推送到达时按包名解冻.
     *
     * <p>为什么不按 userId: OEM 的广播方法参数布局经常变, 按包名找所有用户的 AppRecord 更稳;
     * 推送目标应用通常只有一个用户. 找不到 AppRecord (进程还没创建) 时仍记录免冻结窗口,
     * 让随后 ProcessAdd 触发的冻结被挡住.
     */
    public static void temporaryUnfreezeIfNeedByPackage(String packageName, String reason, long interval) {
        if (packageName == null || packageName.isEmpty())
            return;

        boolean matched = false;
        boolean held = false;
        List<AppRecord> appRecords = AppService.getAll();
        for (AppRecord appRecord : appRecords) {
            if (appRecord == null || !packageName.equals(appRecord.getPackageName()))
                continue;
            matched = true;
            // 系统应用 / 内置保护应用不会冻结, 不需要推送窗口.
            if (appRecord.isProtected())
                continue;
            if (!AppConfigs.isFreezeEnabled(packageName, appRecord.getUserId()))
                continue;
            if (!AppConfigs.getOptions(packageName, appRecord.getUserId()).wakeOnPush)
                continue;
            held = true;
            temporaryUnfreezeIfNeed(appRecord, reason, interval);
        }

        if (!matched || held)
            holdFreezeForPackage(packageName, interval);
    }

    /** 记录包名的「推送免冻结窗口」. */
    public static void holdFreezeForPackage(String packageName, long durationMs) {
        if (packageName == null || packageName.isEmpty())
            return;
        long until = SystemClock.elapsedRealtime() + Math.max(1000L, durationMs);
        FREEZE_HOLD_UNTIL_MS.put(packageName, until);
    }

    /**
     * 服务启动 / 绑定前解冻: 只处理当前确实被冻结的应用.
     *
     * <p>服务启动非常频繁, 如果对每个启动都排一次冻结消息会拖慢 Freezer 线程;
     * 只有「已经冻结」的应用才需要先解冻, 未冻结的应用本来就能正常启动服务.
     */
    public static void temporaryUnfreezeIfFrozenByPackage(String packageName, String reason, long interval) {
        if (packageName == null || packageName.isEmpty())
            return;

        boolean held = false;
        List<AppRecord> appRecords = AppService.getAll();
        for (AppRecord appRecord : appRecords) {
            if (appRecord == null || !packageName.equals(appRecord.getPackageName()))
                continue;
            if (appRecord.isProtected() || !appRecord.isFrozen())
                continue;
            temporaryUnfreezeIfNeed(appRecord, reason, interval);
            held = true;
        }

        if (held)
            holdFreezeForPackage(packageName, interval);
    }

    /** 距推送免冻结窗口结束还剩多少毫秒; 0 表示没有窗口 / 已过期. */
    private static long freezeHoldRemaining(String packageName) {
        if (packageName == null || packageName.isEmpty())
            return 0L;
        Long until = FREEZE_HOLD_UNTIL_MS.get(packageName);
        if (until == null)
            return 0L;
        long remaining = until - SystemClock.elapsedRealtime();
        if (remaining <= 0L) {
            FREEZE_HOLD_UNTIL_MS.remove(packageName);
            return 0L;
        }
        return remaining;
    }

    /**
     * 应用被外部事件 (Binder / 广播 / 媒体按键等) 唤醒时的临时解冻.
     *
     * <p>关键点: **解冻这一步永远要做**, 只有「解冻后再重新安排冻结」受应用选项控制.
     * 否则用户关掉某个应用的「参与自动冻结」后, 该应用仍然冻着, 而所有唤醒路径都被跳过,
     * 在依赖 Binder 解冻的 ROM (小米 Greeze / OPlus Hans / ReKernel) 上会表现为打不开 / ANR.
     */
    public static void temporaryUnfreezeIfNeed(AppRecord appRecord, String reason, long interval) {
        if (appRecord == null || appRecord.isProtected())
            return;

        // v1.2.3: 白名单绝对不冻, 唤醒时只需要解冻历史残留, 不再重新安排冻结.
        if (appRecord.isWhiteListed()) {
            if (hasFrozenProcess(appRecord) || appRecord.isFrozen() || appRecord.isReportedFrozen()) {
                synchronized (LOCK) {
                    Log.i(appRecord.getPackageNameWithUser() + " 是白名单, " + reason + " 解冻全部进程");
                    thawLocked(appRecord);
                }
            }
            return;
        }

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

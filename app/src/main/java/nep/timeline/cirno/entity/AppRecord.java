package nep.timeline.cirno.entity;

import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Data;
import nep.timeline.cirno.CommonConstants;
import nep.timeline.cirno.configs.checkers.AppConfigs;
import nep.timeline.cirno.utils.FilePickerGuard;
import nep.timeline.cirno.utils.InputMethodData;
import nep.timeline.cirno.utils.NetworkProxyGuard;
import nep.timeline.cirno.utils.PKGUtils;
import nep.timeline.cirno.virtuals.ProcessRecord;

@Data
public class AppRecord {
    private final String packageName;
    private final int userId;
    private final int uid;
    private final ApplicationInfo applicationInfo;
    /**
     * AppState 会被 reset() 整个替换, 内存回收线程会读它, 因此引用本身也要 volatile,
     * 否则可能读到已被替换掉的旧实例.
     */
    private volatile AppState appState;
    /** 冻结状态会被 Freezer / Binder 线程写、被 Memory 线程读, 必须 volatile. */
    private volatile boolean frozen;
    /**
     * 是否已经向系统上报过「整个 uid 已冻结」(reportOneUidFrozenStateChanged / socketDestroy / 移闹钟).
     *
     * <p>只有全部存活进程都冻结时才会上报; 上报之后即使进程自然死亡导致又走进「已全部冻结」的
     * 早退分支, 也不能重复上报, 解冻时也要知道该不该上报 unfrozen.
     */
    private volatile boolean reportedFrozen;
    private final List<ProcessRecord> processRecords = new CopyOnWriteArrayList<>();

    /**
     * 上一次由「配置对齐」补发自动冻结调度的时间 (elapsedRealtime).
     *
     * <p>用于限流: 进程写 cgroup 失败 / 规则选中的进程还没启动时, 避免每 2 秒都重试一次.
     */
    private volatile long lastAutoFreezeKickMs;

    /** 最近一次「完整冻结」开始的时间 (elapsedRealtime); 0 表示当前没有完整冻结. */
    private volatile long frozenSinceMs;

    /** 上一次尝试回收子进程的时间 (elapsedRealtime). */
    private volatile long lastChildReapMs;

    /** 已经回收过几轮子进程; 达到上限后进入退避, 防止 START_STICKY 服务反复拉起. */
    private volatile int childReapCount;

    /** 退避截止时间; 在此之前不再对这个应用做子进程回收. */
    private volatile long childReapBlockedUntilMs;

    public AppRecord(ApplicationInfo applicationInfo) {
        this.packageName = applicationInfo.packageName;
        this.userId = PKGUtils.getUserId(applicationInfo.uid);
        this.uid = applicationInfo.uid;
        this.applicationInfo = applicationInfo;
        this.appState = new AppState(this);
    }

    /**
     * 系统应用 / 输入法 / 内置保护应用: 任何情况下都不冻结, 也不允许手动冻结.
     *
     * <p>这些应用一旦被冻住, 整机 UI / 输入法 / 框架服务会直接不可用, 没有安全的唤醒路径.
     */
    public boolean isProtected() {
        if (packageName == null)
            return true;
        if (equals(InputMethodData.currentInputMethodApp))
            return true;
        // Google 服务默认受保护 (防止冻结 GMS 后 FCM 推送收不到).
        // 用户在不使用 FCM 时可以在应用面板里显式打开「允许冻结 Google 服务」,
        // 这时才绕过系统应用 / 内置白名单保护.
        if (CommonConstants.isGoogleFamily(packageName))
            return !AppConfigs.isGoogleFreezeAllowed(packageName, userId);

        // 文件选择器 / 文档提供器: 冻住后系统启动 Activity、查询 ContentProvider 会一直等,
        // 表现就是「刚点开文件管理器就卡住」.
        if (FilePickerGuard.isFilePicker(packageName))
            return true;

        // TProxy / 防火墙类应用: 规则还在, 但用户态进程被冻住后整机流量会断.
        if (NetworkProxyGuard.isNetworkProxy(packageName))
            return true;

        return PKGUtils.isSystemApp(applicationInfo) || CommonConstants.isWhitelistApps(packageName);
    }

    /**
     * 用户自己加的白名单应用.
     *
     * <p>语义: 整应用不参与自动冻结 / 内存回收, 但允许按进程规则或手动做「部分冻结」
     * (主进程永远不冻结, 见 {@code FreezerService.shouldFreezeProcess}). 这样用户既可以
     * 让应用整体不被冻死, 又可以单独冻掉它那些占内存又不需要常驻的子进程.
     */
    public boolean isWhiteListed() {
        return AppConfigs.isWhiteApp(packageName, userId);
    }

    /** 旧代码用的「完全不处理」判断, 保持原有语义 (系统应用 / 内置保护 / 用户白名单). */
    public boolean isSystem() {
        return isProtected() || isWhiteListed();
    }

    public String getPackageNameWithUser() {
        if (userId == 0)
            return packageName;
        return packageName + ":" + userId;
    }

    /** 标记「刚刚进入完整冻结」; 已冻结时不会刷新时间, 保证 frozenSinceMs 是首次冻结时间. */
    public void markFrozenNow() {
        if (frozenSinceMs <= 0L)
            frozenSinceMs = SystemClock.elapsedRealtime();
    }

    /** 解冻 / 不再完整冻结时清掉冻结起点. */
    public void clearFrozenSince() {
        frozenSinceMs = 0L;
    }

    /** 解冻后允许下一次冻结重新计算深度回收的冷却与次数. */
    public void resetChildReapState() {
        lastChildReapMs = 0L;
        childReapCount = 0;
        childReapBlockedUntilMs = 0L;
    }

    public void reset() {
        this.frozen = false;
        this.reportedFrozen = false;
        this.appState = new AppState(this);
        clearFrozenSince();
        resetChildReapState();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (obj == this)
            return true;
        if (obj instanceof AppRecord appRecord)
            return getUserId() == appRecord.getUserId() && getPackageName().equals(appRecord.getPackageName());
        return false;
    }

    @Override
    public int hashCode() {
        int userId = getUserId();
        String packageName = getPackageName();
        return ((Integer.hashCode(userId) + 59) * 59) + (packageName == null ? 43 : packageName.hashCode());
    }
}

package nep.timeline.cirno.entity;

import android.content.pm.ApplicationInfo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.Data;
import nep.timeline.cirno.CommonConstants;
import nep.timeline.cirno.configs.checkers.AppConfigs;
import nep.timeline.cirno.utils.InputMethodData;
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

    public AppRecord(ApplicationInfo applicationInfo) {
        this.packageName = applicationInfo.packageName;
        this.userId = PKGUtils.getUserId(applicationInfo.uid);
        this.uid = applicationInfo.uid;
        this.applicationInfo = applicationInfo;
        this.appState = new AppState(this);
    }

    public boolean isSystem() {
        return packageName == null || equals(InputMethodData.currentInputMethodApp) || PKGUtils.isSystemApp(applicationInfo) || AppConfigs.isWhiteApp(packageName, userId) || CommonConstants.isWhitelistApps(packageName);
    }

    public String getPackageNameWithUser() {
        if (userId == 0)
            return packageName;
        return packageName + ":" + userId;
    }

    public void reset() {
        this.frozen = false;
        this.reportedFrozen = false;
        this.appState = new AppState(this);
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

package nep.timeline.cirno.entity;

import android.os.IBinder;

import java.util.HashSet;
import java.util.Set;

import lombok.Getter;

@Getter
public class AppState {
    private final AppRecord parent;
    // 这些状态由各 Hook 线程写、Memory 线程读: volatile 保证读到的不是过期值,
    // 避免把「刚解冻、马上回前台」的应用当成冻结应用回收.
    private volatile boolean visible = false;
    private volatile boolean location = false;
    private volatile boolean audio = false;
    private volatile boolean recording = false;
    private volatile boolean vpn = false;
    /**
     * 应用正在显示悬浮窗 (TYPE_APPLICATION_OVERLAY).
     *
     * <p>悬浮窗应用可能没有前台 Activity (翻译/录屏/小窗/悬浮球), 只看 visible 会把它当后台冻住,
     * 表现为窗口卡死、停止刷新. 但也不能只凭 addWindow 就豁免: addWindow 只创建 WindowState,
     * 真正可见要等 setViewVisibility(VISIBLE), 而且 AppOps 可能拒绝悬浮窗权限.
     * 因此用两个独立的可见性集合求交集, 详见下面的 updateOverlayState().
     */
    private volatile boolean overlay = false;
    private final Set<IBinder> activities = new HashSet<>();
    private final Set<IBinder> locationListeners = new HashSet<>();
    private final Set<Integer> interfaceIds = new HashSet<>();
    private final Set<Integer> recodingIds = new HashSet<>();
    /** 已 addWindow 的悬浮窗 token. */
    private final Set<IBinder> windowTokens = new HashSet<>();
    /** 应用侧认为可见 (WindowState.setViewVisibility == VISIBLE) 的 token. */
    private final Set<IBinder> appVisibleWindowTokens = new HashSet<>();
    /** AppOps 允许显示 (setAppOpVisibilityLw(true)) 的 token. */
    private final Set<IBinder> appOpVisibleWindowTokens = new HashSet<>();

    public AppState(AppRecord appRecord) {
        this.parent = appRecord;
    }

    public synchronized boolean setVisible(boolean value) {
        if (visible == value)
            return false;
        visible = value;
        return true;
    }

    public synchronized boolean setLocation(boolean value) {
        if (location == value)
            return false;
        location = value;
        return true;
    }

    public synchronized boolean setAudio(boolean value) {
        if (audio == value)
            return false;
        audio = value;
        return true;
    }

    public synchronized boolean setRecording(boolean value) {
        if (recording == value)
            return false;
        recording = value;
        return true;
    }

    public synchronized boolean setVpn(boolean value) {
        if (vpn == value)
            return false;
        vpn = value;
        return true;
    }

    /**
     * 记录一个新 addWindow 的悬浮窗.
     *
     * @return overlay 是否由「不可见」翻转为「可见」 (调用方只需在 true 时解冻)
     */
    public synchronized boolean addWindowToken(IBinder token, boolean appVisible, boolean appOpVisible) {
        if (token == null || !windowTokens.add(token))
            return false;
        if (appVisible)
            appVisibleWindowTokens.add(token);
        if (appOpVisible)
            appOpVisibleWindowTokens.add(token);
        boolean wasOverlay = overlay;
        updateOverlayState();
        return !wasOverlay && overlay;
    }

    /** 应用侧可见性变化; 返回 overlay 状态是否翻转. */
    public synchronized boolean setWindowAppVisibility(IBinder token, boolean visible) {
        if (token == null || !windowTokens.contains(token))
            return false;
        boolean changed = visible
                ? appVisibleWindowTokens.add(token)
                : appVisibleWindowTokens.remove(token);
        if (!changed)
            return false;
        boolean wasOverlay = overlay;
        updateOverlayState();
        return wasOverlay != overlay;
    }

    /** AppOps 可见性变化; 返回 overlay 状态是否翻转. */
    public synchronized boolean setWindowAppOpVisibility(IBinder token, boolean visible) {
        if (token == null || !windowTokens.contains(token))
            return false;
        boolean changed = visible
                ? appOpVisibleWindowTokens.add(token)
                : appOpVisibleWindowTokens.remove(token);
        if (!changed)
            return false;
        boolean wasOverlay = overlay;
        updateOverlayState();
        return wasOverlay != overlay;
    }

    /** 窗口移除; 返回 overlay 是否由「可见」翻转为「不可见」. */
    public synchronized boolean removeWindowToken(IBinder token) {
        if (token == null || !windowTokens.remove(token))
            return false;
        appVisibleWindowTokens.remove(token);
        appOpVisibleWindowTokens.remove(token);
        boolean wasOverlay = overlay;
        updateOverlayState();
        return wasOverlay && !overlay;
    }

    /**
     * overlay = 存在某个 token 同时「应用侧可见」且「AppOps 允许」.
     *
     * <p>两个条件必须同时满足: 只看 appVisible 会把被 AppOps 拒绝的窗口当成可见, 应用永远不会
     * 被冻结; 只看 appOp 会在 setViewVisibility 之前提前豁免. 用集合而不是布尔值是为了支持多窗口.
     */
    private void updateOverlayState() {
        for (IBinder token : appVisibleWindowTokens) {
            if (appOpVisibleWindowTokens.contains(token)) {
                overlay = true;
                return;
            }
        }
        overlay = false;
    }

    public boolean isOverlay() {
        return overlay;
    }
}

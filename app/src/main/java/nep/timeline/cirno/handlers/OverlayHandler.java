package nep.timeline.cirno.handlers;

import android.os.IBinder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.threads.Handlers;

/**
 * 悬浮窗 / 画中画 (TYPE_APPLICATION_OVERLAY) 窗口的可见性汇总.
 *
 * <p>WMS 的调用顺序不是「先 addWindow 再通知可见性」: addWindow 内部就会先
 * setViewVisibility / initAppOpsState, 这些回调可能比 addWindow 的 After hook 更早
 * 到达 overlay 线程. 所以这里用缓存把「窗口存在」和「窗口真的可见」解耦:
 * 可见性回调先到时先存缓存, addWindow 到达时再把缓存当初始值用, 避免把刚添加的窗口登记成可见.
 */
public class OverlayHandler {
    /** token -> 窗口是否真的被显示 (setViewVisibility == View.VISIBLE). */
    private static final ConcurrentMap<IBinder, Boolean> APP_WINDOW_VISIBILITY = new ConcurrentHashMap<>();
    /** token -> AppOps 是否允许该窗口显示 (WindowState#mAppOpVisibility). */
    private static final ConcurrentMap<IBinder, Boolean> APP_OP_VISIBILITY = new ConcurrentHashMap<>();

    /**
     * 悬浮窗不可见 / 关闭后延迟冻结的时间.
     *
     * <p>用户关掉悬浮窗后经常立刻又开下一个 (切换画中画、拖动小窗), 马上冻结会让应用看起来卡死,
     * 留几秒缓冲, 期间应用还能继续响应.
     */
    private static final long FREEZE_DELAY = 5000L;

    public static void onWindowAdded(String packageName, int userId, IBinder token) {
        try {
            if (packageName == null || packageName.isEmpty() || token == null)
                return;

            AppRecord appRecord = AppService.get(packageName, userId);
            if (appRecord == null)
                return;

            // 可见性回调可能比 addWindow 先到, 优先用缓存里的值; 没有就按不可见处理:
            // WindowState#mAppOpVisibility 默认是 true, 若此时直接按可见登记,
            // 一个被 AppOps 拒绝的窗口也会把应用当成「正在显示悬浮窗」而永远不冻结.
            boolean appVisible = APP_WINDOW_VISIBILITY.getOrDefault(token, false);
            boolean appOpVisible = APP_OP_VISIBILITY.getOrDefault(token, false);

            if (appRecord.getAppState().addWindowToken(token, appVisible, appOpVisible)) {
                Log.d("应用 " + appRecord.getPackageNameWithUser() + " 显示悬浮窗");
                FreezerService.thaw(appRecord);
            }

            // 客户端直接销毁窗口时不一定走 WMS#removeWindow / removeClientToken,
            // 用 Binder 死亡监听兜底, 否则 windowTokens 会一直留着, 这个应用再也不会被自动冻结.
            try {
                token.linkToDeath(
                        () -> Handlers.overlay.post(() -> onWindowRemoved(packageName, userId, token)),
                        0);
            } catch (Throwable throwable) {
                Log.e("OverlayHandler 监听悬浮窗 Binder 死亡", throwable);
            }
        } catch (Throwable throwable) {
            Log.e("OverlayHandler.onWindowAdded", throwable);
        }
    }

    public static void onWindowAppVisibilityChanged(String packageName, int userId, IBinder token,
            boolean visible) {
        try {
            if (packageName == null || packageName.isEmpty() || token == null)
                return;

            // 先写缓存: AppService 拿不到 AppRecord 时 (进程刚启动 / 正在更新) 后面的 addWindow
            // 也要能读到这次可见性, 否则会丢掉用户真正看到的那个窗口.
            APP_WINDOW_VISIBILITY.put(token, visible);

            AppRecord appRecord = AppService.get(packageName, userId);
            if (appRecord == null)
                return;

            onOverlayStateChanged(appRecord, visible,
                    appRecord.getAppState().setWindowAppVisibility(token, visible));
        } catch (Throwable throwable) {
            Log.e("OverlayHandler.onWindowAppVisibilityChanged", throwable);
        }
    }

    public static void onWindowAppOpVisibilityChanged(String packageName, int userId, IBinder token,
            boolean visible) {
        try {
            if (packageName == null || packageName.isEmpty() || token == null)
                return;

            APP_OP_VISIBILITY.put(token, visible);

            AppRecord appRecord = AppService.get(packageName, userId);
            if (appRecord == null)
                return;

            onOverlayStateChanged(appRecord, visible,
                    appRecord.getAppState().setWindowAppOpVisibility(token, visible));
        } catch (Throwable throwable) {
            Log.e("OverlayHandler.onWindowAppOpVisibilityChanged", throwable);
        }
    }

    /**
     * overlay 状态真的翻转时才动冻结: 一个应用可能同时挂多个悬浮窗 (例如画中画 + 小窗),
     * 单个 token 的状态变化不一定影响整体是否还在显示悬浮窗, AppState 已经帮我们判断了.
     */
    private static void onOverlayStateChanged(AppRecord appRecord, boolean visible, boolean changed) {
        if (!changed)
            return;

        if (visible) {
            Log.d("应用 " + appRecord.getPackageNameWithUser() + " 显示悬浮窗");
            FreezerService.thaw(appRecord);
            return;
        }

        Log.d("应用 " + appRecord.getPackageNameWithUser() + " 悬浮窗不可见");
        FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, FREEZE_DELAY);
    }

    public static void onWindowRemoved(String packageName, int userId, IBinder token) {
        try {
            if (token == null)
                return;

            // 缓存无论如何都要清: token 已经死了, 留着就是泄漏, 而 token 不会复用.
            APP_WINDOW_VISIBILITY.remove(token);
            APP_OP_VISIBILITY.remove(token);

            // 拿不到包名时无法定位 AppState, 直接返回; 这个 token 会由下一次可见性回调 /
            // linkToDeath 或应用重启后的状态重置收敛, 强行猜包名会清掉别的应用的窗口状态.
            if (packageName == null || packageName.isEmpty())
                return;

            AppRecord appRecord = AppService.get(packageName, userId);
            if (appRecord == null)
                return;

            if (appRecord.getAppState().removeWindowToken(token)) {
                Log.d("应用 " + appRecord.getPackageNameWithUser() + " 关闭悬浮窗");
                FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, FREEZE_DELAY);
            }
        } catch (Throwable throwable) {
            Log.e("OverlayHandler.onWindowRemoved", throwable);
        }
    }
}

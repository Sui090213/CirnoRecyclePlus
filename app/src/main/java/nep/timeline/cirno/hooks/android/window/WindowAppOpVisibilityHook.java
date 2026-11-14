package nep.timeline.cirno.hooks.android.window;

import android.os.Build;
import android.os.IBinder;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.handlers.OverlayHandler;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.PKGUtils;

/**
 * 监听 WindowState 的 AppOps 可见性 (mAppOpVisibility).
 *
 * <p>窗口即使被 setViewVisibility(View.VISIBLE), 只要 SYSTEM_ALERT_WINDOW 的 AppOps 被拒,
 * 系统仍然会把它隐藏. AppState 要求「视图可见 + AppOps 可见」同时成立才算悬浮窗可见,
 * 所以这个信号必须单独跟踪, 否则被 AppOps 拒绝的窗口会让应用一直免冻结.
 */
public class WindowAppOpVisibilityHook extends MethodHook {
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    public WindowAppOpVisibilityHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.wm.WindowState";
    }

    @Override
    public String getTargetMethod() {
        return "setAppOpVisibilityLw";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[] { boolean.class };
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                try {
                    Object[] args = callback.getArgs();
                    if (args.length < 1)
                        return;

                    Object windowState = callback.getThisObject();
                    if (windowState == null)
                        return;

                    Object attrs = CakeReflection.getObjectField(windowState, "mAttrs");
                    if (attrs == null
                            || CakeReflection.getIntField(attrs, "type") != TYPE_APPLICATION_OVERLAY)
                        return;

                    Object session = CakeReflection.getObjectField(windowState, "mSession");
                    Object client = CakeReflection.getObjectField(windowState, "mClient");
                    if (session == null || client == null)
                        return;

                    // setAppOpVisibilityLw 可能在 WindowState 构造完成后立刻被 initAppOpsState()
                    // 调用, 此时 Session#mPackageName 还不一定有值, 先退回窗口自己的包名.
                    String packageName = (String) CakeReflection.getObjectField(attrs, "packageName");
                    if (packageName == null || packageName.isEmpty())
                        packageName = (String) CakeReflection.getObjectField(session, "mPackageName");
                    if (packageName == null || packageName.isEmpty())
                        return;

                    int userId = PKGUtils.getUserId(CakeReflection.getIntField(session, "mUid"));

                    Object binder = CakeReflection.callMethod(client, "asBinder");
                    if (!(binder instanceof IBinder token))
                        return;

                    boolean visible = (boolean) args[0];

                    final String finalPackageName = packageName;
                    Handlers.overlay.post(() -> {
                        try {
                            OverlayHandler.onWindowAppOpVisibilityChanged(
                                    finalPackageName, userId, token, visible);
                        } catch (Throwable throwable) {
                            Log.e("WindowAppOpVisibilityHook 处理悬浮窗 AppOps 可见性", throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e("WindowAppOpVisibilityHook", throwable);
                }
            }
        };
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.S;
    }
}

package nep.timeline.cirno.hooks.android.window;

import android.os.Build;
import android.os.IBinder;
import android.view.View;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.handlers.OverlayHandler;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.PKGUtils;

/**
 * 监听 WindowState 的真实视图可见性 (客户端 setViewVisibility).
 *
 * <p>悬浮窗/画中画是否「真的在屏幕上」最终由这个值决定: 窗口对象存在但用户切走了、
 * 或者画中画被收进后台, 都会走到这里 (View.GONE / INVISIBLE). 只有可见时才豁免冻结.
 */
public class WindowViewVisibilityHook extends MethodHook {
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    public WindowViewVisibilityHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.wm.WindowState";
    }

    @Override
    public String getTargetMethod() {
        return "setViewVisibility";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[] { int.class };
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

                    // 这个回调可能在 addWindow 内部就触发, 那时 Session#mPackageName 还没填,
                    // 所以先取窗口自己的 attrs.packageName.
                    String packageName = (String) CakeReflection.getObjectField(attrs, "packageName");
                    if (packageName == null || packageName.isEmpty())
                        packageName = (String) CakeReflection.getObjectField(session, "mPackageName");
                    if (packageName == null || packageName.isEmpty())
                        return;

                    int userId = PKGUtils.getUserId(CakeReflection.getIntField(session, "mUid"));

                    Object binder = CakeReflection.callMethod(client, "asBinder");
                    if (!(binder instanceof IBinder token))
                        return;

                    boolean visible = (int) args[0] == View.VISIBLE;

                    final String finalPackageName = packageName;
                    Handlers.overlay.post(() -> {
                        try {
                            OverlayHandler.onWindowAppVisibilityChanged(
                                    finalPackageName, userId, token, visible);
                        } catch (Throwable throwable) {
                            Log.e("WindowViewVisibilityHook 处理悬浮窗可见性", throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e("WindowViewVisibilityHook", throwable);
                }
            }
        };
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.S;
    }
}

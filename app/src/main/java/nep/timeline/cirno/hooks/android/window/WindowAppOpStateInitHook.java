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
 * 在 WindowState#initAppOpsState() 之后登记一次 AppOps 可见性.
 *
 * <p>为什么需要: WindowState#mAppOpVisibility 字段默认是 true, 用户拒绝「显示在其他应用上层」
 * 时框架会在 initAppOpsState() 里把它改成 false. 而 AppOps 变化回调可能比 addWindow 的 After
 * hook 更早到达 overlay 线程, 所以这里先记一份初始值, 让 addWindow 登记窗口时能拿到正确的初值,
 * 避免把被拒绝的窗口当成可见, 导致应用永远不冻结.
 *
 * <p>选择 initAppOpsState 而不是构造函数: 它是无参 void、A12-A16 都稳定存在, 而且执行时
 * mAttrs/mSession/mClient 都已就绪; hook 构造函数在部分 libxposed 实现里拿不到 thisObject,
 * 也没有必要在构造期增加开销.
 */
public class WindowAppOpStateInitHook extends MethodHook {
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    public WindowAppOpStateInitHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.wm.WindowState";
    }

    @Override
    public String getTargetMethod() {
        return "initAppOpsState";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[0];
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                try {
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

                    int uid = CakeReflection.getIntField(session, "mUid");
                    String packageName = (String) CakeReflection.getObjectField(attrs, "packageName");
                    if (packageName == null || packageName.isEmpty())
                        packageName = (String) CakeReflection.getObjectField(session, "mPackageName");
                    if (packageName == null || packageName.isEmpty())
                        return;

                    Object binder = CakeReflection.callMethod(client, "asBinder");
                    if (!(binder instanceof IBinder token))
                        return;

                    boolean visible = CakeReflection.getBooleanField(windowState, "mAppOpVisibility");
                    int userId = PKGUtils.getUserId(uid);
                    final String finalPackageName = packageName;
                    Handlers.overlay.post(() -> {
                        try {
                            OverlayHandler.onWindowAppOpVisibilityChanged(
                                    finalPackageName, userId, token, visible);
                        } catch (Throwable throwable) {
                            Log.e("WindowAppOpStateInitHook 处理悬浮窗 AppOps 初始状态", throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e("WindowAppOpStateInitHook", throwable);
                }
            }
        };
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.S;
    }
}

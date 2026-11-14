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
 * 监听 WMS 添加窗口, 只关心 TYPE_APPLICATION_OVERLAY (悬浮窗 / 画中画).
 *
 * <p>窗口在这里出现不代表应用「正在显示悬浮窗」: addWindow 只创建 WindowState,
 * 真正的可见性要等 setViewVisibility / AppOps 判定. 所以这里只把 token 登记进 AppState,
 * 之后由 OverlayHandler 结合可见性回调统一决定解冻还是重新排冻结.
 */
public class WindowAddHook extends MethodHook {
    /** WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; 直接写字面量, 避免依赖隐藏字段. */
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    public WindowAddHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.wm.WindowManagerService";
    }

    @Override
    public String getTargetMethod() {
        return "addWindow";
    }

    @Override
    public Object[] getTargetParam() {
        // A12-A17 之间 addWindow 的参数一直在增删 (requestUserId / requestedVisibleTypes / ...),
        // 只拿前三个稳定参数做前缀匹配, 让工具返回本机实际的完整签名, 不写死版本.
        return CakeReflection.findParameterTypesOrDefault(
                CakeReflection.findClassIfExists(getTargetClass(), classLoader),
                getTargetMethod(),
                "com.android.server.wm.Session",
                "android.view.IWindow",
                "android.view.WindowManager$LayoutParams");
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                try {
                    // addWindow 成功时返回 ADD_OKAY(0), 同时还可能带上 ADD_FLAG_IN_TOUCH_MODE /
                    // ADD_FLAG_APP_VISIBLE 这类正的状态位, 只有负数才是添加失败.
                    if (callback.throwable != null || !(callback.result instanceof Integer)
                            || (int) callback.result < 0)
                        return;

                    Object[] args = callback.getArgs();
                    if (args.length < 3)
                        return;

                    Object session = args[0];
                    Object client = args[1];
                    Object attrs = args[2];
                    if (session == null || client == null || attrs == null)
                        return;

                    if (CakeReflection.getIntField(attrs, "type") != TYPE_APPLICATION_OVERLAY)
                        return;

                    // 包名优先用 attrs 里的: Session#mPackageName 是在 WindowState 构造之后
                    // 由 Session#onWindowAdded() 才填上的, 这里可能还是 null.
                    String packageName = (String) CakeReflection.getObjectField(attrs, "packageName");
                    if (packageName == null || packageName.isEmpty())
                        packageName = (String) CakeReflection.getObjectField(session, "mPackageName");
                    if (packageName == null || packageName.isEmpty())
                        return;

                    int userId = PKGUtils.getUserId(CakeReflection.getIntField(session, "mUid"));

                    Object binder = CakeReflection.callMethod(client, "asBinder");
                    if (!(binder instanceof IBinder token))
                        return;

                    final String finalPackageName = packageName;
                    Handlers.overlay.post(() -> {
                        try {
                            OverlayHandler.onWindowAdded(finalPackageName, userId, token);
                        } catch (Throwable throwable) {
                            Log.e("WindowAddHook 处理悬浮窗添加", throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e("WindowAddHook", throwable);
                }
            }
        };
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.S;
    }
}

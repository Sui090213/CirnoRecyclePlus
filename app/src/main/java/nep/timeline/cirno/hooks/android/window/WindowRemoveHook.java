package nep.timeline.cirno.hooks.android.window;

import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;

import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.handlers.OverlayHandler;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.PKGUtils;

/**
 * 监听窗口移除, 把已经关闭的悬浮窗 token 从 AppState 里清掉, 让应用重新参与自动冻结.
 *
 * <p>版本差异 (已按 AOSP A12-A16 的源码确认):
 * <ul>
 *   <li>A12-A14: {@code WindowManagerService#removeWindow(Session, IWindow)}</li>
 *   <li>A15-A16: 方法改名为 {@code WindowManagerService#removeClientToken(Session, IBinder)},
 *       removeWindow 已被删除</li>
 * </ul>
 * 这里不写死版本号, 而是在本机类里找这两个方法: A15 起优先 removeClientToken, 否则优先 removeWindow,
 * 其中一个不存在时退到另一个, 尽量不在未知版本上完全失去回调.
 */
public class WindowRemoveHook extends MethodHook {
    private static final String TARGET_CLASS = "com.android.server.wm.WindowManagerService";
    private static final String SESSION_CLASS = "com.android.server.wm.Session";
    private static final String IWINDOW_CLASS = "android.view.IWindow";
    private static final String IBINDER_CLASS = "android.os.IBinder";

    /**
     * 解析出来的目标方法.
     *
     * <p>注意: MethodHook 的父类构造函数会回调 getTargetParam()/getTargetMethod(), 从解析结果里读到它;
     * 这里不写字段初始化语句, 否则子类字段初始化会在 super() 之后把缓存冲掉 (虽然值为 null 也没影响,
     * 但保留缓存能避免 super() 里解析两次).
     */
    private Method resolvedMethod;

    public WindowRemoveHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return TARGET_CLASS;
    }

    @Override
    public String getTargetMethod() {
        Method method = resolveTargetMethod();
        return method == null ? "removeWindow" : method.getName();
    }

    @Override
    public Object[] getTargetParam() {
        Method method = resolveTargetMethod();
        if (method == null)
            return new Object[] { SESSION_CLASS, IWINDOW_CLASS };
        return method.getParameterTypes();
    }

    private Method resolveTargetMethod() {
        if (resolvedMethod != null)
            return resolvedMethod;

        Class<?> clazz = CakeReflection.findClassIfExists(TARGET_CLASS, classLoader);
        if (clazz == null)
            return null;

        Method removeWindow = null;
        Method removeClientToken = null;
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2 || !parameterTypes[0].getName().equals(SESSION_CLASS))
                continue;

            if (method.getName().equals("removeWindow")
                    && parameterTypes[1].getName().equals(IWINDOW_CLASS)) {
                removeWindow = method;
            } else if (method.getName().equals("removeClientToken")
                    && parameterTypes[1].getName().equals(IBINDER_CLASS)) {
                removeClientToken = method;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            resolvedMethod = removeClientToken != null ? removeClientToken : removeWindow;
        else
            resolvedMethod = removeWindow != null ? removeWindow : removeClientToken;

        return resolvedMethod;
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                try {
                    // 用 After 而不是 Before: 移除失败时窗口还在, 这时候清掉 AppState 会让应用
                    // 在悬浮窗仍显示的情况下被冻结.
                    if (callback.throwable != null)
                        return;

                    Object[] args = callback.getArgs();
                    if (args.length == 0)
                        return;

                    Object session = args[0];
                    if (session == null)
                        return;

                    IBinder token = findClientToken(args);
                    if (token == null)
                        return;

                    int uid;
                    try {
                        uid = CakeReflection.getIntField(session, "mUid");
                    } catch (Throwable throwable) {
                        // 拿不到 uid 就确定不了 userId, 多用户下猜 userId=0 会清错应用的窗口状态.
                        return;
                    }

                    String packageName = null;
                    try {
                        packageName = (String) CakeReflection.getObjectField(session, "mPackageName");
                    } catch (Throwable ignored) {
                        // 部分版本 / ROM 上这个字段可能不存在或还没赋值, 下面统一按拿不到处理.
                    }

                    if (packageName == null || packageName.isEmpty()) {
                        // AppState 只按 token 记录窗口, 没有 token -> 应用的反查表; 没有包名就无法定位
                        // AppRecord. 与其猜一个包名 (会误清别的应用的悬浮窗状态), 不如跳过:
                        // 这个 token 还有 OverlayHandler 里的 linkToDeath 兜底.
                        return;
                    }

                    final String finalPackageName = packageName;
                    final int userId = PKGUtils.getUserId(uid);

                    Handlers.overlay.post(() -> {
                        try {
                            OverlayHandler.onWindowRemoved(finalPackageName, userId, token);
                        } catch (Throwable throwable) {
                            Log.e("WindowRemoveHook 处理悬浮窗移除", throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    Log.e("WindowRemoveHook", throwable);
                }
            }
        };
    }

    /**
     * 从参数里找出客户端窗口 token.
     *
     * <p>A15+ 的 removeClientToken 第二个参数就是 IBinder; A12-A14 的 removeWindow 给的是 IWindow,
     * 需要 asBinder() 转换. 直接扫参数而不是按下标取, 兼容方法签名变化.
     */
    private IBinder findClientToken(Object[] args) {
        Class<?> iWindow = CakeReflection.findClassIfExists(IWINDOW_CLASS, classLoader);

        // args[0] 是 Session, 而 Session 本身继承 IWindowSession.Stub, 也是一个 IBinder.
        // 如果不跳过它, A15+ 的 removeClientToken(Session, IBinder) 会把 Session 当成窗口 token,
        // AppState 里的 overlay 永远清不掉 (应用从此不再自动冻结). 只从第 2 个参数开始找.
        for (int i = 1; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null)
                continue;

            // A15+: removeClientToken(Session, IBinder); 系统进程自己创建的窗口也可能是
            // 同时实现 IWindow 与 IBinder 的本地 Binder, 这时它本身就是 token.
            if (arg instanceof IBinder binder)
                return binder;

            if (iWindow != null && iWindow.isInstance(arg)) {
                Object binder = CakeReflection.callMethod(arg, "asBinder");
                if (binder instanceof IBinder token)
                    return token;
            }
        }

        return null;
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.S;
    }
}

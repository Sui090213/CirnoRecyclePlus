package nep.timeline.cirno.hooks.android.input;

import android.os.Build;
import android.view.inputmethod.InputMethodInfo;

import java.util.Map;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.ActivityManagerService;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.utils.InputMethodData;

/**
 * 输入法切换时迁移「当前输入法保护」.
 *
 * <p>只有 setInputMethodLocked 一个 hook 点: 输入法被冻住时系统根本发不出 show/hide 回调,
 * 靠 show 解冻是来不及的. 当前输入法由 AppRecord.isProtected() 永久保护, 这里只负责在切换时
 * 解冻新输入法、回收旧输入法; 开机时的初始输入法由 InputMethodData.initFromSettingsWithRetry()
 * 兜底恢复.
 */
public class InputMethodManagerService extends MethodHook {
    private static final long OLD_IME_FREEZE_DELAY_MS = 3000L;

    public InputMethodManagerService(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.inputmethod.InputMethodManagerService";
    }

    @Override
    public String getTargetMethod() {
        return "setInputMethodLocked";
    }

    @Override
    public Object[] getTargetParam() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM)
            return CakeReflection.findParameterTypesOrDefault(CakeReflection.findClassIfExists(getTargetClass(), classLoader), getTargetMethod(), String.class, int.class, int.class, int.class);
        return CakeReflection.findParameterTypesOrDefault(CakeReflection.findClassIfExists(getTargetClass(), classLoader), getTargetMethod(), String.class, int.class);
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                try {
                    Object[] args = callback.getArgs();
                    if (args.length < 1 || !(args[0] instanceof String id) || id.isEmpty())
                        return;

                    int userId = (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM && args.length > 3)
                            ? (int) args[3]
                            : ActivityManagerService.getCurrentOrTargetUserId();
                    Object settings = resolveSettings(callback.getThisObject(), userId);

                    AppRecord newApp;
                    AppRecord oldApp;
                    boolean switched;

                    // cgroup 读写 (thaw / 重新冻结) 必须放在 InputMethodData 锁外:
                    // 锁内做 I/O 会把 setInputMethodLocked 的调用线程和 Freezer 线程串起来.
                    synchronized (InputMethodData.class) {
                        ensureMethodMap(callback.getThisObject(), settings);
                        Map<String, InputMethodInfo> map = InputMethodData.inputMethods;
                        if (map == null || map.isEmpty())
                            return;

                        InputMethodInfo info = map.get(id);
                        if (info == null)
                            return;

                        // 同一个 IME 重复设置: 不需要任何处理. 不同 subtype 切换时 info 会变,
                        // 但包没变, 不触发解冻/重冻.
                        if (info.equals(InputMethodData.currentInputMethodInfo))
                            return;

                        newApp = AppService.get(info.getPackageName(), userId);
                        oldApp = InputMethodData.currentInputMethodApp;

                        InputMethodData.currentInputMethodInfo = info;
                        switched = newApp != null && !newApp.equals(oldApp);
                        if (newApp != null)
                            InputMethodData.currentInputMethodApp = newApp;
                    }

                    if (!switched)
                        return;

                    Log.i("切换输入法: " + id);
                    FreezerService.thaw(newApp);
                    if (oldApp != null && !InputMethodData.isCurrentInputMethod(oldApp))
                        FreezerHandler.sendFreezeMessageIgnoreMessages(oldApp, OLD_IME_FREEZE_DELAY_MS);
                } catch (Throwable throwable) {
                    Log.e("InputMethodManagerService 处理失败", throwable);
                }
            }
        };
    }

    private Object resolveSettings(Object imms, int userId) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Class<?> repository = CakeReflection.findClassIfExists(
                    "com.android.server.inputmethod.InputMethodSettingsRepository", classLoader);
            if (repository != null) {
                try {
                    return CakeReflection.callStaticMethod(repository, "get", userId);
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return CakeReflection.getObjectField(imms, "mSettings");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析 mMethodMap / InputMethodMap.mMap.
     *
     * <p>map 为空时必须每次重试: 首次 setInputMethod 时 settings 可能还没初始化好,
     * 旧实现只填一次, 之后即使 map 可用也永久 return, 输入法保护就失效了.
     */
    @SuppressWarnings("unchecked")
    private void ensureMethodMap(Object imms, Object settings) {
        if (InputMethodData.instance == null)
            InputMethodData.instance = imms;

        Map<String, InputMethodInfo> current = InputMethodData.inputMethods;
        if (current != null && !current.isEmpty())
            return;

        if (settings == null) {
            InputMethodData.inputMethods = null;
            return;
        }

        try {
            Object map = CakeReflection.getObjectField(settings, "mMethodMap");
            if (map == null) {
                InputMethodData.inputMethods = null;
                return;
            }

            if ("com.android.server.inputmethod.InputMethodMap".equals(map.getClass().getTypeName())) {
                Object inner = CakeReflection.getObjectField(map, "mMap");
                InputMethodData.inputMethods = inner instanceof Map
                        ? (Map<String, InputMethodInfo>) inner
                        : null;
            } else if (map instanceof Map) {
                InputMethodData.inputMethods = (Map<String, InputMethodInfo>) map;
            } else {
                InputMethodData.inputMethods = null;
            }
        } catch (Throwable throwable) {
            Log.w("解析 InputMethod method map 失败", throwable);
            InputMethodData.inputMethods = null;
        }
    }
}

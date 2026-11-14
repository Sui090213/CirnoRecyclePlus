package nep.timeline.cirno.utils;

import android.content.Context;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;

import java.util.HashMap;
import java.util.Map;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.ActivityManagerService;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.threads.Handlers;

/**
 * 当前输入法状态.
 *
 * <p>输入法被冻结时系统发不出 show 请求, 所以不能靠 hook show/hide 来解冻; 只能把「当前输入法」
 * 当成永久保护对象 (见 AppRecord.isProtected). 切换输入法时由 InputMethodManagerService hook 迁移保护.
 *
 * <p>但 hook 是进程启动后才装的: 开机后用户直接用输入法时, 本类里的 currentInputMethodApp 还是 null,
 * 第三方输入法就会被当普通后台应用冻住 → 点输入框卡死. 因此需要在 system_server 就绪后从
 * Settings.Secure.DEFAULT_INPUT_METHOD 反推当前输入法, 并重试到 AMS/Context 可用为止.
 */
public class InputMethodData {
    private static final int REFRESH_MAX_ATTEMPTS = 60;
    private static final long REFRESH_INTERVAL_MS = 1000L;

    public static volatile Object instance;
    public static volatile Map<String, InputMethodInfo> inputMethods = new HashMap<>();
    public static volatile InputMethodInfo currentInputMethodInfo;
    public static volatile AppRecord currentInputMethodApp;

    /** 当前输入法是否是某个应用 (按 AppRecord 身份). */
    public static boolean isCurrentInputMethod(AppRecord appRecord) {
        AppRecord current = currentInputMethodApp;
        return current != null && current.equals(appRecord);
    }

    /** 当前输入法是否是某个包名 (AppRecord 还没建立时也能判断). */
    public static boolean isCurrentInputMethodPackage(String packageName) {
        if (packageName == null)
            return false;
        AppRecord current = currentInputMethodApp;
        return current != null && packageName.equals(current.getPackageName());
    }

    /**
     * 更新当前输入法. 只在真正切换时解冻新输入法, 避免每次 setInputMethod 都写 cgroup.
     *
     * @return 是否发生了切换
     */
    public static synchronized boolean setCurrentInputMethod(InputMethodInfo info, AppRecord appRecord) {
        if (info != null)
            currentInputMethodInfo = info;
        if (appRecord == null || appRecord.equals(currentInputMethodApp))
            return false;
        currentInputMethodApp = appRecord;
        return true;
    }

    /**
     * 开机兜底: 反复尝试从 Settings 恢复当前输入法, 成功后解冻它 (如果已经被冻).
     *
     * <p>只重试有限次数, 避免 Settings 永远读不到时留下无限循环; 期间 AMS 的 Context 也可能还没
     * 初始化好, refreshFromSettings() 会返回 false, 下一轮再试.
     */
    public static void initFromSettingsWithRetry() {
        initFromSettingsWithRetry(0);
    }

    private static void initFromSettingsWithRetry(int attempt) {
        if (currentInputMethodApp != null)
            return;

        boolean ok = refreshFromSettings();
        if (ok || attempt >= REFRESH_MAX_ATTEMPTS) {
            if (!ok)
                Log.w("读取当前输入法失败, 输入法可能被当成普通应用冻结");
            return;
        }

        Handlers.runtime.postDelayed(() -> initFromSettingsWithRetry(attempt + 1), REFRESH_INTERVAL_MS);
    }

    /**
     * 读取指定用户的默认输入法 id.
     *
     * <p>{@code Settings.Secure.getStringForUser} 是 @hide API, SDK 里不可直接引用; system_server
     * 里要按「当前目标用户」读取, 所以先反射调用隐藏方法, 失败再退回公开的 getString (user 0).
     */
    private static String readDefaultInputMethodId(Context context, int userId) {
        try {
            Object value = CakeReflection.callStaticMethod(
                    Settings.Secure.class, "getStringForUser",
                    new Class<?>[]{android.content.ContentResolver.class, String.class, int.class},
                    context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, userId);
            if (value instanceof String id && !id.isEmpty())
                return id;
        } catch (Throwable ignored) {
            // 隐藏 API 不可用: 退回公开方法.
        }
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    }

    /**
     * 从 Settings.Secure.DEFAULT_INPUT_METHOD 读取当前输入法并解冻.
     *
     * @return 是否成功解析并设置
     */
    public static boolean refreshFromSettings() {
        try {
            Context context = ActivityManagerService.getContext();
            if (context == null)
                return false;

            int userId = ActivityManagerService.getCurrentOrTargetUserId();
            String id = readDefaultInputMethodId(context, userId);
            if (id == null || id.isEmpty())
                return false;

            String packageName = id.contains("/")
                    ? id.substring(0, id.indexOf('/'))
                    : id;
            AppRecord appRecord = AppService.get(packageName, userId);
            if (appRecord == null)
                return false;

            Map<String, InputMethodInfo> methods = inputMethods;
            InputMethodInfo info = methods == null ? null : methods.get(id);
            boolean changed = setCurrentInputMethod(info, appRecord);
            if (changed) {
                Log.i("当前输入法: " + appRecord.getPackageNameWithUser());
                FreezerService.thaw(appRecord);
            }
            return true;
        } catch (Throwable throwable) {
            Log.w("读取当前输入法失败", throwable);
            return false;
        }
    }
}

package nep.timeline.cirno.configs.checkers;

import nep.timeline.cirno.CommonConstants;
import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.configs.settings.AppOptions;
import nep.timeline.cirno.configs.settings.ApplicationSettings;
import nep.timeline.cirno.entity.AppRecord;

public class AppConfigs {
    /**
     * 默认选项 (只读共享实例).
     *
     * <p>system_server 侧只需要读, 所以没有配置的应用直接返回这个实例, 不往配置里塞数据;
     * App 侧要改选项时必须走 {@code AppOptionsStore.getOrCreate()}, 不能改这个实例.
     */
    private static final AppOptions DEFAULT_OPTIONS = new AppOptions();

    public static String key(String packageName, int userId) {
        return packageName + "#" + userId;
    }

    public static boolean isWhiteApp(String pkg, int userId) {
        // 配置是异步读取的: 界面可能在配置加载完成之前就渲染应用列表, 这里必须做空判断.
        ApplicationSettings settings = GlobalVars.applicationSettings;
        if (settings == null || settings.whiteApps == null || pkg == null)
            return false;

        return settings.whiteApps.contains(pkg + "#" + userId);
    }

    /**
     * 读取应用选项 (只读, 绝不修改返回值).
     *
     * <p>没有单独配置时返回共享的默认选项: 参与冻结、播放音频时跳过冻结、
     * 允许内存回收、冻结全部进程.
     */
    public static AppOptions getOptions(String pkg, int userId) {
        ApplicationSettings settings = GlobalVars.applicationSettings;
        if (settings == null || settings.appOptions == null || pkg == null)
            return DEFAULT_OPTIONS;

        AppOptions options = settings.appOptions.get(key(pkg, userId));
        if (options == null)
            return DEFAULT_OPTIONS;

        options.sanitize();
        return options;
    }

    /** 应用是否允许自动冻结. */
    public static boolean isFreezeEnabled(String pkg, int userId) {
        return getOptions(pkg, userId).enabled;
    }

    /**
     * Google 服务是否被用户显式允许冻结.
     *
     * <p>默认返回 false: GMS / GSF / Play 商店等仍然受保护, 不会破坏 FCM.
     * 只对 {@link CommonConstants#isGoogleFamily(String)} 里的包生效, 其他应用一律 false.
     */
    public static boolean isGoogleFreezeAllowed(String pkg, int userId) {
        if (pkg == null || !CommonConstants.isGoogleFamily(pkg))
            return false;
        return getOptions(pkg, userId).allowFreezeGoogle;
    }

    /** 应用是否允许被内存回收模块回收. */
    public static boolean isReclaimAllowed(AppRecord appRecord) {
        if (appRecord == null || appRecord.getPackageName() == null)
            return true;
        return getOptions(appRecord.getPackageName(), appRecord.getUserId()).allowReclaim;
    }

    /**
     * 按应用的进程规则判断某个进程该不该被冻结.
     *
     * <p>进程名可能为 null (理论上不会), 这种情况按「全部冻结」处理, 保持旧行为.
     */
    public static boolean shouldFreezeProcess(AppOptions options, String processName) {
        if (options == null)
            return true;
        return options.shouldFreezeProcess(processName);
    }
}

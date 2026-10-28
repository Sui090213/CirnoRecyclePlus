package nep.timeline.cirno;

import nep.timeline.cirno.configs.settings.ApplicationSettings;
import nep.timeline.cirno.configs.settings.GlobalSettings;

public class GlobalVars {
    public static final String TAG = "CirnoRecycle";
    public static final String CONFIG = "CirnoRecycle";
    public final static String CONFIG_DIR = "/data/misc/" + GlobalVars.CONFIG;
    public final static String LOG_DIR = CONFIG_DIR + "/log";
    public static volatile ClassLoader classLoader;
    public static volatile GlobalSettings globalSettings = null;
    public static volatile ApplicationSettings applicationSettings = null;

    /**
     * App 进程上一次读取配置时, 是否发现「文件存在但解析失败」.
     *
     * <p>界面用它决定是否要提醒用户: 配置文件损坏 / 只写了一半时, 内存里可能是默认值,
     * 这时候随便点一个开关就会把默认值整份写回去, 覆盖掉可能还能手工修复的配置.
     * 由 {@code ConfigManagerJson.readConfigSU()} 维护.
     */
    public static volatile boolean configCorrupted = false;
}

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
}

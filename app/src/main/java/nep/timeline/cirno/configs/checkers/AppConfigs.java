package nep.timeline.cirno.configs.checkers;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.configs.settings.ApplicationSettings;

public class AppConfigs {
    public static boolean isWhiteApp(String pkg, int userId) {
        // 配置是异步读取的: 界面可能在配置加载完成之前就渲染应用列表, 这里必须做空判断.
        ApplicationSettings settings = GlobalVars.applicationSettings;
        if (settings == null || settings.whiteApps == null || pkg == null)
            return false;

        return settings.whiteApps.contains(pkg + "#" + userId);
    }
}

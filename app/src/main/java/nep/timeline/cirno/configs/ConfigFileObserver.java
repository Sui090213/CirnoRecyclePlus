package nep.timeline.cirno.configs;

import android.os.FileObserver;
import android.os.Handler;

import java.io.File;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.threads.Handlers;

public class ConfigFileObserver extends FileObserver {
    public ConfigFileObserver() {
        // 除了 MODIFY/MOVE_SELF, 也监听 CREATE / MOVED_TO / CLOSE_WRITE:
        // App 写配置用「临时文件 + mv」原子替换, mv 覆盖目标只会产生 MOVED_TO 事件.
        super(GlobalVars.CONFIG_DIR,
                DELETE | DELETE_SELF | MODIFY | MOVE_SELF | CREATE | MOVED_TO | CLOSE_WRITE);
        reInit();
        ConfigManager.readConfig();
    }

    @Override
    public void onEvent(int event, String path) {
        // 只关心两个配置文件; 同目录下的 foreground_guard.orig / *.tmp 等事件直接忽略,
        // 否则每次保存前台压制原值都会触发一次多余的配置重载.
        if (path != null
                && !path.equals("GlobalSettings.json")
                && !path.equals("ApplicationSettings.json"))
            return;

        Handler handler = Handlers.config;
        handler.removeCallbacksAndMessages(null);
        switch (event & ALL_EVENTS) {
            case DELETE:
            case DELETE_SELF:
            case MOVE_SELF: {
                handler.postDelayed(() -> {
                    // 目录被删除/移动后, 内核会移除 inotify watch; 必须重新注册,
                    // 否则之后 App 再写配置永远收不到事件 (热更新静默失效).
                    reInit();
                    try {
                        startWatching();
                    } catch (Throwable throwable) {
                        nep.timeline.cirno.log.Log.w("重新注册配置监听失败", throwable);
                    }
                    ConfigManager.readConfig();
                }, 2000);
                break;
            }
            case MODIFY:
            case CREATE:
            case MOVED_TO:
            case CLOSE_WRITE: {
                handler.postDelayed(ConfigManager::readConfig, 2000);
                break;
            }
            default:
                break;
        }
    }

    public void reInit() {
        File configDir = new File(GlobalVars.CONFIG_DIR);
        if (!configDir.exists())
            configDir.mkdir();
        File logDir = new File(GlobalVars.LOG_DIR);
        if (!logDir.exists())
            logDir.mkdir();
    }
}

package nep.timeline.cirno.configs;

public class ConfigManager {
    public static final ConfigManagerJson manager = new ConfigManagerJson();

    public static void readConfig() {
        // 只读不写: 之前的实现每次读完都调用 saveConfig(), 而 saveConfig 写文件又会触发
        // ConfigFileObserver 的 MODIFY 事件, 形成「写 -> 事件 -> 读 -> 再写」的 2 秒循环.
        // 配置默认值由 readConfig() 内部在文件缺失 / 格式错误时创建.
        manager.readConfig();
    }

    public static void saveConfig() {
        manager.saveConfig();
        manager.readConfig();
    }
}

package nep.timeline.cirno.configs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.topjohnwu.superuser.io.SuFile;

import java.io.File;
import java.io.IOException;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.configs.settings.ApplicationSettings;
import nep.timeline.cirno.configs.settings.GlobalSettings;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.utils.RWUtils;

public class ConfigManagerJson {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final String globalSettingsName = "GlobalSettings.json";
    private final String applicationSettingsName = "ApplicationSettings.json";

    /**
     * system_server 侧读取配置 (以 root 直接读写文件).
     *
     * <p>关键约定: 文件存在但内容不完整 / 解析失败时, **保留内存里上一次的配置**,
     * 绝不把默认值写回去. App 用 root 写 JSON 是截断后重写, 不是原子替换, 读到半截
     * 文件是很正常的, 直接重置会把用户配置冲掉.
     */
    public void readConfig() {
        try {
            File globalFile = new File(GlobalVars.CONFIG_DIR, globalSettingsName);
            if (!globalFile.exists()) {
                if (GlobalVars.globalSettings == null)
                    GlobalVars.globalSettings = new GlobalSettings();
                saveConfig();
            } else if (!applyGlobalSettings(RWUtils.readConfig(globalFile.getAbsolutePath()))) {
                Log.w("GlobalSettings.json 读取失败或内容不完整, 保留上一次的配置");
            }

            File applicationFile = new File(GlobalVars.CONFIG_DIR, applicationSettingsName);
            if (!applicationFile.exists()) {
                if (GlobalVars.applicationSettings == null)
                    GlobalVars.applicationSettings = new ApplicationSettings();
                saveConfig();
            } else if (!applyApplicationSettings(RWUtils.readConfig(applicationFile.getAbsolutePath()))) {
                Log.w("ApplicationSettings.json 读取失败或内容不完整, 保留上一次的配置");
            }
        } catch (RuntimeException e) {
            Log.w("读取配置异常, 保留上一次的配置: " + e);
        }

        // 极端情况下 (首次启动且文件都读失败) 也要保证非 null, 避免调用方 NPE.
        if (GlobalVars.globalSettings == null)
            GlobalVars.globalSettings = new GlobalSettings();
        if (GlobalVars.applicationSettings == null)
            GlobalVars.applicationSettings = new ApplicationSettings();
    }

    private boolean applyGlobalSettings(String json) {
        if (json == null)
            return false;
        try {
            GlobalSettings parsed = gson.fromJson(json, GlobalSettings.class);
            if (parsed == null)
                return false;
            GlobalVars.globalSettings = parsed;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean applyApplicationSettings(String json) {
        if (json == null)
            return false;
        try {
            ApplicationSettings parsed = gson.fromJson(json, ApplicationSettings.class);
            if (parsed == null)
                return false;
            GlobalVars.applicationSettings = parsed;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void saveConfig() {
        try {
            GlobalSettings globalSettings = GlobalVars.globalSettings;
            if (globalSettings != null) {
                String globalConfigStr = gson.toJson(GlobalVars.globalSettings);
                RWUtils.writeStringToFile(new File(GlobalVars.CONFIG_DIR, globalSettingsName), globalConfigStr);
            }
            ApplicationSettings applicationSettings = GlobalVars.applicationSettings;
            if (applicationSettings != null) {
                String applicationConfigStr = gson.toJson(GlobalVars.applicationSettings);
                RWUtils.writeStringToFile(new File(GlobalVars.CONFIG_DIR, applicationSettingsName), applicationConfigStr);
            }
        } catch (IOException e) {
            Log.e("Save Config", e);
        }
    }

    /**
     * App 进程侧读取配置 (通过 root shell 读).
     *
     * @return true 表示两个配置文件都存在且成功解析 (或至少成功读取到可用配置);
     * false 表示首次运行 (文件还没生成), 界面可以用默认值, 但应提示用户.
     */
    public boolean readConfigSU() {
        boolean filesPresent = true;

        try {
            SuFile globalFile = new SuFile(GlobalVars.CONFIG_DIR, globalSettingsName);
            if (!globalFile.exists()) {
                filesPresent = false;
                if (GlobalVars.globalSettings == null)
                    GlobalVars.globalSettings = new GlobalSettings();
            } else if (!applyGlobalSettings(RWUtils.readConfig(globalFile))) {
                Log.w("App 读取 GlobalSettings.json 失败, 保留上一次的配置");
                if (GlobalVars.globalSettings == null)
                    filesPresent = false;
            }

            SuFile applicationFile = new SuFile(GlobalVars.CONFIG_DIR, applicationSettingsName);
            if (!applicationFile.exists()) {
                filesPresent = false;
                if (GlobalVars.applicationSettings == null)
                    GlobalVars.applicationSettings = new ApplicationSettings();
            } else if (!applyApplicationSettings(RWUtils.readConfig(applicationFile))) {
                Log.w("App 读取 ApplicationSettings.json 失败, 保留上一次的配置");
                if (GlobalVars.applicationSettings == null)
                    filesPresent = false;
            }
        } catch (RuntimeException e) {
            Log.w("App 读取配置异常, 保留上一次的配置: " + e);
        }

        if (GlobalVars.globalSettings == null) {
            GlobalVars.globalSettings = new GlobalSettings();
            filesPresent = false;
        }
        if (GlobalVars.applicationSettings == null) {
            GlobalVars.applicationSettings = new ApplicationSettings();
            filesPresent = false;
        }

        return filesPresent;
    }

    public void saveConfigSU() {
        try {
            GlobalSettings globalSettings = GlobalVars.globalSettings;
            if (globalSettings != null) {
                String globalConfigStr = gson.toJson(GlobalVars.globalSettings);
                RWUtils.writeStringToFileSU(new SuFile(GlobalVars.CONFIG_DIR, globalSettingsName), globalConfigStr, false);
            }
            ApplicationSettings applicationSettings = GlobalVars.applicationSettings;
            if (applicationSettings != null) {
                String applicationConfigStr = gson.toJson(GlobalVars.applicationSettings);
                RWUtils.writeStringToFileSU(new SuFile(GlobalVars.CONFIG_DIR, applicationSettingsName), applicationConfigStr, false);
            }
        } catch (IOException e) {
            Log.e("Save Config", e);
        }
    }
}

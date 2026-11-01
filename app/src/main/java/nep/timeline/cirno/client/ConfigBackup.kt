package nep.timeline.cirno.client

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.settings.ApplicationSettings
import nep.timeline.cirno.configs.settings.GlobalSettings
import nep.timeline.cirno.utils.RWUtils
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 配置导出 / 导入.
 *
 * <p>导出包是一个 JSON:
 * - `globalSettings` / `applicationSettings`: 原始配置文件内容;
 * - `uiPrefs`: App 自己的界面偏好 (主题 / 背景透明度等, 不含背景图片文件);
 * - `sha256`: 对去掉该字段后的整个 JSON 计算的校验值.
 *
 * <p>导入前先校验 SHA-256, 再用 Gson 验证两份配置能正常解析; 写入前会把当前配置
 * 备份到同目录的 `*.bak-<时间戳>` 文件. 写入后重新读取校验, 失败时尝试回滚.
 */
object ConfigBackup {
    private const val FORMAT = "cirno-config"
    private const val SCHEMA = 1
    private const val MAX_BUNDLE_BYTES = 4 * 1024 * 1024
    private const val GLOBAL_FILE = "GlobalSettings.json"
    private const val APPLICATION_FILE = "ApplicationSettings.json"

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    data class Summary(
        val exportedAt: Long,
        val appVersion: String,
        val globalBytes: Int,
        val applicationBytes: Int,
        val hasUiPrefs: Boolean,
        val sha256: String,
    )

    data class ParsedBundle(
        val globalJson: String,
        val applicationJson: String,
        val uiPrefs: AppPrefs.UiPrefsSnapshot?,
        val summary: Summary,
    )

    /** 写出导出包到 SAF 的 Uri. */
    fun export(context: Context, uri: Uri): Result<Summary> = runCatching {
        ensureRoot()
        val globalJson = readConfigFile(GLOBAL_FILE) { gson.toJson(GlobalVars.globalSettings ?: GlobalSettings()) }
        val applicationJson = readConfigFile(APPLICATION_FILE) {
            gson.toJson(GlobalVars.applicationSettings ?: ApplicationSettings())
        }

        // 先做一次本地解析校验, 避免导出坏掉的配置.
        validateConfig(globalJson, GlobalSettings::class.java, "GlobalSettings.json")
        validateConfig(applicationJson, ApplicationSettings::class.java, "ApplicationSettings.json")

        val payload = JsonObject()
        payload.addProperty("format", FORMAT)
        payload.addProperty("schema", SCHEMA)
        payload.addProperty("exportedAt", System.currentTimeMillis())
        payload.addProperty("appVersion", BuildConfig.VERSION_NAME)
        payload.add("globalSettings", JsonParser.parseString(globalJson).asJsonObject)
        payload.add("applicationSettings", JsonParser.parseString(applicationJson).asJsonObject)
        payload.add("uiPrefs", uiPrefsToJson(AppPrefs.snapshot()))

        val hash = Sha256.text(gson.toJson(payload))
        payload.addProperty("sha256", hash)
        val text = gson.toJson(payload)

        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("无法打开导出文件")

        Summary(
            exportedAt = payload.get("exportedAt")?.asLong ?: 0L,
            appVersion = BuildConfig.VERSION_NAME,
            globalBytes = globalJson.toByteArray(Charsets.UTF_8).size,
            applicationBytes = applicationJson.toByteArray(Charsets.UTF_8).size,
            hasUiPrefs = true,
            sha256 = hash,
        )
    }

    /** 读取并校验导出包, 不写入任何文件. */
    fun readAndVerify(context: Context, uri: Uri): Result<ParsedBundle> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0)
                    break
                total += read
                if (total > MAX_BUNDLE_BYTES)
                    error("配置文件超过 4MB，拒绝导入")
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: error("无法打开导入文件")

        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: error("不是有效的 JSON 对象")
        if (root.get("format")?.asString != FORMAT)
            error("不是 Cirno Recycle+ 的配置导出包")
        if (root.get("schema")?.asInt != SCHEMA)
            error("不支持的配置包版本")

        val expectedHash = root.get("sha256")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            .orEmpty()
        if (expectedHash.isEmpty())
            error("配置包缺少 SHA-256 校验值")

        root.remove("sha256")
        val actualHash = Sha256.text(gson.toJson(root))
        if (!Sha256.matches(expectedHash, actualHash))
            error("配置包 SHA-256 校验失败，文件可能已损坏或被修改")

        val global = root.getAsJsonObject("globalSettings")
            ?: error("配置包缺少 globalSettings")
        val application = root.getAsJsonObject("applicationSettings")
            ?: error("配置包缺少 applicationSettings")
        val globalJson = gson.toJson(global)
        val applicationJson = gson.toJson(application)
        validateConfig(globalJson, GlobalSettings::class.java, "GlobalSettings.json")
        validateConfig(applicationJson, ApplicationSettings::class.java, "ApplicationSettings.json")

        ParsedBundle(
            globalJson = globalJson,
            applicationJson = applicationJson,
            uiPrefs = root.getAsJsonObject("uiPrefs")?.let { parseUiPrefs(it) },
            summary = Summary(
                exportedAt = root.get("exportedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                appVersion = root.get("appVersion")?.takeIf { !it.isJsonNull }?.asString ?: "",
                globalBytes = globalJson.toByteArray(Charsets.UTF_8).size,
                applicationBytes = applicationJson.toByteArray(Charsets.UTF_8).size,
                hasUiPrefs = root.has("uiPrefs"),
                sha256 = expectedHash,
            ),
        )
    }

    /**
     * 应用已经校验过的导入包.
     *
     * <p>会先把当前配置写成同目录的时间戳备份; 写入后调用 [ConfigManager.readConfigSU]
     * 重新解析, 失败则尝试用备份回滚.
     */
    fun apply(bundle: ParsedBundle): Result<Unit> = runCatching {
        ensureRoot()
        val oldGlobal = readConfigFileOrNull(GLOBAL_FILE)
        val oldApplication = readConfigFileOrNull(APPLICATION_FILE)

        val timestamp = System.currentTimeMillis()
        if (!backupConfig(GLOBAL_FILE, oldGlobal, timestamp) ||
            !backupConfig(APPLICATION_FILE, oldApplication, timestamp)
        ) {
            error("备份当前配置失败，已中止导入")
        }

        try {
            writeConfigFile(GLOBAL_FILE, bundle.globalJson)
            writeConfigFile(APPLICATION_FILE, bundle.applicationJson)
            val ok = ConfigManager.manager.readConfigSU()
            if (!ok)
                error("导入后配置文件无法解析")

            // 再读一次原始内容, 确认确实是导入包的内容, 而不是半截文件.
            val readGlobal = readConfigFileOrNull(GLOBAL_FILE)
            val readApplication = readConfigFileOrNull(APPLICATION_FILE)
            if (readGlobal != bundle.globalJson || readApplication != bundle.applicationJson)
                error("导入后读回内容与导出包不一致")

            bundle.uiPrefs?.let { AppPrefs.restoreSnapshot(it) }
        } catch (t: Throwable) {
            // 回滚: 有旧内容就写回, 没有就删除新写出来的文件.
            if (oldGlobal != null) runCatching { writeConfigFile(GLOBAL_FILE, oldGlobal) }
            else runCatching { SuFile(GlobalVars.CONFIG_DIR, GLOBAL_FILE).delete() }
            if (oldApplication != null) runCatching { writeConfigFile(APPLICATION_FILE, oldApplication) }
            else runCatching { SuFile(GlobalVars.CONFIG_DIR, APPLICATION_FILE).delete() }
            runCatching { ConfigManager.manager.readConfigSU() }
            throw t
        }
    }

    private fun ensureRoot() {
        val root = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        if (!root)
            error("需要 root 权限才能读写配置目录")
    }

    private fun readConfigFile(name: String, fallback: () -> String): String {
        val suFile = SuFile(GlobalVars.CONFIG_DIR, name)
        if (!suFile.exists())
            return fallback()
        return RWUtils.readConfig(suFile)?.takeIf { it.isNotBlank() }
            ?: error("读取 $name 失败")
    }

    private fun readConfigFileOrNull(name: String): String? =
        runCatching {
            val suFile = SuFile(GlobalVars.CONFIG_DIR, name)
            if (!suFile.exists()) null
            else RWUtils.readConfig(suFile)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun writeConfigFile(name: String, text: String) {
        RWUtils.writeStringToFileSUAtomic(SuFile(GlobalVars.CONFIG_DIR, name), text)
    }

    private fun backupConfig(name: String, text: String?, timestamp: Long): Boolean {
        if (text.isNullOrBlank())
            return true
        return runCatching {
            val backupName = "$name.bak-$timestamp"
            RWUtils.writeStringToFileSUAtomic(SuFile(GlobalVars.CONFIG_DIR, backupName), text)
        }.isSuccess
    }

    private fun <T> validateConfig(text: String, type: Class<T>, name: String) {
        if (text.length > MAX_BUNDLE_BYTES)
            error("$name 超过 4MB")
        val parsed = runCatching { gson.fromJson(text, type) }.getOrNull()
        if (parsed == null)
            error("$name 不是有效的 JSON 配置")
    }

    private fun uiPrefsToJson(snapshot: AppPrefs.UiPrefsSnapshot): JsonObject = JsonObject().apply {
        addProperty("themeMode", snapshot.themeMode)
        addProperty("monetEnabled", snapshot.monetEnabled)
        addProperty("themeSeed", snapshot.themeSeed)
        addProperty("autoCheckUpdate", snapshot.autoCheckUpdate)
        addProperty("sortBackgroundFirst", snapshot.sortBackgroundFirst)
        addProperty("showInactiveProcesses", snapshot.showInactiveProcesses)
        addProperty("backgroundEnabled", snapshot.backgroundEnabled)
        addProperty("backgroundAlpha", snapshot.backgroundAlpha.toDouble())
        addProperty("backgroundDim", snapshot.backgroundDim.toDouble())
        addProperty("backgroundPreset", snapshot.backgroundPreset)
        addProperty("backgroundImageIncluded", false)
    }

    private fun parseUiPrefs(json: JsonObject): AppPrefs.UiPrefsSnapshot {
        val base = AppPrefs.snapshot()
        fun int(name: String, fallback: Int): Int = runCatching {
            json.get(name)?.takeIf { !it.isJsonNull }?.asInt ?: fallback
        }.getOrDefault(fallback)
        fun bool(name: String, fallback: Boolean): Boolean = runCatching {
            json.get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: fallback
        }.getOrDefault(fallback)
        fun float(name: String, fallback: Float): Float = runCatching {
            json.get(name)?.takeIf { !it.isJsonNull }?.asFloat ?: fallback
        }.getOrDefault(fallback)

        return AppPrefs.UiPrefsSnapshot(
            themeMode = int("themeMode", base.themeMode).coerceIn(0, 2),
            monetEnabled = bool("monetEnabled", base.monetEnabled),
            themeSeed = int("themeSeed", base.themeSeed),
            autoCheckUpdate = bool("autoCheckUpdate", base.autoCheckUpdate),
            sortBackgroundFirst = bool("sortBackgroundFirst", base.sortBackgroundFirst),
            showInactiveProcesses = bool("showInactiveProcesses", base.showInactiveProcesses),
            backgroundEnabled = bool("backgroundEnabled", base.backgroundEnabled),
            backgroundAlpha = float("backgroundAlpha", base.backgroundAlpha).coerceIn(0.05f, 1f),
            backgroundDim = float("backgroundDim", base.backgroundDim).coerceIn(0f, 0.8f),
            backgroundPreset = int("backgroundPreset", base.backgroundPreset).coerceIn(0, 4),
        )
    }

    /** 给界面显示的人类可读时间. */
    fun formatTimestamp(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(timestamp))
}

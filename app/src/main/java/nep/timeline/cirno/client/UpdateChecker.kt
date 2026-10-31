package nep.timeline.cirno.client

import com.google.gson.JsonParser
import nep.timeline.cirno.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查.
 *
 * <p>从 GitHub 上仓库根目录的 `update.json` 读取最新版本 (和 Magisk 模块的
 * `updateJson` 用同一份文件):
 *
 * ```json
 * {
 *   "version": "v1.2.0",
 *   "versionCode": 12000,
 *   "zipUrl": "https://github.com/.../releases/download/v1.2.0/Cirno-Recycle-Plus-v1.2.0.zip",
 *   "apkUrl": "https://github.com/.../releases/download/v1.2.0/Cirno-Recycle-Plus-v1.2.0.apk",
 *   "releaseUrl": "https://github.com/Sui090213/CirnoRecyclePlus/releases/tag/v1.2.0",
 *   "changelog": "https://raw.githubusercontent.com/.../CHANGELOG.md"
 * }
 * ```
 *
 * <p>版本比较优先用 `versionCode` (和模块的版本号一致); 没有这个字段时退化为比较
 * 版本名里的数字. 网络请求全部在 IO 线程做, 失败只返回 [Result.Failed], 不抛异常.
 * <p>版本比较用**版本名**（`1.2.0` 这种）: APK 的 `versionCode` 是普通自增小整数，而模块 /
 * `update.json` 用的是 12000 这样的模块版本号，两者不可直接比较；版本名才是人看的、也是
 * Release tag 用的那个。
 */
object UpdateChecker {
    private const val TIMEOUT_MS = 8000
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_CHANGELOG_BYTES = 4096
    private const val MAX_CHANGELOG_CHARS = 1200

    /** update.json 的多个镜像: GitHub raw 在国内可能被拦截, 失败后自动尝试下一个. */
    private val UPDATE_URLS = listOf(
        "https://raw.githubusercontent.com/Sui090213/CirnoRecyclePlus/main/update.json",
        "https://cdn.jsdelivr.net/gh/Sui090213/CirnoRecyclePlus@main/update.json",
    )

    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val zipUrl: String?,
        val apkUrl: String?,
        val releaseUrl: String?,
        val changelog: String?,
    )

    sealed class Result {
        /** 发现新版本. */
        data class Available(val info: UpdateInfo) : Result()

        /** 已经是最新版本 (或远端版本比当前还旧). */
        data class UpToDate(val remoteVersion: String, val remoteVersionCode: Int) : Result()

        /** 检查失败 (断网 / 被墙 / update.json 格式不对). */
        data class Failed(val message: String) : Result()
    }

    /**
     * 检查更新. 必须在 IO 线程调用.
     *
     * @param currentVersionName 当前 App 的版本名, 默认取 [BuildConfig.VERSION_NAME]
     */
    fun check(
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): Result {
        var lastError = "无法连接更新服务器"

        for (url in UPDATE_URLS) {
            val json = runCatching { fetchText(url, MAX_BYTES) }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                continue
            } ?: continue

            val parsed = runCatching { parse(json) }.getOrNull()
            if (parsed == null) {
                lastError = "update.json 格式不正确"
                continue
            }

            val (version, versionCode, zipUrl, apkUrl, releaseUrl, changelogUrl) = parsed
            // 用版本名比较: 见类注释 (APK versionCode 和模块 versionCode 不是一个体系).
            val newer = compareVersionNames(version, currentVersionName) > 0
            if (!newer)
                return Result.UpToDate(version, versionCode)

            val changelog = changelogUrl?.let { link ->
                runCatching { fetchText(link, MAX_CHANGELOG_BYTES) }.getOrNull()
                    ?.trim()
                    ?.take(MAX_CHANGELOG_CHARS)
                    ?.takeIf { it.isNotBlank() }
            }

            return Result.Available(
                UpdateInfo(
                    version = version.ifBlank { currentVersionName },
                    versionCode = versionCode,
                    zipUrl = zipUrl,
                    apkUrl = apkUrl,
                    releaseUrl = releaseUrl,
                    changelog = changelog,
                )
            )
        }

        return Result.Failed(lastError)
    }

    /** 解析 update.json; 缺少 version 时返回 null. */
    private fun parse(json: String): ParsedUpdate? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        fun str(name: String): String? = runCatching {
            val element = root.get(name) ?: return@runCatching null
            if (element.isJsonNull) null else element.asString
        }.getOrNull()

        val version = str("version")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val versionCode = runCatching {
            val element = root.get("versionCode") ?: return@runCatching 0
            if (element.isJsonNull) 0 else element.asInt
        }.getOrDefault(0)

        return ParsedUpdate(
            version = version,
            versionCode = versionCode,
            zipUrl = str("zipUrl"),
            apkUrl = str("apkUrl"),
            releaseUrl = str("releaseUrl"),
            changelogUrl = str("changelog"),
        )
    }

    /** 按数字段比较版本名 (1.2.0 > 1.1.9). */
    fun compareVersionNames(left: String, right: String): Int {
        val leftParts = versionNumbers(left)
        val rightParts = versionNumbers(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val a = leftParts.getOrElse(index) { 0 }
            val b = rightParts.getOrElse(index) { 0 }
            if (a != b)
                return a.compareTo(b)
        }
        return 0
    }

    private fun versionNumbers(value: String): List<Int> =
        Regex("\\d+").findAll(value.trim().removePrefix("v").removePrefix("V"))
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

    /** 读一个文本 URL; 非 2xx / 内容为空时返回 null. */
    private fun fetchText(url: String, maxBytes: Int): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CirnoRecyclePlus/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299)
                return null
            val bytes = connection.inputStream.use { readLimited(it, maxBytes) }
            if (bytes.isEmpty())
                return null
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (read <= 0)
                break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class ParsedUpdate(
        val version: String,
        val versionCode: Int,
        val zipUrl: String?,
        val apkUrl: String?,
        val releaseUrl: String?,
        val changelogUrl: String?,
    )
}

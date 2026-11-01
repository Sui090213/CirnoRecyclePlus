package nep.timeline.cirno.client

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 本地文件 / 文本的 SHA-256 工具.
 *
 * <p>用于两类场景:
 * - 背景图片导入后记录哈希, 每次加载前校验, 防止文件被其他程序替换或写坏;
 * - 配置导出包写入 sha256 字段, 导入前先校验, 避免把损坏 / 被改过的 JSON 写进系统配置.
 *
 * <p>比较时使用常量时间比较, 避免把哈希当成密码学凭据时被时序攻击
 * (本地场景其实不严格需要, 但成本几乎为零).
 */
object Sha256 {
    private val HEX = "0123456789abcdef".toCharArray()

    /** 计算字节数组的 SHA-256, 返回小写十六进制. */
    fun bytes(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return toHex(digest)
    }

    /** 计算文本的 SHA-256 (UTF-8), 返回小写十六进制. */
    fun text(value: String): String = bytes(value.toByteArray(Charsets.UTF_8))

    /** 计算文件的 SHA-256; 文件不存在 / 读取失败返回 null. */
    fun file(file: File?): String? {
        if (file == null || !file.isFile)
            return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0)
                        break
                    digest.update(buffer, 0, read)
                }
            }
            toHex(digest.digest())
        }.getOrNull()
    }

    /** 常量时间比较两个十六进制哈希; 任一为空返回 false. */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank())
            return false
        val left = expected.trim().lowercase()
        val right = actual.trim().lowercase()
        if (left.length != right.length)
            return false
        var diff = 0
        for (index in left.indices)
            diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }

    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }
}

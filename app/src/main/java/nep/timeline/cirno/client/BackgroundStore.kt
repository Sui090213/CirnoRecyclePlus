package nep.timeline.cirno.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * 自定义背景的本地存储.
 *
 * <p>选中的图片 / 网络图片都会先解码、按最长边缩放，再压成 JPEG 存到应用私有目录。
 * 这样不依赖 SAF 的持久化权限，重启后仍然能读到；也不会把几千万像素的原图直接塞进
 * Compose 的背景层导致 OOM.
 *
 * <p>只处理图片，不申请存储权限；选择图片走系统 Photo Picker / 文件选择器.
 */
object BackgroundStore {
    private const val FILE_NAME = "custom_background.jpg"
    private const val MAX_EDGE = 2560
    private const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

    /** 保存成功后的结果. */
    data class SavedBackground(
        val path: String,
        val sha256: String,
    )

    /**
     * 加载结果.
     *
     * <p>[hashMatched] 为 false 表示文件哈希和 [AppPrefs.backgroundImageHash] 对不上,
     * 此时 [bitmap] 为 null, 调用方应提示用户重新选择背景.
     */
    data class LoadedBackground(
        val bitmap: ImageBitmap?,
        val actualHash: String?,
        val hashMatched: Boolean,
    )

    /** 自定义背景文件的绝对路径；文件不存在时返回 null. */
    fun currentFile(context: Context): File {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.isFile && file.length() > 0L) file else file
    }

    /**
     * 从 content:// / file:// 导入一张图片.
     *
     * @return 保存后的文件路径 + SHA-256; 失败返回 null.
     */
    fun importFromUri(context: Context, uri: Uri): SavedBackground? {
        return runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                applyTargetSize(decoder, info.size.width, info.size.height)
            }
            save(context, bitmap)
        }.getOrNull()
    }

    /**
     * 从 http(s) 链接下载并导入一张图片.
     *
     * <p>只支持 http/https，限制 32MB，避免误填链接把内存吃满.
     */
    fun importFromUrl(context: Context, rawUrl: String, referer: String? = null): SavedBackground? {
        return runCatching {
            val url = URL(rawUrl.trim())
            require(url.protocol == "https") { "只支持 https 图片链接" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CirnoRecyclePlus/Background")
                if (!referer.isNullOrBlank())
                    setRequestProperty("Referer", referer)
            }
            try {
                if (connection.responseCode !in 200..299)
                    error("HTTP ${connection.responseCode}")
                val length = connection.contentLengthLong
                if (length > MAX_DOWNLOAD_BYTES)
                    error("图片超过 32MB")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0)
                            break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES)
                            error("图片超过 32MB")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val bitmap = decodeBytes(bytes)
                save(context, bitmap)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 读取已保存的背景图，按最长边 [maxEdge] 采样，避免大图占用过多内存.
     *
     * <p>读取前先校验 [expectedSha256]；老版本没有记录哈希时接受当前文件并返回实际哈希,
     * 由调用方补写基线.
     */
    fun loadImage(
        path: String?,
        expectedSha256: String?,
        maxEdge: Int = 2048,
    ): LoadedBackground? {
        if (path.isNullOrBlank())
            return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L)
            return null

        val actualHash = Sha256.file(file) ?: return null
        if (!expectedSha256.isNullOrBlank() && !Sha256.matches(expectedSha256, actualHash)) {
            return LoadedBackground(
                bitmap = null,
                actualHash = actualHash,
                hashMatched = false,
            )
        }

        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge)
                sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)?.asImageBitmap()
        }.getOrNull()

        return LoadedBackground(
            bitmap = bitmap,
            actualHash = actualHash,
            hashMatched = true,
        )
    }

    /**
     * 只校验文件哈希, 不 Decode; 供设置页显示「背景文件是否被替换」.
     *
     * @return true 校验通过 / 还没有基线; false 哈希不匹配; null 文件不存在.
     */
    fun verifyHash(path: String?, expectedSha256: String?): Boolean? {
        if (path.isNullOrBlank())
            return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L)
            return null
        val actual = Sha256.file(file) ?: return null
        if (expectedSha256.isNullOrBlank())
            return true
        return Sha256.matches(expectedSha256, actual)
    }

    /** 删除自定义背景文件. */
    fun clear(context: Context) {
        runCatching { currentFile(context).delete() }
    }

    private fun applyTargetSize(decoder: ImageDecoder, width: Int, height: Int) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
        }
        val longest = maxOf(width, height)
        if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longest
            decoder.setTargetSize(
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            applyTargetSize(decoder, info.size.width, info.size.height)
        }
    }

    private fun save(context: Context, bitmap: Bitmap): SavedBackground {
        val file = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "图片压缩失败" }
        }
        if (file.exists() && !file.delete())
            error("无法覆盖旧背景")
        check(temp.renameTo(file)) { "背景保存失败" }

        val sha256 = Sha256.file(file) ?: error("无法计算背景文件哈希")
        return SavedBackground(file.absolutePath, sha256)
    }
}

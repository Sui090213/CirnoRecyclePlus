package nep.timeline.cirno.client

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * 应用图标内存缓存.
 *
 * <p>列表滚动时如果在主线程调用 `loadIcon()` / `toBitmap()`，会明显掉帧；这里统一
 * 在 IO 线程解码，主线程只读缓存。缓存数量按 96 个应用控制，约十几 MB。
 */
object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(96)

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(packageManager: PackageManager, appInfo: ApplicationInfo): ImageBitmap? {
        get(appInfo.packageName)?.let { return it }
        return runCatching {
            appInfo.loadIcon(packageManager).toBitmap().asImageBitmap()
        }.getOrNull()?.also { cache.put(appInfo.packageName, it) }
    }
}

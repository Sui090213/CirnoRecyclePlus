package nep.timeline.cirno.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 在线图库里的一张壁纸. */
data class OnlineWallpaper(
    val id: String,
    val thumbUrl: String,
    val fullUrl: String,
    val pageUrl: String?,
    val author: String?,
    val resolution: String?,
    val source: String,
)

/** 一页在线图库结果. */
data class OnlineGalleryPage(
    val items: List<OnlineWallpaper>,
    val page: Int,
    val lastPage: Int,
)

/**
 * 在线壁纸源.
 *
 * <p>支持两个不需要 API key 的公共源:
 * - Wallhaven: 支持关键词搜索 / 分页, 默认只取 SFW、general 分类;
 * - Lorem Picsum: 无搜索, 但稳定、无需 key, 作为备用源.
 *
 * <p>网络请求都在 IO 线程做, 失败返回 [Result.failure], 不抛给 Compose.
 */
object OnlineGalleryRepository {
    private const val TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val PAGE_SIZE = 24

    suspend fun searchWallhaven(query: String, page: Int): Result<OnlineGalleryPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safePage = page.coerceAtLeast(1)
                val safeQuery = query.trim()
                val sort = if (safeQuery.isEmpty()) "hot" else "relevance"
                val encoded = URLEncoder.encode(safeQuery, "UTF-8")
                val url = "https://wallhaven.cc/api/v1/search" +
                    "?q=$encoded" +
                    "&categories=100" +
                    "&purity=100" +
                    "&sorting=$sort" +
                    "&page=$safePage"
                val json = fetchJson(url) ?: error("Wallhaven 没有返回内容")
                val root = JsonParser.parseString(json).asJsonObject
                val data = root.getAsJsonArray("data") ?: error("Wallhaven 返回格式不正确")
                val meta = root.getAsJsonObject("meta")
                val lastPage = meta?.get("last_page")?.takeIf { !it.isJsonNull }?.asInt
                    ?: safePage
                val items = buildList {
                    for (element in data) {
                        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val path = item.str("path") ?: continue
                        val thumbs = item.getAsJsonObject("thumbs")
                        val thumb = thumbs?.str("large")
                            ?: thumbs?.str("original")
                            ?: path
                        val uploader = item.getAsJsonObject("uploader")
                        add(
                            OnlineWallpaper(
                                id = item.str("id") ?: path,
                                thumbUrl = thumb,
                                fullUrl = path,
                                pageUrl = item.str("url"),
                                author = uploader?.str("username"),
                                resolution = item.str("resolution"),
                                source = "Wallhaven",
                            )
                        )
                    }
                }
                OnlineGalleryPage(items = items, page = safePage, lastPage = lastPage.coerceAtLeast(safePage))
            }
        }

    suspend fun listPicsum(page: Int): Result<OnlineGalleryPage> = withContext(Dispatchers.IO) {
        runCatching {
            val safePage = page.coerceAtLeast(1)
            val url = "https://picsum.photos/v2/list?page=$safePage&limit=$PAGE_SIZE"
            val json = fetchJson(url) ?: error("Picsum 没有返回内容")
            val array = JsonParser.parseString(json).asJsonArray
            val items = buildList {
                for (element in array) {
                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val id = item.str("id") ?: continue
                    val width = item.get("width")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    val height = item.get("height")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    add(
                        OnlineWallpaper(
                            id = id,
                            thumbUrl = "https://picsum.photos/id/$id/240/420",
                            fullUrl = "https://picsum.photos/id/$id/1080/2400",
                            pageUrl = item.str("url"),
                            author = item.str("author"),
                            resolution = if (width > 0 && height > 0) "$width x $height" else null,
                            source = "Picsum",
                        )
                    )
                }
            }
            // Picsum 的 list 接口没有 last_page 字段: 返回不满一页就认为到底了.
            OnlineGalleryPage(
                items = items,
                page = safePage,
                lastPage = if (items.size < PAGE_SIZE) safePage else safePage + 1,
            )
        }
    }

    private fun fetchJson(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CirnoRecyclePlus/OnlineGallery")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://wallhaven.cc/")
        }
        try {
            if (connection.responseCode !in 200..299)
                error("HTTP ${connection.responseCode}")
            val input = connection.inputStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            input.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0)
                        break
                    total += read
                    if (total > MAX_BYTES)
                        error("返回内容过大")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.str(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive)
            return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

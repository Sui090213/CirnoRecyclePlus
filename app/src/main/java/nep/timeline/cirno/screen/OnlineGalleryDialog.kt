package nep.timeline.cirno.screen

import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.BackgroundStore
import nep.timeline.cirno.client.OnlineGalleryPage
import nep.timeline.cirno.client.OnlineGalleryRepository
import nep.timeline.cirno.client.OnlineWallpaper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private enum class GallerySource(val label: String) {
    WALLHAVEN("Wallhaven"),
    PICSUM("Picsum"),
}

/** 缩略图内存缓存: 位图很小 (240x420), 48 张大约十几 MB, 关闭图库后由 GC 回收. */
private object GalleryThumbCache {
    val cache = LruCache<String, ImageBitmap>(32)
}

/**
 * 全屏在线图库.
 *
 * <p>Wallhaven 支持关键词搜索, Picsum 作为无需搜索的备用源；两个源都不需要 API key.
 * 点击缩略图会下载原图到本地应用私有目录, 记录 SHA-256 后应用为背景.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineGalleryDialog(
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(GallerySource.WALLHAVEN) }
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var lastPage by remember { mutableIntStateOf(1) }
    var items by remember { mutableStateOf<List<OnlineWallpaper>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, submittedQuery, page, refreshToken) {
        loading = true
        error = null
        val result = if (source == GallerySource.WALLHAVEN) {
            OnlineGalleryRepository.searchWallhaven(submittedQuery, page)
        } else {
            OnlineGalleryRepository.listPicsum(page)
        }
        result.onSuccess { resultPage: OnlineGalleryPage ->
            items = resultPage.items
            lastPage = resultPage.lastPage
            if (resultPage.items.isEmpty())
                error = "这一页没有结果，试试换关键词或上一页"
        }.onFailure {
            items = emptyList()
            error = it.message ?: "加载失败"
        }
        loading = false
    }

    fun downloadAndApply(item: OnlineWallpaper) {
        if (downloadingId != null)
            return
        downloadingId = item.id
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    BackgroundStore.importFromUrl(
                        context,
                        item.fullUrl,
                        referer = if (item.source == "Wallhaven") "https://wallhaven.cc/" else null,
                    )
                }.getOrNull()
            }
            downloadingId = null
            if (saved != null) {
                AppPrefs.updateBackgroundImage(saved.path, saved.sha256)
                Toast.makeText(context, "背景已下载并应用 (SHA-256 已记录)", Toast.LENGTH_SHORT).show()
                onApplied()
            } else {
                Toast.makeText(context, "下载或解码失败，换一张试试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = { if (downloadingId == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        title = { Text("在线图库") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = downloadingId == null) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GallerySource.entries.forEach { entry ->
                            FilterChip(
                                selected = source == entry,
                                onClick = {
                                    if (source != entry) {
                                        source = entry
                                        page = 1
                                        // 切换源时清掉上一源的搜索结果, 避免看起来像"搜索没生效".
                                        query = ""
                                        submittedQuery = ""
                                    }
                                },
                                label = { Text(entry.label) }
                            )
                        }
                    }

                    if (source == GallerySource.WALLHAVEN) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("搜索壁纸，例如 minimal / city") },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = {
                                    page = 1
                                    submittedQuery = query
                                    refreshToken++
                                }
                            ) {
                                Text("搜索")
                            }
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Picsum 不支持关键词搜索，直接翻页浏览。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { refreshToken++ }) { Text("重试") }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items, key = { it.source + ":" + it.id }) { item ->
                                GalleryCard(
                                    item = item,
                                    downloading = downloadingId == item.id,
                                    enabled = downloadingId == null,
                                    onClick = { downloadAndApply(item) },
                                )
                            }
                        }
                        if (loading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        if (!loading && items.isEmpty() && error == null) {
                            Text(
                                text = "没有结果",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { if (page > 1) page-- },
                            enabled = !loading && page > 1 && downloadingId == null
                        ) { Text("上一页") }
                        Text(
                            text = "第 $page / $lastPage 页",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { if (page < lastPage) page++ },
                            enabled = !loading && page < lastPage && downloadingId == null
                        ) { Text("下一页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(
    item: OnlineWallpaper,
    downloading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            RemoteThumbnail(
                url = item.thumbUrl,
                contentDescription = (item.author ?: item.source) + " 壁纸",
            )
            if (downloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        Text(
            text = item.author ?: item.source,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
        Text(
            text = listOfNotNull(item.source, item.resolution).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RemoteThumbnail(url: String, contentDescription: String) {
    var bitmap by remember(url) { mutableStateOf(GalleryThumbCache.cache.get(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (bitmap != null)
            return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadThumbnail(url) }
        if (loaded != null) {
            GalleryThumbCache.cache.put(url, loaded)
            bitmap = loaded
        } else {
            failed = true
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Text(
            text = if (failed) "无图" else "加载中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun loadThumbnail(url: String): ImageBitmap? {
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CirnoRecyclePlus/OnlineGallery")
            setRequestProperty("Referer", "https://wallhaven.cc/")
        }
        try {
            if (connection.responseCode !in 200..299)
                return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0)
                        break
                    total += read
                    if (total > 3 * 1024 * 1024)
                        error("缩略图过大")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

package nep.timeline.cirno.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.BackgroundStore
import nep.timeline.cirno.ui.BackgroundArtwork
import nep.timeline.cirno.ui.BUILTIN_BACKGROUNDS

/**
 * 自定义背景设置卡.
 *
 * <p>支持三种来源：
 * <ul>
 *     <li>本地相册 / 文件选择器（图片会复制到应用私有目录，不依赖临时权限）；</li>
 *     <li>网络链接 / 在线图库（下载后限制 32MB，并记录 SHA-256 用于加载时校验）；</li>
 *     <li>内置渐变（不联网也能用）。</li>
 * </ul>
 *
 * <p>透明度 / 压暗滑条拖动时实时更新全屏背景，松手才写 SharedPreferences，
 * 避免每帧都落盘.
 */
@Composable
fun BackgroundSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var integrityError by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun importUri(uri: android.net.Uri) {
        if (busy)
            return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { BackgroundStore.importFromUri(context, uri) }.getOrNull()
            }
            busy = false
            if (saved != null) {
                AppPrefs.updateBackgroundImage(saved.path, saved.sha256)
                integrityError = null
                toast("背景已应用")
            } else {
                toast("图片读取失败，换一张试试")
            }
        }
    }

    fun importUrl(url: String) {
        if (busy)
            return
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) {
            urlError = "链接需要以 https:// 开头"
            return
        }
        busy = true
        urlError = null
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { BackgroundStore.importFromUrl(context, trimmed) }.getOrNull()
            }
            busy = false
            if (saved != null) {
                AppPrefs.updateBackgroundImage(saved.path, saved.sha256)
                integrityError = null
                showUrlDialog = false
                toast("在线背景已下载并应用")
            } else {
                urlError = "下载或解码失败，检查链接是否是可访问的图片"
                if (!showUrlDialog)
                    toast(urlError!!)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null)
            importUri(uri)
    }

    // 文件级校验: 加载前先看 SHA-256 是否和导入时记录的一致.
    LaunchedEffect(AppPrefs.backgroundImagePath, AppPrefs.backgroundImageHash) {
        val path = AppPrefs.backgroundImagePath
        if (path.isNullOrBlank()) {
            integrityError = null
            return@LaunchedEffect
        }
        val status = withContext(Dispatchers.IO) {
            BackgroundStore.verifyHash(path, AppPrefs.backgroundImageHash)
        }
        integrityError = when (status) {
            false -> "背景文件哈希校验失败，文件可能已被替换或写坏；请重新选择背景。"
            null -> "背景文件不存在，请重新选择背景。"
            true -> null
        }
    }

    SettingsCard("自定义背景") {
        SettingsSwitchRow(
            title = "启用自定义背景",
            subtitle = "背景图会铺满整个 App，设置页、列表、底栏都会透出；关闭后恢复纯色背景",
            checked = AppPrefs.backgroundEnabled,
            onCheckedChange = { AppPrefs.updateBackgroundEnabled(it) }
        )

        if (!AppPrefs.backgroundEnabled) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = "打开后可以选择本地图片、导入网络图片，或者直接用下面的内置渐变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SettingsCard
        }

        Spacer(Modifier.size(10.dp))

        // 预览：和全屏背景使用同一套绘制逻辑，所见即所得.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            BackgroundArtwork(
                path = AppPrefs.backgroundImagePath,
                expectedSha256 = AppPrefs.backgroundImageHash,
                preset = AppPrefs.backgroundPreset,
                alpha = AppPrefs.backgroundAlpha,
                dim = AppPrefs.backgroundDim,
                modifier = Modifier.fillMaxSize(),
                onIntegrityError = { error -> integrityError = error },
            )
            if (AppPrefs.backgroundImagePath.isNullOrBlank() && AppPrefs.backgroundPreset == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有选择背景",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        integrityError?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { picker.launch("image/*") },
                enabled = !busy
            ) {
                Text("选择图片")
            }
            TextButton(onClick = {
                urlInput = ""
                urlError = null
                showUrlDialog = true
            }, enabled = !busy) {
                Text("网络链接")
            }
            if (!AppPrefs.backgroundImagePath.isNullOrBlank()) {
                TextButton(onClick = {
                    BackgroundStore.clear(context)
                    AppPrefs.updateBackgroundImage(null)
                    toast("已移除自定义图片")
                }, enabled = !busy) {
                    Text("移除图片")
                }
            }
        }

        Spacer(Modifier.size(2.dp))
        Text(
            text = if (AppPrefs.backgroundImagePath.isNullOrBlank())
                "内置渐变"
            else
                "内置渐变（选择后会替换当前图片）",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.size(4.dp))
        // 选择内置渐变时清掉自定义图片: 否则 BackgroundArtwork 会优先画旧图片,
        // 用户会以为"选了渐变没反应".
        fun selectPreset(preset: Int) {
            BackgroundStore.clear(context)
            AppPrefs.updateBackgroundImage(null)
            AppPrefs.updateBackgroundPreset(preset)
            integrityError = null
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = AppPrefs.backgroundPreset == 0 && AppPrefs.backgroundImagePath.isNullOrBlank(),
                    onClick = { selectPreset(0) },
                    label = { Text("无") }
                )
            }
            items(BUILTIN_BACKGROUNDS.size) { index ->
                val preset = index + 1
                FilterChip(
                    selected = AppPrefs.backgroundPreset == preset,
                    onClick = { selectPreset(preset) },
                    label = { Text(BUILTIN_BACKGROUNDS[index].name) }
                )
            }
        }

        Spacer(Modifier.size(12.dp))
        Text(
            text = "在线图库",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "从 Wallhaven / Lorem Picsum 在线搜索、翻页选择，下载后保存到本地并记录 SHA-256。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(6.dp))
        OutlinedButton(
            onClick = { showGallery = true },
            enabled = !busy
        ) {
            Text("打开在线图库")
        }

        Spacer(Modifier.size(12.dp))
        Text(
            text = "背景不透明度 ${(AppPrefs.backgroundAlpha * 100).toInt()}%",
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = AppPrefs.backgroundAlpha,
            onValueChange = { AppPrefs.updateBackgroundAlpha(it, persist = false) },
            onValueChangeFinished = { AppPrefs.commitBackgroundAlpha() },
            valueRange = 0.05f..1f
        )
        Text(
            text = "越小越接近原来的纯色背景，越大背景图越明显。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.size(8.dp))
        Text(
            text = "背景压暗 ${(AppPrefs.backgroundDim * 100).toInt()}%",
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = AppPrefs.backgroundDim,
            onValueChange = { AppPrefs.updateBackgroundDim(it, persist = false) },
            onValueChangeFinished = { AppPrefs.commitBackgroundDim() },
            valueRange = 0f..0.8f
        )
        Text(
            text = "背景太亮时调高一点，保证文字仍然清楚。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showUrlDialog = false },
            title = { Text("从网络添加背景") },
            text = {
                Column {
                    Text(
                        text = "填入可直接访问的 https 图片链接。图片会下载到本地，之后离线也能显示。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            urlError = null
                        },
                        label = { Text("图片链接") },
                        singleLine = true,
                        isError = urlError != null,
                        enabled = !busy
                    )
                    urlError?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { importUrl(urlInput) },
                    enabled = !busy
                ) {
                    Text(if (busy) "下载中…" else "下载并应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }, enabled = !busy) {
                    Text("取消")
                }
            }
        )
    }

    if (showGallery) {
        OnlineGalleryDialog(
            onDismiss = { showGallery = false },
            onApplied = {
                showGallery = false
                integrityError = null
            }
        )
    }
}

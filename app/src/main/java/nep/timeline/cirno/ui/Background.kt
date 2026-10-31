package nep.timeline.cirno.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.BackgroundStore
import java.io.File

/** 内置渐变背景: 不依赖网络和图片，默认就能看到效果. */
data class BuiltinBackground(
    val name: String,
    val colors: List<Color>,
)

/**
 * 内置背景列表，索引从 1 开始对应 [AppPrefs.backgroundPreset].
 *
 * <p>颜色都是手调的，避免在线图片不可用时背景设置页一片空白.
 */
val BUILTIN_BACKGROUNDS: List<BuiltinBackground> = listOf(
    BuiltinBackground(
        name = "初雪",
        colors = listOf(Color(0xFF071528), Color(0xFF1D4F8C), Color(0xFFB8E4FF)),
    ),
    BuiltinBackground(
        name = "极光",
        colors = listOf(Color(0xFF061A1A), Color(0xFF0F766E), Color(0xFF7DD3FC)),
    ),
    BuiltinBackground(
        name = "樱夜",
        colors = listOf(Color(0xFF160B1E), Color(0xFF6D2848), Color(0xFFF9A8D4)),
    ),
    BuiltinBackground(
        name = "深空",
        colors = listOf(Color(0xFF03040A), Color(0xFF1E1B4B), Color(0xFF6366F1)),
    ),
)

/** 背景是否真正有内容可画 (启用 + 有可用图片或内置渐变). */
fun hasCustomBackground(): Boolean {
    if (!AppPrefs.backgroundEnabled)
        return false
    val path = AppPrefs.backgroundImagePath
    val imageReady = !path.isNullOrBlank() && runCatching { File(path).isFile }.getOrDefault(false)
    return imageReady || AppPrefs.backgroundPreset in 1..BUILTIN_BACKGROUNDS.size
}

/**
 * 应用根背景.
 *
 * <p>背景图片画在所有 Scaffold 之下；主题色里的 surface / background 会在
 * [CirnoTheme] 里变成半透明，所以设置页、列表、底栏都会透出背景，而不是只在
 * 第一页顶部露一条.
 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val active = hasCustomBackground()
    Box(modifier = Modifier.fillMaxSize()) {
        if (active) {
            // 先铺一层不透明底色，避免半透明主题色直接透出系统窗口.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 1f))
            )
            BackgroundArtwork(
                path = AppPrefs.backgroundImagePath,
                preset = AppPrefs.backgroundPreset,
                alpha = AppPrefs.backgroundAlpha,
                dim = AppPrefs.backgroundDim,
                modifier = Modifier.fillMaxSize()
            )
        }
        content()
    }
}

/**
 * 只画背景层，不包内容；设置页的预览也用它.
 *
 * @param alpha 图片 / 渐变的不透明度
 * @param dim 背景压暗程度，0..0.8
 */
@Composable
fun BackgroundArtwork(
    path: String?,
    preset: Int,
    alpha: Float,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { BackgroundStore.loadImage(path) }
        }
    }

    Box(modifier = modifier) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(alpha.coerceIn(0f, 1f))
            )
        } else {
            val builtin = BUILTIN_BACKGROUNDS.getOrNull(preset - 1)
            if (builtin != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha.coerceIn(0f, 1f))
                        .background(
                            Brush.verticalGradient(colors = builtin.colors)
                        )
                )
            }
        }

        // 顶部和底部各压一点，让状态栏图标 / 底栏文字在亮色图片上也看得清.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = (dim * 0.55f).coerceIn(0f, 0.8f)),
                        0.35f to Color.Black.copy(alpha = (dim * 0.18f).coerceIn(0f, 0.8f)),
                        1f to Color.Black.copy(alpha = dim.coerceIn(0f, 0.8f))
                    )
                )
        )
    }
}

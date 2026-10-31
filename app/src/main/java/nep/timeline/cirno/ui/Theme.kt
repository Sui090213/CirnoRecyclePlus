package nep.timeline.cirno.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import nep.timeline.cirno.client.AppPrefs

/** 主题模式: 跟随系统 / 强制浅色 / 强制深色. */
enum class ThemeMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/** Material3 自带的两套基准配色, 自定义主题色时只覆盖有彩色 / 表面相关的字段. */
private val LIGHT_BASE = lightColorScheme()
private val DARK_BASE = darkColorScheme()

/**
 * 全局主题入口: 所有 Activity 的 setContent 都包一层它.
 *
 * <ul>
 *     <li>主题模式跟随系统 / 强制浅色 / 强制深色;</li>
 *     <li>莫奈取色: Android 12+ 的 dynamicLight/DarkColorScheme, 从壁纸取色;</li>
 *     <li>自定义主题色: 用种子色实时生成一套 Material3 配色 (不依赖第三方库).</li>
 * </ul>
 *
 * <p>状态栏 / 导航栏图标颜色会跟着深浅色切换, 否则深色主题下会出现深色图标配深色背景.
 */
@Composable
fun CirnoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = AppPrefs.themeMode
    val monet = AppPrefs.monetEnabled
    val seed = AppPrefs.themeSeed

    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (monet) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        seedColorScheme(Color(seed), dark)
    }

    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/**
 * 用种子色生成一套 Material3 配色.
 *
 * <p>不引入额外的取色库: 把种子色转成 HSL, 再按 Material3 的明度规则生成
 * primary / secondary / tertiary / surface 系列. 色相始终保持种子色的色相,
 * 所以换什么颜色, 整体风格都会跟着变.
 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1].coerceAtLeast(0.30f)

    val base = if (dark) DARK_BASE else LIGHT_BASE

    fun tone(hueOffset: Float, saturation: Float, lightness: Float): Color {
        val h = ((hue + hueOffset) % 360f + 360f) % 360f
        return Color.hsl(h, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
    }

    val primary = tone(0f, maxOf(sat, 0.42f), if (dark) 0.78f else 0.42f)
    val onPrimary = tone(0f, sat, if (dark) 0.12f else 0.99f)
    val primaryContainer = tone(0f, sat * 0.85f, if (dark) 0.28f else 0.90f)
    val onPrimaryContainer = tone(0f, sat * 0.90f, if (dark) 0.92f else 0.12f)

    val secondary = tone(22f, maxOf(sat * 0.72f, 0.30f), if (dark) 0.74f else 0.40f)
    val onSecondary = tone(22f, sat * 0.80f, if (dark) 0.12f else 0.99f)
    val secondaryContainer = tone(22f, sat * 0.60f, if (dark) 0.26f else 0.90f)
    val onSecondaryContainer = tone(22f, sat * 0.85f, if (dark) 0.92f else 0.12f)

    val tertiary = tone(55f, maxOf(sat * 0.85f, 0.35f), if (dark) 0.76f else 0.40f)
    val onTertiary = tone(55f, sat * 0.80f, if (dark) 0.12f else 0.99f)
    val tertiaryContainer = tone(55f, sat * 0.60f, if (dark) 0.26f else 0.90f)
    val onTertiaryContainer = tone(55f, sat * 0.85f, if (dark) 0.92f else 0.12f)

    val surface = tone(0f, if (dark) sat * 0.12f else sat * 0.06f, if (dark) 0.07f else 0.985f)
    val onSurface = tone(0f, if (dark) sat * 0.15f else sat * 0.25f, if (dark) 0.90f else 0.11f)
    val surfaceVariant = tone(0f, if (dark) sat * 0.18f else sat * 0.12f, if (dark) 0.18f else 0.93f)
    val onSurfaceVariant = tone(0f, if (dark) sat * 0.25f else sat * 0.30f, if (dark) 0.72f else 0.35f)
    val outline = tone(0f, sat * 0.30f, if (dark) 0.52f else 0.56f)
    val outlineVariant = tone(0f, sat * 0.20f, if (dark) 0.30f else 0.82f)

    val surfaceContainerLowest = tone(0f, sat * 0.06f, if (dark) 0.04f else 1.00f)
    val surfaceContainerLow = tone(0f, sat * 0.08f, if (dark) 0.10f else 0.97f)
    val surfaceContainer = tone(0f, sat * 0.09f, if (dark) 0.13f else 0.95f)
    val surfaceContainerHigh = tone(0f, sat * 0.10f, if (dark) 0.16f else 0.93f)
    val surfaceContainerHighest = tone(0f, sat * 0.12f, if (dark) 0.20f else 0.91f)
    val surfaceDim = tone(0f, sat * 0.10f, if (dark) 0.06f else 0.87f)
    val surfaceBright = tone(0f, sat * 0.06f, if (dark) 0.24f else 0.985f)
    val inverseSurface = tone(0f, sat * 0.10f, if (dark) 0.90f else 0.20f)
    val inverseOnSurface = tone(0f, sat * 0.12f, if (dark) 0.20f else 0.95f)
    val inversePrimary = tone(0f, sat * 0.60f, if (dark) 0.42f else 0.78f)

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

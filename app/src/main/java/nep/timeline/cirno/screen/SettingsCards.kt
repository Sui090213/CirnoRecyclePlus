package nep.timeline.cirno.screen

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.ui.ThemeMode

/** 自定义主题色的预设色块 (ARGB). */
private val THEME_COLORS = listOf(
    0xFF4A90D9.toInt() to "雪蓝",
    0xFF35B5A9.toInt() to "冰青",
    0xFF7C5CD6.toInt() to "淡紫",
    0xFFD6679B.toInt() to "樱粉",
    0xFFE08A3C.toInt() to "暖橙",
    0xFF43A047.toInt() to "草绿",
)

/**
 * 外观设置: 主题模式 / 莫奈取色 / 自定义主题色.
 *
 * <p>所有修改立即生效并持久化到 SharedPreferences (见 [AppPrefs]), 不需要重启 App.
 */
@Composable
fun AppearanceSettingsCard() {
    var showCustomDialog by remember { mutableStateOf(false) }
    var hexInput by remember { mutableStateOf(seedToHex(AppPrefs.themeSeed)) }

    SettingsCard("外观 / 主题") {
        Text(text = "主题模式", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = AppPrefs.themeMode == mode,
                    onClick = { AppPrefs.updateThemeMode(mode) },
                    label = { Text(themeModeLabel(mode)) }
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "「跟随系统」会跟着手机的深色模式开关自动切换.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        SettingsSwitchRow(
            title = "莫奈取色 (Material You)",
            subtitle = "从系统壁纸取色, 需要 Android 12+; 打开后自定义主题色不生效",
            checked = AppPrefs.monetEnabled,
            onCheckedChange = { AppPrefs.updateMonetEnabled(it) }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        Text(text = "主题色", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(8.dp))
        if (AppPrefs.monetEnabled) {
            Text(
                text = "当前正在使用系统壁纸取色; 关闭「莫奈取色」后可以选下面的颜色.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                THEME_COLORS.forEach { (argb, name) ->
                    ColorDot(
                        argb = argb,
                        selected = AppPrefs.themeSeed == argb,
                        onClick = { AppPrefs.updateThemeSeed(argb) }
                    )
                }
                TextButton(onClick = {
                    hexInput = seedToHex(AppPrefs.themeSeed)
                    showCustomDialog = true
                }) {
                    Text("自定义")
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = "当前主题色 ${seedToHex(AppPrefs.themeSeed)} · 点色块立即生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCustomDialog) {
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("自定义主题色") },
            text = {
                Column {
                    Text(
                        text = "输入 #RRGGBB 格式的颜色值, 例如 #4A90D9 (雪蓝).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = {
                            hexInput = it
                            error = null
                        },
                        label = { Text("颜色值") },
                        singleLine = true,
                        isError = error != null
                    )
                    error?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    parseHexColor(hexInput)?.let { argb ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "预览 ${seedToHex(argb)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val argb = parseHexColor(hexInput)
                    if (argb == null) {
                        error = "格式不对, 示例: #4A90D9"
                    } else {
                        AppPrefs.updateThemeSeed(argb)
                        showCustomDialog = false
                    }
                }) {
                    Text("应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 通用设置: 更新检查 / 首页排序 / 进程列表显示范围.
 *
 * @param checkingUpdate 是否正在检查更新 (按钮防重复点击)
 * @param onCheckUpdate 点「立即检查」的回调
 */
@Composable
fun GeneralSettingsCard(
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
) {
    SettingsCard("通用设置") {
        SettingsSwitchRow(
            title = "自动检查更新",
            subtitle = "打开 App 时检查 GitHub 上的新版本 (12 小时内只查一次)",
            checked = AppPrefs.autoCheckUpdate,
            onCheckedChange = { AppPrefs.updateAutoCheckUpdate(it) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "检查更新", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "当前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                Text(if (checkingUpdate) "检查中…" else "立即检查")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

        SettingsSwitchRow(
            title = "后台应用优先排序",
            subtitle = "首页把正在后台运行的应用排到最前面, 一眼看到哪些应用还占着内存",
            checked = AppPrefs.sortBackgroundFirst,
            onCheckedChange = { AppPrefs.updateSortBackgroundFirst(it) }
        )
        SettingsSwitchRow(
            title = "进程列表显示未运行进程",
            subtitle = "应用面板里列出已声明但当前没启动的进程, 方便提前给子进程设规则",
            checked = AppPrefs.showInactiveProcesses,
            onCheckedChange = { AppPrefs.updateShowInactiveProcesses(it) }
        )
    }
}

@Composable
private fun ColorDot(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun seedToHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun parseHexColor(text: String): Int? {
    val value = text.trim()
    if (!value.startsWith("#"))
        return null
    return runCatching { AndroidColor.parseColor(value) }.getOrNull()
}

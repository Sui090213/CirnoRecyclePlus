package nep.timeline.cirno.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.client.UpdateChecker

/**
 * 「发现新版本」对话框.
 *
 * <p>首页启动自动检查 / 设置页手动检查共用这一份. 只做提示 + 跳浏览器下载,
 * 不在 App 内直接下载安装 (避免再引入 FileProvider / 安装权限的一堆兼容问题).
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "当前版本 v${BuildConfig.VERSION_NAME}，最新版本 ${info.version}。",
                    style = MaterialTheme.typography.bodyMedium
                )
                val changelog = info.changelog
                if (!changelog.isNullOrBlank()) {
                    Text(
                        text = changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUpdateDownload(context, info)
                onDismiss()
            }) {
                Text("去下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

/**
 * 打开更新下载页: 优先 Release 页面 (一定能打开, 里面能看到 APK / 模块 zip),
 * 其次 APK 直链, 最后模块 zip.
 *
 * @return 是否成功拉起浏览器
 */
fun openUpdateDownload(context: Context, info: UpdateChecker.UpdateInfo): Boolean {
    val candidates = listOfNotNull(info.releaseUrl, info.apkUrl, info.zipUrl)
    for (url in candidates) {
        if (url.isBlank())
            continue
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (t: Throwable) {
            // 换下一个链接.
        }
    }
    Toast.makeText(context, "没有可用的浏览器打开下载链接", Toast.LENGTH_SHORT).show()
    return false
}

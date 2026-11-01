package nep.timeline.cirno.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.client.ConfigBackup

/**
 * 配置导出 / 导入卡片.
 *
 * <p>导出包包含系统冻结 / 内存配置和界面偏好，但不包含背景图片文件本身；
 * 导入前会校验 SHA-256，并把当前配置备份到 `/data/misc/CirnoRecycle` 下的
 * `*.bak-<时间戳>` 文件.
 */
@Composable
fun ConfigBackupCard(
    onImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ConfigBackup.ParsedBundle?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null)
            return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigBackup.export(context, uri) }.getOrElse { Result.failure(it) }
            }
            busy = false
            result.onSuccess { summary ->
                isError = false
                message = "已导出 ${summary.globalBytes + summary.applicationBytes} 字节配置\n" +
                    "SHA-256: ${summary.sha256}"
                toast("配置已导出")
            }.onFailure {
                isError = true
                message = "导出失败: ${it.message}"
                toast(message!!)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null)
            return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigBackup.readAndVerify(context, uri) }
                    .getOrElse { Result.failure(it) }
            }
            busy = false
            result.onSuccess {
                pending = it
                isError = false
                message = null
            }.onFailure {
                isError = true
                message = "导入包校验失败: ${it.message}"
                toast(message!!)
            }
        }
    }

    fun applyImport(bundle: ConfigBackup.ParsedBundle) {
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigBackup.apply(bundle) }.getOrElse { Result.failure(it) }
            }
            busy = false
            result.onSuccess {
                isError = false
                message = "配置导入成功；系统配置已重新加载，界面偏好已应用。"
                toast("配置导入成功")
                onImported()
            }.onFailure {
                isError = true
                message = "导入失败: ${it.message}"
                toast(message!!)
            }
        }
    }

    SettingsCard("配置备份") {
        Text(
            text = "导出系统冻结 / 内存配置和界面偏好，或从之前导出的 JSON 恢复。" +
                "导出包带 SHA-256 校验；导入前会自动备份当前配置。" +
                "SHA-256 只用于校验文件完整性，不能代替来源信任。" +
                "背景图片文件不会包含在导出包里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { exportLauncher.launch("cirno-config-${System.currentTimeMillis()}.json") },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("导出配置")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("导入配置")
            }
        }
        if (busy) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("处理中…", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }

    pending?.let { bundle ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("导入这份配置？") },
            text = {
                Column {
                    Text("导出时间：${ConfigBackup.formatTimestamp(bundle.summary.exportedAt)}")
                    Text("导出时版本：${bundle.summary.appVersion.ifBlank { "未知" }}")
                    Text("系统配置大小：${bundle.summary.globalBytes + bundle.summary.applicationBytes} 字节")
                    Text("SHA-256：${bundle.summary.sha256.take(16)}…")
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "导入会覆盖当前冻结 / 内存配置和界面偏好。导入前会自动备份到配置目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toApply = bundle
                        pending = null
                        applyImport(toApply)
                    },
                    enabled = !busy
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }, enabled = !busy) { Text("取消") }
            }
        )
    }
}

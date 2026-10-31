package nep.timeline.cirno.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.R
import nep.timeline.cirno.runtime.RuntimeState

private const val REPO_URL = "https://github.com/Sui090213/CirnoRecyclePlus"
private const val RELEASE_URL = "$REPO_URL/releases"
private const val ISSUE_URL = "$REPO_URL/issues"
private const val UPSTREAM_URL = "https://github.com/Freezer-Team/Cirno"

/**
 * 关于页：版本 / 激活状态 / 项目链接 / 兼容性说明.
 *
 * <p>底栏的「关于」直接复用这里，不再单独开 Activity.
 */
@Composable
fun AboutScreen(
    runtime: RuntimeState?,
    rootOk: Boolean,
    configOk: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo_art),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "v" + BuildConfig.VERSION_NAME + " · by " + stringResource(R.string.app_author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(10.dp))
                val active = rootOk && configOk && runtime != null
                val dotColor = when {
                    active -> Color(0xFF4CAF50)
                    rootOk -> Color(0xFFFFA000)
                    else -> Color.Gray
                }
                val status = when {
                    active -> "已激活"
                    rootOk && configOk -> "模块未响应"
                    rootOk -> "等待模块加载"
                    else -> "未激活（需要 root）"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Android 应用冻结 + 内存回收模块",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "项目",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                AboutLink("GitHub 仓库", REPO_URL) { open(REPO_URL) }
                AboutLink("下载最新版本", RELEASE_URL) { open(RELEASE_URL) }
                AboutLink("问题反馈", ISSUE_URL) { open(ISSUE_URL) }
                AboutLink("上游 Cirno 项目", UPSTREAM_URL) { open(UPSTREAM_URL) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "兼容性说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "小米 / Redmi / POCO\n" +
                        "MIUI / HyperOS 自带的 millet（Greeze）冻结服务可能和本模块互相抢进程，" +
                        "表现为反复冻结 / 解冻和功耗升高。v1.3.1 起刷入时会自动检测并禁用，" +
                        "也可以在「设置 → 小米 millet 兼容」里一键禁用 / 恢复，恢复会按安装时备份还原。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Google 服务\n" +
                        "GMS / GSF / Play 商店默认受保护，避免切断 FCM 推送。不使用谷歌推送时，" +
                        "可在应用详情里打开「允许冻结 Google 服务」；如果同时加入了白名单，白名单优先。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "白名单\n" +
                        "白名单是绝对保护：整个应用（包括所有子进程）不会被自动或手动冻结，也不参与内存回收。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "开源与致谢",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "基于 Freezer-Team / Nep-Timeline 的 Cirno 二次修改，遵循 GPL-3.0。" +
                        "\n界面使用 Jetpack Compose + Material 3；毛玻璃使用 Haze；" +
                        "在线图库示例来自 Lorem Picsum。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "应用不上传任何使用数据；背景图片只保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "Cirno Recycle+ v" + BuildConfig.VERSION_NAME + " · " +
                stringResource(R.string.app_author) + " · GPL-3.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
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
        Text(
            text = "打开",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

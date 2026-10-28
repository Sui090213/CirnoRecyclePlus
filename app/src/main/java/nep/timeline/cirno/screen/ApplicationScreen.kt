package nep.timeline.cirno.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nep.timeline.cirno.ApplicationActivity
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.checkers.AppConfigs
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun ApplicationScreen(activity: ApplicationActivity) {
    val appName = activity.intent.getStringExtra("appName")!!
    val packageName = activity.intent.getStringExtra("packageName")!!
    var userId = activity.intent.getStringExtra("userId")
    if (userId == null)
        userId = "0"

    val hazeState = rememberHazeState()
    val hazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(MaterialTheme.colorScheme.background.copy(0.67f))
    )

    val isWhitelisted = remember { mutableStateOf(AppConfigs.isWhiteApp(packageName, userId.toInt())) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(Color.Transparent),
                modifier = Modifier
                    .hazeEffect(hazeState) {
                        style = hazeStyle
                    }
                    .fillMaxWidth(),
                title = {
                    Box(
                        Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = appName + (if (userId == "0") "" else "#$userId"),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier.hazeSource(
                state = hazeState
            ),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding())
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "白名单",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = isWhitelisted.value,
                                onCheckedChange = { checked ->
                                    if (GlobalVars.configCorrupted) {
                                        android.widget.Toast.makeText(
                                            activity,
                                            "配置文件损坏，写入已锁定；请先到内存设置页确认覆盖",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val key = "$packageName#$userId"
                                        val settings = GlobalVars.applicationSettings
                                            ?: nep.timeline.cirno.configs.settings.ApplicationSettings()
                                                .also { GlobalVars.applicationSettings = it }
                                        // JSON 里 "whiteApps": null 时兜底成可变集合, 避免 NPE.
                                        if (settings.whiteApps == null)
                                            settings.whiteApps = java.util.LinkedHashSet()
                                        if (checked)
                                            settings.whiteApps.add(key)
                                        else
                                            settings.whiteApps.remove(key)
                                        isWhitelisted.value = checked
                                        // 写配置文件要 root, 统一排进进程级单线程写队列,
                                        // 避免和内存设置页并发写同一对 JSON 互相覆盖.
                                        nep.timeline.cirno.configs.ConfigWriter.submit {
                                            val ok = runCatching {
                                                ConfigManager.manager.saveConfigSU()
                                            }.getOrDefault(false)
                                            if (!ok) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(
                                                        activity,
                                                        "白名单保存失败：请检查 root 权限或查看日志",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
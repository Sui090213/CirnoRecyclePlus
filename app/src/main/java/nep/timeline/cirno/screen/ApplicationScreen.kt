package nep.timeline.cirno.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nep.timeline.cirno.ApplicationActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationScreen(activity: ApplicationActivity) {
    // 入口 extras 由首页长按/点击时写入, 这里保持 !!, 和原来一致; userId 允许老入口不传或传坏.
    val appName = activity.intent.getStringExtra("appName")!!
    val packageName = activity.intent.getStringExtra("packageName")!!
    val userIdText = activity.intent.getStringExtra("userId") ?: "0"
    // 非法 userId 字符串兜底成 0, 避免整页因为一个坏 extra 崩掉.
    val userId = userIdText.toIntOrNull() ?: 0

    val hazeState = rememberHazeState()
    val hazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(MaterialTheme.colorScheme.background.copy(0.67f))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(Color.Transparent),
                modifier = Modifier
                    .hazeEffect(hazeState) {
                        style = hazeStyle
                    }
                    .fillMaxWidth(),
                navigationIcon = {
                    TextButton(onClick = { activity.finish() }) {
                        Text("返回")
                    }
                },
                title = {
                    Box(
                        Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = appName + (if (userId == 0) "" else "#$userId"),
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .hazeSource(
                    state = hazeState
                )
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // 整页复用首页长按弹出的同一份设置内容, 顶部留出状态栏 / 标题栏的高度.
            AppSettingsContent(
                appName = appName,
                packageName = packageName,
                userId = userId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

package nep.timeline.cirno.screen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.topjohnwu.superuser.Shell
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nep.timeline.cirno.ApplicationActivity
import nep.timeline.cirno.CommonConstants
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.MemoryActivity
import nep.timeline.cirno.R
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.checkers.AppConfigs
import nep.timeline.cirno.utils.PKGUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
fun MainScreen() {
    val handler = Handler(Looper.getMainLooper())

    val hazeState = rememberHazeState()
    val hazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(MaterialTheme.colorScheme.background.copy(0.67f))
    )

    val context = LocalContext.current

    fun getInstalledApps(context: Context): List<ApplicationInfo> {
        val packageManager = context.packageManager
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA).filter {
            !CommonConstants.isWhitelistApps(it.packageName) && !PKGUtils.isSystemApp(it)
        }
    }

    // 读取配置要走 root, 放在 IO 线程, 避免首次进入界面时卡住主线程.
    val readConfig = remember { mutableStateOf(false) }
    val apps = remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        readConfig.value = withContext(Dispatchers.IO) {
            runCatching { ConfigManager.manager.readConfigSU() }.getOrDefault(false)
        }
        // 应用列表和图标加载也放到 IO 线程, 避免主线程批量 loadIcon 造成首开卡顿.
        val loaded = withContext(Dispatchers.IO) { getInstalledApps(context) }
        handler.post {
            apps.value = loaded
        }
    }

    fun enterAppPage(appName: String, userId: String, packageName: String) {
        val intent = Intent()
        intent.setClass(context, ApplicationActivity::class.java)

        intent.putExtra("appName", appName)
        intent.putExtra("userId", userId)
        intent.putExtra("packageName", packageName)

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    @Composable
    fun AppItem(appInfo: ApplicationInfo, packageManager: PackageManager) {
        val appName = remember(appInfo) { appInfo.loadLabel(packageManager).toString() }
        val appIcon = remember(appInfo) {
            appInfo.loadIcon(packageManager).toBitmap().asImageBitmap()
        }
        val userId = PKGUtils.getUserId(appInfo.uid)
        val white = remember(appInfo) { AppConfigs.isWhiteApp(appInfo.packageName, userId) }

        Row(
            modifier = if (Shell.getShell().isRoot && readConfig.value)
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp).clickable(onClick = { enterAppPage(appName, userId.toString(), appInfo.packageName) })
            else
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
        ) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = (if (white) "(白名单) " else "") + appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = (if (white) Color(0xFF4CAF50) else Color.Unspecified)
                )
                Text(
                    text = (if (userId == 0) "" else "$userId#") + appInfo.packageName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Scaffold(
        topBar = {
            val navIconSize = 48.dp
            TopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(Color.Transparent),
                modifier = Modifier
                    .hazeEffect(hazeState) {
                        style = hazeStyle
                    }
                    .fillMaxWidth(),
                navigationIcon = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, MemoryActivity::class.java))
                    }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                title = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = navIconSize)
                            .padding(end = navIconSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 只有真的拿到 root 并成功读到配置才显示「已激活」;
                                // 没有 root / 配置读失败时显示灰色「未激活」, 别再误导用户.
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            color = if (readConfig.value) Color(0xFF4CAF50) else Color.Gray,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (readConfig.value) "已激活" else "未激活(需要 root)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "by " + stringResource(R.string.app_author) + " · v" + stringResource(R.string.app_version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Spacer(modifier = Modifier.width(navIconSize))
                }
            )
        },
    ) { padding ->
        if (apps.value.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
            return@Scaffold
        }

        val packageManager = context.packageManager
        Surface(
            modifier = Modifier.hazeSource(
                state = hazeState
            ),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val sortedApps = remember(apps.value) {
                    apps.value.sortedByDescending {
                        AppConfigs.isWhiteApp(it.packageName, PKGUtils.getUserId(it.uid))
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    //state = listState,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding())
                ) {
                    // 用 items() 而不是把所有应用塞进一个 item: LazyColumn 只组合可见项,
                    // 应用多时不会一次性加载全部图标.
                    items(
                        items = sortedApps,
                        key = { appInfo -> appInfo.packageName + "#" + PKGUtils.getUserId(appInfo.uid) }
                    ) { appInfo ->
                        AppItem(appInfo, packageManager)
                    }
                }
            }
        }
    }
}
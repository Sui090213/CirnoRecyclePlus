package nep.timeline.cirno.screen

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.RuntimeClient
import nep.timeline.cirno.client.UpdateChecker
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.checkers.AppConfigs
import nep.timeline.cirno.runtime.RuntimeState
import nep.timeline.cirno.utils.PKGUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalEncodingApi::class)
@Composable
fun MainScreen() {
    val handler = Handler(Looper.getMainLooper())

    val hazeState = rememberHazeState()
    val hazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(MaterialTheme.colorScheme.background.copy(0.67f))
    )

    val context = LocalContext.current
    val packageManager = context.packageManager

    fun getInstalledApps(context: Context): List<ApplicationInfo> {
        val packageManager = context.packageManager
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA).filter {
            // Google 全家桶默认受保护, 但仍要显示在列表里: 用户可能不使用 FCM,
            // 需要在应用详情里单独打开「允许冻结 Google 服务」.
            CommonConstants.isGoogleFamily(it.packageName) ||
                (!CommonConstants.isWhitelistApps(it.packageName) && !PKGUtils.isSystemApp(it))
        }
    }

    // 读取配置要走 root, 放在 IO 线程, 避免首次进入界面时卡住主线程.
    // rootOk: 有没有 root 权限; configOk: 配置文件是否已经生成并成功读取.
    val rootOk = remember { mutableStateOf(false) }
    val configOk = remember { mutableStateOf(false) }
    val apps = remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }

    // 应用名缓存: 搜索时要按名字过滤, 不能在每次输入时对全部应用调 loadLabel (主线程会卡).
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 首页搜索: 右上角搜索按钮展开输入框, 支持应用名 / 包名.
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // 启动自动检查更新: 发现新版本时弹提示.
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    // 运行时冻结状态: 状态文件只在模块存活时才有, 读不到就保持 null (界面按「未知」显示, 不能当成「未运行」).
    var runtime by remember { mutableStateOf<RuntimeState?>(null) }

    // 长按选中的应用, 非 null 时弹出底部设置面板.
    var sheetApp by remember { mutableStateOf<ApplicationInfo?>(null) }

    // 底部面板直接展开到最大高度, 避免折到一半时开关列表被截断.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        val hasRoot = withContext(Dispatchers.IO) {
            runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        }
        rootOk.value = hasRoot
        if (hasRoot) {
            configOk.value = withContext(Dispatchers.IO) {
                runCatching {
                    // 和配置写队列拿同一把锁, 避免读到还没落盘的旧配置.
                    synchronized(nep.timeline.cirno.configs.ConfigWriter.LOCK) {
                        ConfigManager.manager.readConfigSU()
                    }
                }.getOrDefault(false)
            }
        }
        // 应用列表和图标加载也放到 IO 线程, 避免主线程批量 loadIcon 造成首开卡顿.
        val loaded = withContext(Dispatchers.IO) {
            val list = getInstalledApps(context)
            val pm = context.packageManager
            val labels = list.associate { info ->
                info.packageName to runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName)
            }
            list to labels
        }
        handler.post {
            apps.value = loaded.first
            appLabels = loaded.second
        }
    }

    // 启动时自动检查更新: 默认 12 小时内只查一次, 失败也记一次, 避免每次打开都走一次网络.
    LaunchedEffect(Unit) {
        if (!AppPrefs.autoCheckUpdate)
            return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - AppPrefs.lastUpdateCheckAt() < 12L * 60L * 60L * 1000L)
            return@LaunchedEffect

        val result = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.check() }.getOrNull()
        }
        AppPrefs.markUpdateChecked()
        (result as? UpdateChecker.Result.Available)?.let { updateInfo = it.info }
    }

    // 轮询运行时状态: 每 2 秒读一次状态文件. 读文件要走 root (SuFile), 必须放 IO 线程.
    // 只在界面 RESUMED 且没有弹出底部面板时轮询:
    // - 退到后台 / 锁屏时停掉, 避免持续 root shell 唤醒耗电;
    // - 面板打开时它自己有一份轮询, 这里不重复读.
    LaunchedEffect(rootOk.value, sheetApp) {
        if (!rootOk.value || sheetApp != null)
            return@LaunchedEffect
        val lifecycleOwner = context as? LifecycleOwner ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runtime = withContext(Dispatchers.IO) {
                    runCatching { RuntimeClient.loadState() }.getOrNull()
                }
                delay(2000)
            }
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
        // 白名单状态不缓存: 长按面板里改完白名单后, 首页列表在 2 秒内轮询触发重组时能立刻刷新.
        val white = AppConfigs.isWhiteApp(appInfo.packageName, userId)
        // Google 服务默认受保护; 用户单独放行后就不显示这个标记.
        val googleProtected = CommonConstants.isGoogleFamily(appInfo.packageName) &&
            !AppConfigs.getOptions(appInfo.packageName, userId).allowFreezeGoogle

        // 配置损坏时禁止进应用页 / 长按: 否则白名单开关会用默认值整份覆盖配置, 绕过设置页的写锁.
        val canOpen = rootOk.value && (configOk.value || !GlobalVars.configCorrupted)

        Row(
            modifier = if (canOpen)
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .combinedClickable(
                        onClick = { enterAppPage(appName, userId.toString(), appInfo.packageName) },
                        onLongClick = { sheetApp = appInfo }
                    )
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
                    text = (if (white) "(白名单) " else "") +
                        (if (googleProtected) "(Google 保护) " else "") + appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        white -> Color(0xFF4CAF50)
                        googleProtected -> Color(0xFFFFA000)
                        else -> Color.Unspecified
                    }
                )
                Text(
                    text = (if (userId == 0) "" else "$userId#") + appInfo.packageName,
                    style = MaterialTheme.typography.bodySmall
                )
                AppStatusBadge(
                    runtime = runtime,
                    packageName = appInfo.packageName,
                    userId = userId
                )
            }
        }
    }

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
                        Modifier.fillMaxWidth(),
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
                                // 四态: 已激活 / 有 root 但配置还没生成 / 配置有了但模块没在跑 / 没有 root.
                                val active = rootOk.value && configOk.value && runtime != null
                                val dotColor = when {
                                    active -> Color(0xFF4CAF50)
                                    rootOk.value -> Color(0xFFFFA000)
                                    else -> Color.Gray
                                }
                                val statusText = when {
                                    active -> "已激活"
                                    rootOk.value && configOk.value -> "模块未响应"
                                    rootOk.value -> "等待模块加载"
                                    else -> "未激活(需要 root)"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(color = dotColor, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = statusText,
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
                    IconButton(onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible)
                            searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchVisible) "关闭搜索" else "搜索应用",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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

        Surface(
            modifier = Modifier.hazeSource(
                state = hazeState
            ),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 搜索过滤: 名字和包名都匹配, 不区分大小写.
                val filteredApps = remember(apps.value, appLabels, searchQuery) {
                    val query = searchQuery.trim()
                    if (query.isEmpty()) {
                        apps.value
                    } else {
                        apps.value.filter { info ->
                            val label = appLabels[info.packageName] ?: info.packageName
                            label.contains(query, ignoreCase = true) ||
                                info.packageName.contains(query, ignoreCase = true)
                        }
                    }
                }

                // 排序:
                // 1. 可在设置里选择「后台运行的应用优先」: 把当前有存活进程但不在前台的应用排最前面;
                // 2. 数量多的优先 (后台进程越多越值得注意);
                // 3. 白名单应用次优先 (和旧版行为一致);
                // 4. 最后按应用名排序, 方便查找.
                val sortedApps = remember(filteredApps, runtime, AppPrefs.sortBackgroundFirst) {
                    val backgroundFirst = AppPrefs.sortBackgroundFirst
                    filteredApps.sortedWith(
                        compareBy<ApplicationInfo> { info ->
                            if (!backgroundFirst) {
                                0
                            } else {
                                val state = RuntimeClient.findApp(runtime, info.packageName, PKGUtils.getUserId(info.uid))
                                if (state != null && state.liveProcesses > 0 && !state.visible) 0 else 1
                            }
                        }.thenByDescending { info ->
                            if (!backgroundFirst) {
                                0
                            } else {
                                RuntimeClient.findApp(runtime, info.packageName, PKGUtils.getUserId(info.uid))?.liveProcesses ?: 0
                            }
                        }.thenByDescending { info ->
                            AppConfigs.isWhiteApp(info.packageName, PKGUtils.getUserId(info.uid))
                        }.thenBy { info ->
                            (appLabels[info.packageName] ?: info.packageName).lowercase()
                        }
                    )
                }

                if (searchVisible) {
                    // 展开搜索时自动聚焦并弹出输入法; 输入框本身在顶栏下方, 不会被键盘挡住.
                    LaunchedEffect(searchVisible) {
                        runCatching { searchFocusRequester.requestFocus() }
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = padding.calculateTopPadding() + 4.dp,
                                bottom = 4.dp
                            )
                            .focusRequester(searchFocusRequester),
                        placeholder = { Text("搜索应用名或包名") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清空搜索"
                                    )
                                }
                            }
                        },
                        singleLine = true
                    )
                }

                if (sortedApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有匹配的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        //state = listState,
                        contentPadding = PaddingValues(
                            top = if (searchVisible) 0.dp else padding.calculateTopPadding(),
                            bottom = 16.dp
                        )
                    ) {
                        // 用 items() 而不是把所有应用塞进一个 item: LazyColumn 只组合可见项,
                        // 应用多时不会一次性加载全部图标.
                        items(
                            items = sortedApps,
                            key = { appInfo -> appInfo.uid.toString() + "#" + appInfo.packageName }
                        ) { appInfo ->
                            AppItem(appInfo, packageManager)
                        }
                    }
                }
            }
        }
    }

    // 长按应用弹出的底部设置面板: 内容和详情页完全一致, 直接复用 AppSettingsContent.
    // 面板关闭后不需要手动刷新, 首页的轮询会在 2 秒内更新状态徽章.
    sheetApp?.let { app ->
        val appLabel = remember(app) { app.loadLabel(packageManager).toString() }
        ModalBottomSheet(
            onDismissRequest = { sheetApp = null },
            sheetState = sheetState,
        ) {
            AppSettingsContent(
                appName = appLabel,
                packageName = app.packageName,
                userId = PKGUtils.getUserId(app.uid),
            )
        }
    }

    // 启动时自动检查更新, 有新版就弹提示 (和设置页「检查更新」共用同一个对话框).
    updateInfo?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onDismiss = { updateInfo = null }
        )
    }
}

/** 应用列表里的冻结状态徽章: 一眼看出每个应用当前是冻结 / 前台 / 后台. */
@Composable
private fun AppStatusBadge(runtime: RuntimeState?, packageName: String, userId: Int) {
    // 读不到状态文件 = 模块没在运行 / 状态未知. 此时不显示徽章, 顶部状态栏已经有提示,
    // 不能把「未知」显示成「未运行」.
    if (runtime == null)
        return

    val appState = RuntimeClient.findApp(runtime, packageName, userId)
    val (text, color) = when {
        appState == null -> "未运行" to Color.Gray
        appState.frozenProcesses > 0 && appState.frozenProcesses >= appState.liveProcesses ->
            "❄ 已冻结" to Color(0xFF4CAF50)
        appState.frozenProcesses > 0 -> "部分冻结" to Color(0xFFFFA000)
        appState.visible -> "前台运行" to Color(0xFF2196F3)
        appState.audio -> "后台播放" to Color(0xFF9C27B0)
        else -> "后台运行 · ${appState.liveProcesses} 进程" to Color(0xFF757575)
    }

    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

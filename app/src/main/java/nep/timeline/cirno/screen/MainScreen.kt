package nep.timeline.cirno.screen

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.topjohnwu.superuser.Shell
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.CommonConstants
import nep.timeline.cirno.R
import nep.timeline.cirno.client.AppIconCache
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.RuntimeClient
import nep.timeline.cirno.client.UpdateChecker
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.checkers.AppConfigs
import nep.timeline.cirno.runtime.AppRuntimeState
import nep.timeline.cirno.runtime.RuntimeState
import nep.timeline.cirno.utils.PKGUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 底栏三个页面. */
private enum class MainTab(val label: String, val icon: ImageVector) {
    APPS("软件列表", Icons.AutoMirrored.Filled.List),
    SETTINGS("设置", Icons.Default.Settings),
    ABOUT("关于", Icons.Default.Info),
}

/** 应用详情页的入参；详情页不再是独立 Activity，返回动画由 AnimatedContent 统一处理. */
private data class AppDetailTarget(
    val appName: String,
    val packageName: String,
    val userId: Int,
)

private fun runtimeKey(packageName: String, userId: Int): String = packageName + "#" + userId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val handler = remember { Handler(Looper.getMainLooper()) }
    val context = LocalContext.current
    val packageManager = context.packageManager
    val tabsStateHolder = rememberSaveableStateHolder()

    fun getInstalledApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        // 列表不需要每个应用的 meta-data；传 0 可以少解析一遍 manifest，启动更快.
        return pm.getInstalledApplications(0).filter {
            // Google 全家桶默认受保护, 但仍要显示在列表里: 用户可能不使用 FCM,
            // 需要在应用详情里单独打开「允许冻结 Google 服务」.
            CommonConstants.isGoogleFamily(it.packageName) ||
                (!CommonConstants.isWhitelistApps(it.packageName) && !PKGUtils.isSystemApp(it))
        }
    }

    val rootOk = remember { mutableStateOf(false) }
    val configOk = remember { mutableStateOf(false) }
    val apps = remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var searchQuery by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(MainTab.APPS) }
    var detail by remember { mutableStateOf<AppDetailTarget?>(null) }
    var sheetApp by remember { mutableStateOf<ApplicationInfo?>(null) }

    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var runtime by remember { mutableStateOf<RuntimeState?>(null) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        val hasRoot = withContext(Dispatchers.IO) {
            runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        }
        rootOk.value = hasRoot
        if (hasRoot) {
            configOk.value = withContext(Dispatchers.IO) {
                runCatching {
                    synchronized(nep.timeline.cirno.configs.ConfigWriter.LOCK) {
                        ConfigManager.manager.readConfigSU()
                    }
                }.getOrDefault(false)
            }
        }
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

    // 首页 / 底栏共用的运行时状态轮询. 打开详情页或长按面板时停掉，
    // 详情页自己有轮询，避免同时开多个 root shell.
    LaunchedEffect(rootOk.value, sheetApp, detail) {
        if (!rootOk.value || sheetApp != null || detail != null)
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

    // 返回手势: 详情页会跟着手指进度往右移动 (Android 13+ 的预测性返回)，
    // 松手取消就弹回，完成才真正切回上一页；设置 / 关于页返回软件列表.
    PredictiveBackHandler(enabled = detail != null || tab != MainTab.APPS) { progress ->
        try {
            progress.collect { backProgress = it.progress.coerceIn(0f, 1f) }
            when {
                detail != null -> detail = null
                tab != MainTab.APPS -> tab = MainTab.APPS
            }
        } finally {
            backProgress = 0f
        }
    }

    AnimatedContent(
        targetState = detail,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "detail"
    ) { target ->
        if (target == null) {
            // 用 SaveableStateHolder 保留三个 Tab 和详情的滚动位置:
            // AnimatedContent 会把离屏页面从 composition 移除, 不包一层的话滚回顶部.
            tabsStateHolder.SaveableStateProvider("main-tabs") {
                MainTabs(
                    context = context,
                    packageManager = packageManager,
                    apps = apps.value,
                    appLabels = appLabels,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    tab = tab,
                    onTabChange = { tab = it },
                    runtime = runtime,
                    rootOk = rootOk.value,
                    configOk = configOk.value,
                    stateHolder = tabsStateHolder,
                    onOpenApp = { info ->
                        detail = AppDetailTarget(
                            appName = appLabels[info.packageName] ?: info.packageName,
                            packageName = info.packageName,
                            userId = PKGUtils.getUserId(info.uid),
                        )
                        tab = MainTab.APPS
                    },
                    onLongPressApp = { sheetApp = it },
                )
            }
        } else {
            tabsStateHolder.SaveableStateProvider("detail-${target.packageName}#${target.userId}") {
                AppDetailPage(
                    target = target,
                    onBack = { detail = null },
                    backProgress = backProgress,
                )
            }
        }
    }

    sheetApp?.let { app ->
        val appLabel = appLabels[app.packageName] ?: app.packageName
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

    updateInfo?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onDismiss = { updateInfo = null }
        )
    }
}

/** 三页主界面：顶部搜索 / 标题，底部模块状态 + 三个 Tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(
    context: Context,
    packageManager: PackageManager,
    apps: List<ApplicationInfo>,
    appLabels: Map<String, String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    runtime: RuntimeState?,
    rootOk: Boolean,
    configOk: Boolean,
    stateHolder: SaveableStateHolder,
    onOpenApp: (ApplicationInfo) -> Unit,
    onLongPressApp: (ApplicationInfo) -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = Color.Transparent,
        topBar = {
            MainHeader(
                tab = tab,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
            )
        },
        bottomBar = {
            BottomDock(
                tab = tab,
                onTabChange = onTabChange,
                rootOk = rootOk,
                configOk = configOk,
                runtime = runtime,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "tab"
        ) { target ->
            stateHolder.SaveableStateProvider("tab-${target.name}") {
                when (target) {
                    MainTab.APPS -> AppsTab(
                        context = context,
                        packageManager = packageManager,
                        apps = apps,
                        appLabels = appLabels,
                        searchQuery = searchQuery,
                        runtime = runtime,
                        onOpenApp = onOpenApp,
                        onLongPressApp = onLongPressApp,
                    )
                    MainTab.SETTINGS -> SettingsScreen()
                    MainTab.ABOUT -> AboutScreen(
                        runtime = runtime,
                        rootOk = rootOk,
                        configOk = configOk,
                    )
                }
            }
        }
    }
}

/** 顶部：软件列表页是搜索框，设置 / 关于页是标题；切换时做淡入淡出. */
@Composable
private fun MainHeader(
    tab: MainTab,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "header"
        ) { target ->
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                when (target) {
                    MainTab.APPS -> {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            placeholder = { Text("搜索应用名 / 包名") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "清空搜索")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
                            )
                        )
                    }
                    MainTab.SETTINGS -> {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(
                                text = "设置",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "外观 / 背景 / 通用 / 内存回收",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MainTab.ABOUT -> {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(
                                text = "关于",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "版本 / 激活状态 / 项目链接 / 兼容性",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 底栏：模块名称 + 激活状态 + 三个页面入口. */
@Composable
private fun BottomDock(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    rootOk: Boolean,
    configOk: Boolean,
    runtime: RuntimeState?,
) {
    val active = rootOk && configOk && runtime != null
    val dotColor = when {
        active -> Color(0xFF4CAF50)
        rootOk -> Color(0xFFFFA000)
        else -> Color.Gray
    }
    val statusText = when {
        active -> "已激活"
        rootOk && configOk -> "模块未响应"
        rootOk -> "等待模块加载"
        else -> "未激活（需要 root）"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    text = "v" + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { onTabChange(item) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        )
                    )
                }
            }
        }
    }
}

/** 软件列表页. */
@Composable
private fun AppsTab(
    context: Context,
    packageManager: PackageManager,
    apps: List<ApplicationInfo>,
    appLabels: Map<String, String>,
    searchQuery: String,
    runtime: RuntimeState?,
    onOpenApp: (ApplicationInfo) -> Unit,
    onLongPressApp: (ApplicationInfo) -> Unit,
) {
    if (apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val filteredApps = remember(apps, appLabels, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { info ->
                val label = appLabels[info.packageName] ?: info.packageName
                label.contains(query, ignoreCase = true) ||
                    info.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    // 每次 runtime 更新只建一次索引; 排序 / 列表项查状态都是 O(1), 避免在比较器里
    // 反复 RuntimeClient.findApp (原来 500 个应用时会做几十万次线性查找).
    val runtimeIndex = remember(runtime) {
        runtime?.apps?.associateBy { runtimeKey(it.packageName, it.userId) } ?: emptyMap()
    }

    val sortedApps = remember(filteredApps, runtimeIndex, AppPrefs.sortBackgroundFirst) {
        val backgroundFirst = AppPrefs.sortBackgroundFirst
        filteredApps.sortedWith(
            compareBy<ApplicationInfo> { info ->
                if (!backgroundFirst) {
                    0
                } else {
                    val state = runtimeIndex[runtimeKey(info.packageName, PKGUtils.getUserId(info.uid))]
                    if (state != null && state.liveProcesses > 0 && !state.visible) 0 else 1
                }
            }.thenByDescending { info ->
                if (!backgroundFirst) {
                    0
                } else {
                    runtimeIndex[runtimeKey(info.packageName, PKGUtils.getUserId(info.uid))]?.liveProcesses ?: 0
                }
            }.thenByDescending { info ->
                AppConfigs.isWhiteApp(info.packageName, PKGUtils.getUserId(info.uid))
            }.thenBy { info ->
                (appLabels[info.packageName] ?: info.packageName).lowercase()
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (sortedApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                items(
                    items = sortedApps,
                    key = { appInfo -> appInfo.uid.toString() + "#" + appInfo.packageName }
                ) { appInfo ->
                    val userId = PKGUtils.getUserId(appInfo.uid)
                    AppItem(
                        appInfo = appInfo,
                        appName = appLabels[appInfo.packageName] ?: appInfo.packageName,
                        packageManager = packageManager,
                        runtimeAvailable = runtime != null,
                        appState = runtimeIndex[runtimeKey(appInfo.packageName, userId)],
                        onOpen = { onOpenApp(appInfo) },
                        onLongPress = { onLongPressApp(appInfo) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppItem(
    appInfo: ApplicationInfo,
    appName: String,
    packageManager: PackageManager,
    runtimeAvailable: Boolean,
    appState: AppRuntimeState?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    var appIcon by remember(appInfo.packageName) {
        mutableStateOf(AppIconCache.get(appInfo.packageName))
    }
    LaunchedEffect(appInfo.packageName) {
        if (appIcon == null) {
            appIcon = withContext(Dispatchers.IO) {
                AppIconCache.load(packageManager, appInfo)
            }
        }
    }

    val userId = PKGUtils.getUserId(appInfo.uid)
    val white = AppConfigs.isWhiteApp(appInfo.packageName, userId)
    val googleProtected = CommonConstants.isGoogleFamily(appInfo.packageName) &&
        !AppConfigs.getOptions(appInfo.packageName, userId).allowFreezeGoogle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongPress,
            )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val icon = appIcon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
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
                appState = appState,
                runtimeAvailable = runtimeAvailable,
            )
        }
    }
}

/** 应用列表里的冻结状态徽章. */
@Composable
private fun AppStatusBadge(appState: AppRuntimeState?, runtimeAvailable: Boolean) {
    if (!runtimeAvailable)
        return

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
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 应用详情页（原独立 Activity 改为内部页面，返回键和手势都能触发动画）. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailPage(
    target: AppDetailTarget,
    onBack: () -> Unit,
    backProgress: Float,
) {
    Scaffold(
        modifier = Modifier.graphicsLayer {
            translationX = backProgress * size.width * 0.32f
            alpha = 1f - backProgress * 0.12f
        },
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = target.appName + (if (target.userId == 0) "" else "#${target.userId}"),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = target.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { padding ->
        AppSettingsContent(
            appName = target.appName,
            packageName = target.packageName,
            userId = target.userId,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

package nep.timeline.cirno.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.client.AppOptionsStore
import nep.timeline.cirno.client.AppOptionsValue
import nep.timeline.cirno.client.AppPrefs
import nep.timeline.cirno.client.ProcessCatalog
import nep.timeline.cirno.client.RuntimeClient
import nep.timeline.cirno.configs.settings.AppOptions
import nep.timeline.cirno.runtime.AppRuntimeState
import nep.timeline.cirno.runtime.RuntimeAction
import nep.timeline.cirno.runtime.RuntimeState
import java.util.LinkedHashSet

/**
 * 单应用的冻结设置面板.
 *
 * <p>两个入口共用同一份内容:
 * - 首页长按应用 -> 底部弹出面板 ([nep.timeline.cirno.screen.MainScreen]);
 * - 首页点击应用 -> 应用详情页 ([nep.timeline.cirno.screen.ApplicationScreen]).
 *
 * <p>包含:
 * - 实时冻结状态 (已冻结 / 部分冻结 / 运行中 / 未运行);
 * - 冻结选项: 参与自动冻结、播放音频时是否冻结、保留网络、保留闹钟、允许内存回收;
 * - 进程级规则: 全部冻结 / 只冻结选中进程 / 排除选中进程;
 * - 进程列表: 每个进程的 pid、冻结状态, 并可以手动冻结 / 解冻单个进程.
 */

/** 把界面快照写回配置对象 (由 AppOptionsStore 在写队列线程里调用, 不在主线程改共享集合). */
private fun AppOptionsValue.applyTo(options: AppOptions) {
    options.enabled = enabled
    options.keepAudio = keepAudio
    options.keepNetwork = keepNetwork
    options.keepAlarms = keepAlarms
    options.allowReclaim = allowReclaim
    options.wakeOnPush = wakeOnPush
    options.processMode = processMode
    options.selectedProcesses = LinkedHashSet(selected)
}

/** 读取结果: 配置文件是否已经生成 + 当前选项 + 是否白名单. */
private data class AppSettingsLoad(
    val configReady: Boolean,
    val options: AppOptionsValue,
    val white: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsContent(
    appName: String,
    packageName: String,
    userId: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var optionsUi by remember { mutableStateOf<AppOptionsValue?>(null) }
    var white by remember { mutableStateOf(false) }
    var runtime by remember { mutableStateOf<RuntimeState?>(null) }
    // 配置文件还没生成时仍然允许修改 (保存会创建一份新配置), 只给个提示.
    var configReady by remember { mutableStateOf(true) }
    // 配置损坏时锁住写入: 和内存设置页保持一致, 避免把还能手工修复的配置覆盖成默认值.
    var writesLocked by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 读配置 (要走 root, 放 IO 线程).
    LaunchedEffect(packageName, userId) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (!com.topjohnwu.superuser.Shell.getShell().isRoot) {
                    null
                } else {
                    val configReady = AppOptionsStore.load()
                    AppSettingsLoad(
                        configReady = configReady,
                        options = AppOptionsStore.read(packageName, userId),
                        white = AppOptionsStore.isWhite(packageName, userId),
                    )
                }
            }.getOrNull()
        }
        if (result == null) {
            loadError = "读取配置失败: 需要 root 权限 (请在 Magisk / KernelSU / APatch 里授权)"
            loaded = false
        } else {
            optionsUi = result.options
            white = result.white
            configReady = result.configReady
            writesLocked = nep.timeline.cirno.GlobalVars.configCorrupted
            loaded = true
            loadError = null
        }
    }

    // 冻结状态每 2 秒刷新一次: 手动冻结 / 自动冻结后界面都能马上看到变化.
    // 只在界面前台 (RESUMED) 时轮询: 退到后台就停, 避免持续走 root shell 耗电.
    LaunchedEffect(packageName, userId) {
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runtime = withContext(Dispatchers.IO) {
                    runCatching { RuntimeClient.loadState() }.getOrNull()
                }
                delay(2000)
            }
        }
    }

    val uiRunning = runtime != null

    /** 保存修改: 先更新界面快照, 修改动作交给写队列线程执行 (避免和序列化并发). */
    fun update(block: (AppOptionsValue) -> AppOptionsValue) {
        if (writesLocked) {
            toast("配置文件损坏，写入已锁定；请先到内存设置页确认覆盖")
            return
        }
        val old = optionsUi ?: return
        val new = block(old)
        if (new == old)
            return
        optionsUi = new
        AppOptionsStore.update(
            packageName = packageName,
            userId = userId,
            block = { options -> new.applyTo(options) },
            onResult = { ok ->
                if (!ok)
                    toast("保存失败: 请检查 root 权限或查看日志")
            }
        )
    }

    /**
     * 下发一次手动操作 (冻结 / 解冻). 结果由 system_server 写回状态文件, 这里延迟刷新.
     *
     * @param startTime 进程启动时间, 用于校验 pid 没有被复用; 应用级操作传 -1
     */
    fun sendAction(type: String, pid: Int = 0, startTime: Long = -1L) {
        if (!loaded) {
            toast("配置还没有读取完成")
            return
        }
        scope.launch {
            val seq = withContext(Dispatchers.IO) {
                val action = RuntimeAction().apply {
                    this.type = type
                    this.packageName = packageName
                    this.userId = userId
                    this.pid = pid
                    this.startTime = startTime
                }
                RuntimeClient.sendActions(listOf(action), runtime?.lastActionSeq ?: 0L)
            }
            if (seq == null) {
                toast("操作发送失败: 需要 root 权限, 或模块没有在运行")
                return@launch
            }
            toast("操作已发送, 正在执行…")
            delay(2600)
            runtime = withContext(Dispatchers.IO) {
                runCatching { RuntimeClient.loadState() }.getOrNull()
            }
            val state = runtime
            val message = state?.lastActionMessage
            // 只显示「自己这一批」的执行结果, 避免别的面板的操作结果被张冠李戴.
            if (state != null && !message.isNullOrBlank() && state.lastActionAtMs > 0L &&
                state.lastActionSeq >= seq &&
                System.currentTimeMillis() - state.lastActionAtMs < 20_000L
            ) {
                toast(message)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------------------------
        // 标题 / 状态
        // ------------------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = (if (userId == 0) "" else "$userId#") + packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                AppStatusLine(appState = RuntimeClient.findApp(runtime, packageName, userId), moduleAlive = uiRunning)
                if (!uiRunning) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = if (runtime == null) {
                            "读取不到运行状态: 确认 LSPosed 里已启用本模块并重启过; 状态文件: /data/misc/CirnoRecycle/RuntimeState.json"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val state = runtime
                val message = state?.lastActionMessage
                if (state != null && !message.isNullOrBlank() && state.lastActionAtMs > 0L &&
                    System.currentTimeMillis() - state.lastActionAtMs < 20_000L
                ) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "最近操作: $message",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        loadError?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (writesLocked) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "配置文件解析失败，写入已锁定",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "为避免把可能还能手工修复的配置覆盖掉，这里暂时不能修改。确认要覆盖时，请先到「内存回收设置」页点「仍要写入（覆盖损坏的配置）」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (loaded && !configReady && !writesLocked) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "配置文件还没有生成（模块可能还没在 system_server 里运行）。现在修改会创建一份新配置。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!loaded) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("正在读取配置…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Column
        }

        val current = optionsUi ?: return@Column

        // ------------------------------------------------------------------
        // 冻结开关
        // ------------------------------------------------------------------
        AppSectionCard("冻结选项") {
            AppSwitchRow(
                title = "参与自动冻结",
                subtitle = "关闭后这个应用永远不会被冻结 (仍然可以被内存回收)",
                checked = current.enabled,
                onCheckedChange = { value ->
                    val wasEnabled = current.enabled
                    update { it.copy(enabled = value) }
                    // 关掉自动冻结后, 如果应用当前正被冻着, 要主动解冻一次:
                    // 否则它会一直冻着, 而 Binder / 广播唤醒路径都要等配置生效才能解冻.
                    if (!value && wasEnabled)
                        sendAction(RuntimeAction.TYPE_THAW_APP)
                }
            )
            AppSwitchRow(
                title = "后台播放音频时不冻结",
                subtitle = "默认开启. 关闭后即使正在放歌 / 放视频也会被冻结 (可能卡声音)",
                checked = current.keepAudio,
                onCheckedChange = { value -> update { it.copy(keepAudio = value) } }
            )
            AppSwitchRow(
                title = "冻结时保留网络连接",
                subtitle = "Android 15 及以下冻结时会断掉该应用的网络, 打开后跳过",
                checked = current.keepNetwork,
                onCheckedChange = { value -> update { it.copy(keepNetwork = value) } }
            )
            AppSwitchRow(
                title = "冻结时保留闹钟",
                subtitle = "默认会移除冻结应用的闹钟; 推送类应用可以打开保留",
                checked = current.keepAlarms,
                onCheckedChange = { value -> update { it.copy(keepAlarms = value) } }
            )
            AppSwitchRow(
                title = "允许内存回收",
                subtitle = "关闭后内存回收模块不会回收这个应用的内存",
                checked = current.allowReclaim,
                onCheckedChange = { value -> update { it.copy(allowReclaim = value) } }
            )
            AppSwitchRow(
                title = "收到推送时解冻",
                subtitle = "收到 FCM / 厂商推送广播时先解冻这个应用 15 秒处理消息，之后再冻结",
                checked = current.wakeOnPush,
                onCheckedChange = { value -> update { it.copy(wakeOnPush = value) } }
            )
            AppSwitchRow(
                title = "白名单",
                subtitle = "整应用不冻结、不参与内存回收; 仍然可以冻结它的子进程 (主进程不会被冻)",
                checked = white,
                onCheckedChange = { value ->
                    if (writesLocked) {
                        toast("配置文件损坏，写入已锁定；请先到内存设置页确认覆盖")
                    } else {
                        white = value
                        AppOptionsStore.setWhite(packageName, userId, value) { changed, saved ->
                            if (!changed && white == value)
                                white = !value
                            if (!saved)
                                toast("保存失败: 请检查 root 权限")
                            else if (!changed)
                                toast("白名单没有变化")
                        }
                    }
                }
            )
        }

        // ------------------------------------------------------------------
        // 进程级规则 + 进程列表
        // ------------------------------------------------------------------
        val appState = RuntimeClient.findApp(runtime, packageName, userId)

        // 进程列表 = 「PackageManager 里声明的进程」∪「当前活着的进程」:
        // 用户经常需要给一个还没启动的子进程 (例如微信 :push) 预先设规则, 只列运行中的进程不够用.
        val packageManager = context.packageManager
        val declaredProcesses = remember(packageName) {
            runCatching { ProcessCatalog.declaredProcesses(packageManager, packageName) }
                .getOrDefault(listOf(packageName))
        }
        val showInactive = AppPrefs.showInactiveProcesses
        val processRows = remember(appState, declaredProcesses, showInactive) {
            buildProcessRows(appState, declaredProcesses, showInactive, packageName)
        }

        AppSectionCard("自定义进程冻结") {
            Text(
                text = "自动冻结这个应用时冻结哪些进程. 手动冻结 / 解冻在下面的进程列表里点.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (white) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "白名单应用: 整应用不参与自动冻结, 主进程不允许冻结; 可以手动冻结子进程, 也可以把规则改成「只冻选中 / 排除选中」, 让子进程按规则自动部分冻结.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.processMode == AppOptions.PROCESS_MODE_ALL,
                    onClick = { update { it.copy(processMode = AppOptions.PROCESS_MODE_ALL) } },
                    label = { Text("全部进程") }
                )
                FilterChip(
                    selected = current.processMode == AppOptions.PROCESS_MODE_ONLY,
                    onClick = { update { it.copy(processMode = AppOptions.PROCESS_MODE_ONLY) } },
                    label = { Text("只冻选中") }
                )
                FilterChip(
                    selected = current.processMode == AppOptions.PROCESS_MODE_EXCEPT,
                    onClick = { update { it.copy(processMode = AppOptions.PROCESS_MODE_EXCEPT) } },
                    label = { Text("排除选中") }
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = when (current.processMode) {
                    AppOptions.PROCESS_MODE_ONLY -> "只冻结下面勾选的进程, 其余进程保持运行."
                    AppOptions.PROCESS_MODE_EXCEPT -> "冻结除下面勾选以外的所有进程."
                    else -> "冻结该应用的全部进程."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (showInactive) "进程列表（含未运行）" else "进程列表（仅运行中）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        runtime = withContext(Dispatchers.IO) {
                            runCatching { RuntimeClient.loadState() }.getOrNull()
                        }
                    }
                }) {
                    Text("刷新")
                }
            }
            Text(
                text = if (showInactive) {
                    "列出应用声明的全部进程; 没运行的进程也能勾选规则, 等它启动后按规则冻结."
                } else {
                    "只显示当前运行的进程. 可以在设置页打开「显示未运行进程」."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (runtime == null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "读取不到运行状态 (模块没有在运行), 下面只显示已声明的进程, 无法手动冻结.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.size(6.dp))
            if (processRows.isEmpty()) {
                Text(
                    text = "没有可显示的进程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    processRows.forEach { row ->
                        AppProcessRow(
                            packageName = packageName,
                            row = row,
                            selected = current.selected.contains(row.name),
                            canManualFreeze = row.running && appState?.visible != true,
                            isWhiteApp = white,
                            onSelectedChange = { checked ->
                                update {
                                    val set = if (checked) it.selected + row.name else it.selected - row.name
                                    it.copy(selected = set)
                                }
                            },
                            onFreeze = {
                                if (row.running)
                                    sendAction(
                                        RuntimeAction.TYPE_FREEZE_PID,
                                        row.pid,
                                        startTimeOf(appState, row.name)
                                    )
                            },
                            onThaw = {
                                if (row.running)
                                    sendAction(
                                        RuntimeAction.TYPE_THAW_PID,
                                        row.pid,
                                        startTimeOf(appState, row.name)
                                    )
                            }
                        )
                    }
                }
                if (appState?.visible == true) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "应用正在前台, 手动冻结已禁用 (避免卡住当前界面).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { sendAction(RuntimeAction.TYPE_FREEZE_APP) },
                    enabled = appState != null && !appState.visible && !white
                ) {
                    Text("冻结整个应用")
                }
                TextButton(
                    onClick = { sendAction(RuntimeAction.TYPE_THAW_APP) },
                    enabled = appState != null
                ) {
                    Text("解冻整个应用")
                }
            }
            if (white) {
                Text(
                    text = "白名单应用不能冻结整个应用: 请用上面的进程规则或单个进程的「冻结」按钮.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "手动操作会立刻生效, 但系统有 Binder / 广播等唤醒时仍可能自动解冻再重新冻结, 属于正常现象.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppStatusLine(appState: AppRuntimeState?, moduleAlive: Boolean) {
    val (text, color) = when {
        appState == null -> (if (moduleAlive) "未运行" else "状态未知") to Color.Gray
        appState.frozenProcesses > 0 && appState.frozenProcesses >= appState.liveProcesses ->
            "已冻结 (${appState.frozenProcesses}/${appState.liveProcesses} 进程)" to Color(0xFF4CAF50)
        appState.frozenProcesses > 0 ->
            "部分冻结 (${appState.frozenProcesses}/${appState.liveProcesses} 进程)" to Color(0xFFFFA000)
        appState.visible -> "前台运行中 (${appState.liveProcesses} 进程)" to Color(0xFF2196F3)
        appState.audio -> "后台播放音频中 (${appState.liveProcesses} 进程)" to Color(0xFF9C27B0)
        else -> "后台运行中 (${appState.liveProcesses} 进程)" to Color(0xFF757575)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** 进程列表里的一行: 已声明但没运行的进程和正在运行的进程统一成一种数据结构. */
private data class ProcessRowUi(
    val name: String,
    val pid: Int,
    val frozen: Boolean,
    val manuallyFrozen: Boolean,
    val running: Boolean,
)

/**
 * 合并「已声明进程」和「运行中进程」.
 *
 * <p>运行中的进程优先显示, 主进程优先, 其余按名字排序; 打开「显示未运行进程」时
 * 把声明了但当前没启动的进程也列出来 (可以提前配置进程规则).
 */
private fun buildProcessRows(
    appState: AppRuntimeState?,
    declared: List<String>,
    showInactive: Boolean,
    packageName: String,
): List<ProcessRowUi> {
    val running = appState?.processes.orEmpty()
    val runningByName = running.mapNotNull { process ->
        process.name?.let { it to process }
    }.toMap()

    val names = LinkedHashSet<String>()
    names.addAll(declared)
    names.addAll(runningByName.keys)

    return names
        .filter { showInactive || runningByName.containsKey(it) }
        .map { name ->
            val process = runningByName[name]
            ProcessRowUi(
                name = name,
                pid = process?.pid ?: 0,
                frozen = process?.frozen == true,
                manuallyFrozen = process?.manuallyFrozen == true,
                running = process != null
            )
        }
        .sortedWith(
            compareByDescending<ProcessRowUi> { it.running }
                .thenBy { it.name != packageName }
                .thenBy { it.name }
        )
}

/** 手动冻结 / 解冻时取进程启动时间 (校验 pid 没被复用). */
private fun startTimeOf(appState: AppRuntimeState?, processName: String): Long =
    appState?.processes?.firstOrNull { it.name == processName }?.startTime ?: -1L

@Composable
private fun AppProcessRow(
    packageName: String,
    row: ProcessRowUi,
    selected: Boolean,
    canManualFreeze: Boolean,
    isWhiteApp: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onFreeze: () -> Unit,
    onThaw: () -> Unit
) {
    val displayName = row.name.takeIf { it.isNotBlank() } ?: "(未知进程)"
    val isMainProcess = row.name == packageName
    // 白名单应用的主进程永远不会被冻结, 勾选它没有意义, 直接禁用.
    val selectable = !(isWhiteApp && isMainProcess)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = selectable)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isMainProcess) "主进程" else displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = buildString {
                if (row.running) {
                    append("pid ")
                    append(row.pid)
                    if (row.frozen)
                        append(if (row.manuallyFrozen) " · 已冻结(手动)" else " · 已冻结")
                } else {
                    append("未运行")
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    row.frozen -> Color(0xFF4CAF50)
                    !row.running -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (isWhiteApp && isMainProcess) {
                Text(
                    text = "白名单应用的主进程不会被冻结",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (row.running) {
            if (row.frozen) {
                TextButton(onClick = onThaw) { Text("解冻") }
            } else {
                TextButton(onClick = onFreeze, enabled = canManualFreeze) { Text("冻结") }
            }
        }
    }
}

@Composable
private fun AppSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun AppSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

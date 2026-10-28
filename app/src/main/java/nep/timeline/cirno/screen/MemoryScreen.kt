package nep.timeline.cirno.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.MemoryActivity
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.ConfigWriter
import nep.timeline.cirno.configs.settings.GlobalSettings
import nep.timeline.cirno.memory.MemorySettings
import java.util.Locale

/** 串行化配置写入, 避免快速连点开关时两次写文件互相覆盖. */
private val WRITE_LOCK = Any()

/** Kotlin 侧的一份配置快照: Compose 里用不可变 data class 才能可靠触发重组. */
private data class MemoryUiConfig(
    val enabled: Boolean = true,
    val dryRun: Boolean = false,
    val enableReclaim: Boolean = true,
    val enableCompact: Boolean = true,
    val useSwapReclaim: Boolean = false,
    val frozenOnly: Boolean = true,
    val screenOffOnly: Boolean = false,
    val sampleIntervalMs: Int = 5000,
    val psiSomeThreshold: Double = 5.0,
    val psiHighMultiplier: Double = 2.0,
    val psiStopThreshold: Double = 1.0,
    val cooldownMs: Int = 15000,
    val maxRoundsPerCycle: Int = 2,
    val maxAppsPerRound: Int = 4,
    val minCgroupBytes: Long = 32L * 1024 * 1024,
    val maxBytesPerApp: Long = 128L * 1024 * 1024,
    val reclaimRatio: Double = 0.10,
    val foregroundGuard: Boolean = false,
    val foregroundHighRatio: Double = 0.80,
    val foregroundColdCompact: Boolean = false,
    val foregroundColdIntervalMs: Int = 30000,
    val foregroundReclaim: Boolean = false,
    val requestSeq: Long = 0L,
)

private fun MemorySettings.toUi(): MemoryUiConfig = MemoryUiConfig(
    enabled = enabled,
    dryRun = dryRun,
    enableReclaim = enableReclaim,
    enableCompact = enableCompact,
    useSwapReclaim = useSwapReclaim,
    frozenOnly = frozenOnly,
    screenOffOnly = screenOffOnly,
    sampleIntervalMs = sampleIntervalMs,
    psiSomeThreshold = psiSomeThreshold,
    psiHighMultiplier = psiHighMultiplier,
    psiStopThreshold = psiStopThreshold,
    cooldownMs = cooldownMs,
    maxRoundsPerCycle = maxRoundsPerCycle,
    maxAppsPerRound = maxAppsPerRound,
    minCgroupBytes = minCgroupBytes,
    maxBytesPerApp = maxBytesPerApp,
    reclaimRatio = reclaimRatio,
    foregroundGuard = foregroundGuard,
    foregroundHighRatio = foregroundHighRatio,
    foregroundColdCompact = foregroundColdCompact,
    foregroundColdIntervalMs = foregroundColdIntervalMs,
    foregroundReclaim = foregroundReclaim,
    requestSeq = requestSeq,
)

private fun MemoryUiConfig.applyTo(settings: MemorySettings) {
    settings.enabled = enabled
    settings.dryRun = dryRun
    settings.enableReclaim = enableReclaim
    settings.enableCompact = enableCompact
    settings.useSwapReclaim = useSwapReclaim
    settings.frozenOnly = frozenOnly
    settings.screenOffOnly = screenOffOnly
    settings.sampleIntervalMs = sampleIntervalMs
    settings.psiSomeThreshold = psiSomeThreshold
    settings.psiHighMultiplier = psiHighMultiplier
    settings.psiStopThreshold = psiStopThreshold
    settings.cooldownMs = cooldownMs
    settings.maxRoundsPerCycle = maxRoundsPerCycle
    settings.maxAppsPerRound = maxAppsPerRound
    settings.minCgroupBytes = minCgroupBytes
    settings.maxBytesPerApp = maxBytesPerApp
    settings.reclaimRatio = reclaimRatio
    settings.foregroundGuard = foregroundGuard
    settings.foregroundHighRatio = foregroundHighRatio
    settings.foregroundColdCompact = foregroundColdCompact
    settings.foregroundColdIntervalMs = foregroundColdIntervalMs
    settings.foregroundReclaim = foregroundReclaim
    // 注意: requestSeq 不在这里写, 由 requestReclaimOnce() 单独处理,
    // 否则普通开关保存时会把过期的「立即回收」时间戳又写回去, 导致重复触发.
}

private fun loadUiConfig(): MemoryUiConfig {
    val settings = GlobalVars.globalSettings?.memory ?: MemorySettings()
    return settings.toUi()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(activity: MemoryActivity) {
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<MemoryUiConfig?>(null) }
    var rootOk by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var psiText by remember { mutableStateOf("读取中…") }
    var logLines by remember { mutableStateOf(listOf<String>()) }

    // 配置损坏(文件存在但解析失败)时先锁住写入, 避免一次误触把默认值整份覆盖上去.
    var writesLocked by remember { mutableStateOf(false) }

    fun refreshPressure() {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                val out = Shell.cmd("cat /proc/pressure/memory 2>/dev/null").exec().out
                out.joinToString("\n").trim()
            }.getOrDefault("")
            psiText = if (text.isBlank()) "未知(内核未开启 PSI 或没有 root)" else text
        }
    }

    fun refreshLog() {
        scope.launch(Dispatchers.IO) {
            val out = runCatching {
                Shell.cmd("tail -n 30 /data/misc/CirnoRecycle/log/current.log 2>/dev/null").exec().out
            }.getOrDefault(emptyList())
            logLines = out
        }
    }

    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                if (!Shell.getShell().isRoot) {
                    false
                } else {
                    Shell.cmd("mkdir -p /data/misc/CirnoRecycle/log").exec()
                    ConfigManager.manager.readConfigSU()
                    true
                }
            }.getOrDefault(false)
        }
        rootOk = ok
        if (ok) {
            writesLocked = GlobalVars.configCorrupted
            config = withContext(Dispatchers.IO) { loadUiConfig() }
        } else {
            errorText = "没有 root 权限, 无法读取或写入配置.\n请在 Magisk / KernelSU / APatch 里给本应用授权."
        }
        refreshPressure()
        refreshLog()
    }

    /** 普通开关的持久化: 不包含 requestSeq. */
    fun persist(new: MemoryUiConfig) {
        if (writesLocked)
            return
        ConfigWriter.submit {
            runCatching {
                synchronized(WRITE_LOCK) {
                    val global = GlobalVars.globalSettings
                        ?: GlobalSettings().also { GlobalVars.globalSettings = it }
                    val memory = global.memory ?: MemorySettings().also { global.memory = it }
                    new.applyTo(memory)
                    memory.sanitize()
                    ConfigManager.manager.saveConfigSU()
                }
            }.onFailure {
                errorText = "写入配置失败: ${it.message}"
            }
        }
    }

    fun update(block: (MemoryUiConfig) -> MemoryUiConfig) {
        val old = config ?: return
        val new = block(old)
        config = new
        persist(new)
    }

    /** 「立即回收一次」: 只写 requestSeq, 由 system_server 检测到变化后强制跑一轮. */
    fun requestReclaimOnce() {
        if (writesLocked)
            return
        val old = config ?: return
        val seq = System.currentTimeMillis()
        if (seq <= old.requestSeq)
            return
        config = old.copy(requestSeq = seq)
        ConfigWriter.submit {
            runCatching {
                synchronized(WRITE_LOCK) {
                    val global = GlobalVars.globalSettings
                        ?: GlobalSettings().also { GlobalVars.globalSettings = it }
                    val memory = global.memory ?: MemorySettings().also { global.memory = it }
                    memory.requestSeq = seq
                    ConfigManager.manager.saveConfigSU()
                }
            }.onFailure {
                errorText = "写入回收请求失败: ${it.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.largeTopAppBarColors(),
                title = { Text("内存回收设置") },
                navigationIcon = {
                    TextButton(onClick = { activity.finish() }) {
                        Text("返回")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = config
            if (current == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = errorText ?: "正在读取配置…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Surface
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                errorText?.let {
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
                                text = "配置文件解析失败",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "当前显示的是默认值或上一次的旧配置。为避免把可能还能手动修复的配置直接覆盖，写入已暂时锁定。确认要用当前界面上的值覆盖后，再点下面的按钮解锁。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                writesLocked = false
                                errorText = null
                            }) {
                                Text("仍要写入（覆盖损坏的配置）")
                            }
                        }
                    }
                }

                StatusCard(
                    psiText = psiText,
                    logLines = logLines,
                    onRefresh = {
                        refreshPressure()
                        refreshLog()
                    }
                )

                SectionCard("总开关") {
                    SwitchRow(
                        title = "启用内存回收",
                        subtitle = "关闭后 L0/L2 与前台压制全部停止",
                        checked = current.enabled,
                        onCheckedChange = { value -> update { it.copy(enabled = value) } }
                    )
                    SwitchRow(
                        title = "只打印计划 (dryRun)",
                        subtitle = "只写日志不真正回收, 用来确认候选应用是否合理",
                        checked = current.dryRun,
                        onCheckedChange = { value -> update { it.copy(dryRun = value) } }
                    )
                }

                SectionCard("冻结后内存回收") {
                    SwitchRow(
                        title = "L0 · memory.reclaim",
                        subtitle = "直接回收后台 cgroup 的页, 冻结进程同样有效, 需要 Linux 5.19+",
                        checked = current.enableReclaim,
                        onCheckedChange = { value -> update { it.copy(enableReclaim = value) } }
                    )
                    SwitchRow(
                        title = "允许换出到 swap / zram",
                        subtitle = "向 memory.reclaim 写 \"N swap\", 没开 swap 会自动回退",
                        checked = current.useSwapReclaim,
                        onCheckedChange = { value -> update { it.copy(useSwapReclaim = value) } }
                    )
                    SwitchRow(
                        title = "L2 · compactApp 进程压缩",
                        subtitle = "复用系统 CachedAppOptimizer 做 MADV_COLD / MADV_PAGEOUT",
                        checked = current.enableCompact,
                        onCheckedChange = { value -> update { it.copy(enableCompact = value) } }
                    )
                    SwitchRow(
                        title = "只回收已冻结应用",
                        subtitle = "关闭后连还没冻结的后台应用也一起回收(更容易卡顿)",
                        checked = current.frozenOnly,
                        onCheckedChange = { value -> update { it.copy(frozenOnly = value) } }
                    )
                    SwitchRow(
                        title = "仅息屏时回收",
                        subtitle = "亮屏使用时不回收, 优先保证交互流畅",
                        checked = current.screenOffOnly,
                        onCheckedChange = { value -> update { it.copy(screenOffOnly = value) } }
                    )
                    SliderRow(
                        title = "每轮最多回收应用数",
                        valueText = "${current.maxAppsPerRound} 个",
                        value = current.maxAppsPerRound.toFloat(),
                        range = 1f..8f,
                        steps = 6,
                        onChangeFinished = { value -> update { it.copy(maxAppsPerRound = value.toInt()) } }
                    )
                    SliderRow(
                        title = "单应用回收比例",
                        valueText = "${(current.reclaimRatio * 100).toInt()}%",
                        value = (current.reclaimRatio * 100).toFloat(),
                        range = 5f..50f,
                        steps = 8,
                        onChangeFinished = { value -> update { it.copy(reclaimRatio = value / 100.0) } }
                    )
                }

                SectionCard("PSI 触发条件") {
                    SliderRow(
                        title = "触发阈值 (some/full avg10)",
                        valueText = String.format(Locale.US, "%.1f%%", current.psiSomeThreshold),
                        value = current.psiSomeThreshold.toFloat(),
                        range = 1f..30f,
                        steps = 28,
                        onChangeFinished = { value -> update { it.copy(psiSomeThreshold = value.toDouble()) } }
                    )
                    SliderRow(
                        title = "采样间隔",
                        valueText = "${current.sampleIntervalMs / 1000} 秒",
                        value = current.sampleIntervalMs / 1000f,
                        range = 2f..30f,
                        steps = 27,
                        onChangeFinished = { value -> update { it.copy(sampleIntervalMs = (value * 1000).toInt()) } }
                    )
                    SliderRow(
                        title = "冷却时间",
                        valueText = "${current.cooldownMs / 1000} 秒",
                        value = current.cooldownMs / 1000f,
                        range = 5f..120f,
                        steps = 22,
                        onChangeFinished = { value -> update { it.copy(cooldownMs = (value * 1000).toInt()) } }
                    )
                    SliderRow(
                        title = "高压时连续回收轮数",
                        valueText = "${current.maxRoundsPerCycle} 轮",
                        value = current.maxRoundsPerCycle.toFloat(),
                        range = 1f..4f,
                        steps = 2,
                        onChangeFinished = { value -> update { it.copy(maxRoundsPerCycle = value.toInt()) } }
                    )
                    SliderRow(
                        title = "高压判定倍数",
                        valueText = String.format(Locale.US, "%.1fx", current.psiHighMultiplier),
                        value = current.psiHighMultiplier.toFloat(),
                        range = 1f..6f,
                        steps = 9,
                        onChangeFinished = { value -> update { it.copy(psiHighMultiplier = value.toDouble()) } }
                    )
                    SliderRow(
                        title = "提前停止阈值",
                        valueText = String.format(Locale.US, "%.1f%%", current.psiStopThreshold),
                        value = current.psiStopThreshold.toFloat(),
                        range = 0f..10f,
                        steps = 9,
                        onChangeFinished = { value -> update { it.copy(psiStopThreshold = value.toDouble()) } }
                    )
                    SliderRow(
                        title = "最小回收收益",
                        valueText = "${current.minCgroupBytes / (1024 * 1024)} MB",
                        value = (current.minCgroupBytes / (1024 * 1024)).toFloat(),
                        range = 8f..256f,
                        steps = 30,
                        onChangeFinished = { value ->
                            update { it.copy(minCgroupBytes = value.toLong() * 1024 * 1024) }
                        }
                    )
                    SliderRow(
                        title = "单应用单次回收上限",
                        valueText = "${current.maxBytesPerApp / (1024 * 1024)} MB",
                        value = (current.maxBytesPerApp / (1024 * 1024)).toFloat(),
                        range = 32f..512f,
                        steps = 29,
                        onChangeFinished = { value ->
                            update { it.copy(maxBytesPerApp = value.toLong() * 1024 * 1024) }
                        }
                    )
                }

                SectionCard("前台内存压制 (实验性)") {
                    SwitchRow(
                        title = "top-app memory.high 软上限",
                        subtitle = "前台内存失控时先限流, 避免整机 OOM; 需要内核提供 top-app memory.high",
                        checked = current.foregroundGuard,
                        onCheckedChange = { value -> update { it.copy(foregroundGuard = value) } }
                    )
                    SliderRow(
                        title = "软上限占物理内存",
                        valueText = "${(current.foregroundHighRatio * 100).toInt()}%",
                        value = (current.foregroundHighRatio * 100).toFloat(),
                        range = 50f..95f,
                        steps = 8,
                        onChangeFinished = { value -> update { it.copy(foregroundHighRatio = value / 100.0) } }
                    )
                    SwitchRow(
                        title = "前台冷页压缩",
                        subtitle = "内存有压力时对可见应用调用 compactApp, 把冷页标记为可回收 (需要 L2 总开关开启)",
                        checked = current.foregroundColdCompact,
                        onCheckedChange = { value -> update { it.copy(foregroundColdCompact = value) } }
                    )
                    SliderRow(
                        title = "前台冷页压缩间隔",
                        valueText = "${current.foregroundColdIntervalMs / 1000} 秒",
                        value = current.foregroundColdIntervalMs / 1000f,
                        range = 10f..120f,
                        steps = 21,
                        onChangeFinished = { value ->
                            update { it.copy(foregroundColdIntervalMs = (value * 1000).toInt()) }
                        }
                    )
                    SwitchRow(
                        title = "前台组直接回收 (foreground cgroup)",
                        subtitle = "对可见应用所在 cgroup 写 memory.reclaim, 不含当前前台应用; 需要内核在 foreground 组开启 memory 控制器",
                        checked = current.foregroundReclaim,
                        onCheckedChange = { value -> update { it.copy(foregroundReclaim = value) } }
                    )
                }

                SectionCard("操作") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                requestReclaimOnce()
                            },
                            enabled = current.enabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("立即回收一次")
                        }
                        Button(
                            onClick = {
                                refreshPressure()
                                refreshLog()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("刷新状态")
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "立即回收会忽略 PSI 阈值与冷却, 强制跑一轮候选筛选和回收.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Cirno Recycle+ v1.0.0 · 作者 flyingr\n基于 Nep-Timeline/Cirno (GPL-3.0) 二次修改, 开源且免费",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    psiText: String,
    logLines: List<String>,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "运行状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "PSI (内存压力)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = psiText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            Text(
                text = "最近日志",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (logLines.isEmpty()) {
                Text(
                    text = "还没有日志 (日志在 /data/misc/CirnoRecycle/log/current.log)",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    logLines.takeLast(12).forEach { line ->
                        Text(text = line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
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
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChangeFinished: (Float) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onChangeFinished(local) }
        )
    }
}

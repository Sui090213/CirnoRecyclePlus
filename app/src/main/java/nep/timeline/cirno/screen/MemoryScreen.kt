package nep.timeline.cirno.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nep.timeline.cirno.BuildConfig
import nep.timeline.cirno.GlobalVars
import nep.timeline.cirno.MemoryActivity
import nep.timeline.cirno.R
import nep.timeline.cirno.client.UpdateChecker
import nep.timeline.cirno.configs.ConfigManager
import nep.timeline.cirno.configs.ConfigWriter
import nep.timeline.cirno.configs.settings.GlobalSettings
import nep.timeline.cirno.memory.MemorySettings
import java.util.Locale
import kotlin.math.roundToInt

/** 串行化配置写入, 避免快速连点开关时两次写文件互相覆盖. */
private val WRITE_LOCK = Any()

/** 1 MB 的字节数. 滑块按 MB 显示, 写回配置时仍用字节. */
private const val MB = 1024L * 1024L

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
    val minCgroupBytes: Long = 32L * MB,
    val maxBytesPerApp: Long = 128L * MB,
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

/**
 * 回收强度预设.
 *
 * <p>把「触发阈值 / 连续轮数 / 每轮应用数 / 冷却」等 10 个互相耦合的参数打包成一组,
 * 普通用户只需要选一档, 不用理解每个滑块的物理含义; 想细调仍然可以去「高级设置」.
 *
 * <p>只覆盖上面这 10 个参数, 总开关 / 息屏回收 / 只回收冻结应用 / 前台压制等开关保持不变.
 */
private data class RecyclePreset(
    val name: String,
    val description: String,
    val psiSomeThreshold: Double,
    val psiHighMultiplier: Double,
    val psiStopThreshold: Double,
    val cooldownMs: Int,
    val maxRoundsPerCycle: Int,
    val maxAppsPerRound: Int,
    val minCgroupBytes: Long,
    val maxBytesPerApp: Long,
    val reclaimRatio: Double,
    val sampleIntervalMs: Int,
) {
    /** 把预设套用到一份配置快照上 (纯函数, 调用方负责统一保存一次). */
    fun applyTo(config: MemoryUiConfig): MemoryUiConfig = config.copy(
        psiSomeThreshold = psiSomeThreshold,
        psiHighMultiplier = psiHighMultiplier,
        psiStopThreshold = psiStopThreshold,
        cooldownMs = cooldownMs,
        maxRoundsPerCycle = maxRoundsPerCycle,
        maxAppsPerRound = maxAppsPerRound,
        minCgroupBytes = minCgroupBytes,
        maxBytesPerApp = maxBytesPerApp,
        reclaimRatio = reclaimRatio,
        sampleIntervalMs = sampleIntervalMs,
    )

    /** 当前配置是否与这档预设完全一致; 用于给 FilterChip 打勾. */
    fun matches(config: MemoryUiConfig): Boolean =
        config.psiSomeThreshold == psiSomeThreshold &&
            config.psiHighMultiplier == psiHighMultiplier &&
            config.psiStopThreshold == psiStopThreshold &&
            config.cooldownMs == cooldownMs &&
            config.maxRoundsPerCycle == maxRoundsPerCycle &&
            config.maxAppsPerRound == maxAppsPerRound &&
            config.minCgroupBytes == minCgroupBytes &&
            config.maxBytesPerApp == maxBytesPerApp &&
            config.reclaimRatio == reclaimRatio &&
            config.sampleIntervalMs == sampleIntervalMs
}

/** 三档预设: 数值与「均衡」档默认值一致, 覆盖从省电到激进. */
private val PRESETS = listOf(
    RecyclePreset(
        name = "保守",
        description = "只在高压力时轻量回收, 最省电, 适合日常使用",
        psiSomeThreshold = 8.0,
        psiHighMultiplier = 2.0,
        psiStopThreshold = 2.0,
        cooldownMs = 30000,
        maxRoundsPerCycle = 1,
        maxAppsPerRound = 2,
        minCgroupBytes = 64L * MB,
        maxBytesPerApp = 64L * MB,
        reclaimRatio = 0.05,
        sampleIntervalMs = 8000,
    ),
    RecyclePreset(
        name = "均衡",
        description = "默认档位, 压力上来后温和回收几轮, 兼顾流畅与耗电",
        psiSomeThreshold = 5.0,
        psiHighMultiplier = 2.0,
        psiStopThreshold = 1.0,
        cooldownMs = 15000,
        maxRoundsPerCycle = 2,
        maxAppsPerRound = 4,
        minCgroupBytes = 32L * MB,
        maxBytesPerApp = 128L * MB,
        reclaimRatio = 0.10,
        sampleIntervalMs = 5000,
    ),
    RecyclePreset(
        name = "激进",
        description = "压力一高就连续回收, 回收得更多但更耗电, 可能偶发卡顿",
        psiSomeThreshold = 3.0,
        psiHighMultiplier = 1.5,
        psiStopThreshold = 0.5,
        cooldownMs = 8000,
        maxRoundsPerCycle = 3,
        maxAppsPerRound = 8,
        minCgroupBytes = 16L * MB,
        maxBytesPerApp = 256L * MB,
        reclaimRatio = 0.20,
        sampleIntervalMs = 3000,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<MemoryUiConfig?>(null) }
    var rootOk by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var psiText by remember { mutableStateOf("读取中…") }
    var logLines by remember { mutableStateOf(listOf<String>()) }

    // 配置损坏(文件存在但解析失败)时先锁住写入, 避免一次误触把默认值整份覆盖上去.
    var writesLocked by remember { mutableStateOf(false) }

    // 高级设置默认收起: 大多数人只需要用预设, 细节参数按需展开.
    var advancedExpanded by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // 更新检查: 手动检查有新版时弹对话框; checkingUpdate 防止重复点击.
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

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
                    // 和写队列拿同一把锁: 避免读到「已经排进队列但还没落盘」的旧配置,
                    // 导致刚改的设置被下一次保存写回旧值.
                    synchronized(ConfigWriter.LOCK) {
                        ConfigManager.manager.readConfigSU()
                    }
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
                    if (!ConfigManager.manager.saveConfigSU()) {
                        throw IllegalStateException("配置文件写入失败 (可能是 root 权限或磁盘问题)")
                    }
                }
            }.onFailure {
                errorText = "写入配置失败: ${it.message}"
            }
        }
    }

    /** 改一处配置: 先更新界面状态, 再排队写一次文件. */
    fun update(block: (MemoryUiConfig) -> MemoryUiConfig) {
        if (writesLocked) {
            errorText = "配置损坏，写入已锁定；请先点上面的「仍要写入（覆盖损坏的配置）」解锁"
            return
        }
        val old = config ?: return
        val new = block(old)
        config = new
        persist(new)
    }

    /** 点回收强度预设: 一次性套用 10 个参数, 只排一次写入. */
    fun applyPreset(preset: RecyclePreset) {
        if (writesLocked) {
            errorText = "配置损坏，写入已锁定；请先点上面的「仍要写入（覆盖损坏的配置）」解锁"
            return
        }
        val old = config ?: return
        if (preset.matches(old))
            return
        val new = preset.applyTo(old)
        config = new
        persist(new)
    }

    /** 恢复默认: 用 MemorySettings 的默认值整份覆盖 (含总开关), 只写一次文件. */
    fun resetToDefaults() {
        if (writesLocked) {
            errorText = "配置损坏，写入已锁定；请先点上面的「仍要写入（覆盖损坏的配置）」解锁"
            return
        }
        val defaults = MemorySettings().toUi()
        config = defaults
        persist(defaults)
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
                    if (!ConfigManager.manager.saveConfigSU()) {
                        throw IllegalStateException("配置文件写入失败 (可能是 root 权限或磁盘问题)")
                    }
                }
            }.onFailure {
                errorText = "写入回收请求失败: ${it.message}"
            }
        }
    }

    /** 手动检查更新: 有新版弹对话框, 已是最新 / 失败用 Toast 提示. */
    fun checkUpdateNow() {
        if (checkingUpdate)
            return
        checkingUpdate = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { UpdateChecker.check() }
            }.getOrNull()
            checkingUpdate = false
            when (result) {
                is UpdateChecker.Result.Available -> updateInfo = result.info
                is UpdateChecker.Result.UpToDate -> Toast.makeText(
                    context,
                    "当前已是最新版本 (v${BuildConfig.VERSION_NAME})",
                    Toast.LENGTH_SHORT
                ).show()
                is UpdateChecker.Result.Failed -> Toast.makeText(
                    context,
                    "检查更新失败: ${result.message}",
                    Toast.LENGTH_SHORT
                ).show()
                null -> Toast.makeText(context, "检查更新失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = config

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                // 状态卡不管配置有没有读出来都显示, 这样没 root 时也能看到状态和日志入口.
                XiaomiCompatCard()
                StatusCard(
                    rootOk = rootOk,
                    configLoaded = current != null,
                    psiText = psiText,
                    logLines = logLines,
                    onRefresh = {
                        refreshPressure()
                        refreshLog()
                    }
                )

                // 外观 / 通用设置不依赖 root 和 system_server 配置, 没有 root 时也应该能用.
                AppearanceSettingsCard()
                BackgroundSettingsCard()
                GeneralSettingsCard(
                    checkingUpdate = checkingUpdate,
                    onCheckUpdate = { checkUpdateNow() }
                )
                ConfigBackupCard(
                    onImported = {
                        // 导入会替换 GlobalVars 里的配置对象, 这里同步刷新界面状态.
                        config = loadUiConfig()
                        writesLocked = GlobalVars.configCorrupted
                        errorText = null
                    }
                )

                if (current == null) {
                    LoadingCard(errorText)
                } else {
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

                    // ----------------------------------------------------------
                    // B. 基础设置: 总开关 + 回收强度预设 + 常被用到的开关
                    // ----------------------------------------------------------
                    SectionCard("基础设置") {
                        SwitchRow(
                            title = "启用内存回收",
                            subtitle = "关闭后 L0/L2 与前台压制全部停止",
                            checked = current.enabled,
                            onCheckedChange = { value -> update { it.copy(enabled = value) } }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 回收强度: 三档预设, 选中后一次性写入一组参数.
                        val selectedPreset = PRESETS.firstOrNull { it.matches(current) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(text = "回收强度", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.size(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PRESETS.forEach { preset ->
                                    FilterChip(
                                        selected = preset == selectedPreset,
                                        onClick = { applyPreset(preset) },
                                        label = { Text(preset.name) }
                                    )
                                }
                            }
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = selectedPreset?.description
                                    ?: "当前参数是自定义组合, 三档预设都不完全匹配",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        SwitchRow(
                            title = "仅息屏时回收",
                            subtitle = "亮屏使用时不回收, 优先保证交互流畅",
                            checked = current.screenOffOnly,
                            onCheckedChange = { value -> update { it.copy(screenOffOnly = value) } }
                        )
                        SwitchRow(
                            title = "只回收已冻结应用",
                            subtitle = "关闭后连还没冻结的后台应用也一起回收(更容易卡顿)",
                            checked = current.frozenOnly,
                            onCheckedChange = { value -> update { it.copy(frozenOnly = value) } }
                        )
                        SwitchRow(
                            title = "冻结后回收 memory.reclaim",
                            subtitle = "直接回收后台 cgroup 的页, 冻结进程同样有效, 需要 Linux 5.19+",
                            checked = current.enableReclaim,
                            onCheckedChange = { value -> update { it.copy(enableReclaim = value) } }
                        )
                        SwitchRow(
                            title = "进程压缩 compactApp",
                            subtitle = "复用系统 CachedAppOptimizer 做 MADV_COLD / MADV_PAGEOUT",
                            checked = current.enableCompact,
                            onCheckedChange = { value -> update { it.copy(enableCompact = value) } }
                        )
                    }

                    // ----------------------------------------------------------
                    // C. 前台内存压制: 只留总开关, 细节收进高级设置
                    // ----------------------------------------------------------
                    SectionCard("前台内存压制 (实验性)") {
                        SwitchRow(
                            title = "启用前台内存压制",
                            subtitle = "前台内存失控时先限流, 避免整机 OOM; 需要内核支持 top-app memory.high",
                            checked = current.foregroundGuard,
                            onCheckedChange = { value -> update { it.copy(foregroundGuard = value) } }
                        )
                        Text(
                            text = "给前台应用设置内存软上限，具体参数在高级设置里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ----------------------------------------------------------
                    // D. 高级设置: 默认折叠, 点标题展开
                    // ----------------------------------------------------------
                    CollapsibleCard(
                        title = "高级设置",
                        subtitle = "阈值、轮数、上限等细节参数, 不确定就用上面的回收强度预设",
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded }
                    ) {
                        // PSI 采样与节奏
                        SliderRow(
                            title = "采样间隔",
                            valueText = "${current.sampleIntervalMs / 1000} 秒",
                            value = current.sampleIntervalMs / 1000f,
                            range = 2f..30f,
                            steps = 27,
                            onChangeFinished = { value ->
                                update {
                                    val newInterval = (value * 1000).toInt()
                                    // 冷却时间不能小于采样间隔, 否则 sanitize 会静默拉高.
                                    it.copy(
                                        sampleIntervalMs = newInterval,
                                        cooldownMs = maxOf(it.cooldownMs, newInterval)
                                    )
                                }
                            }
                        )
                        SliderRow(
                            title = "触发阈值 (some avg10)",
                            valueText = String.format(Locale.US, "%.1f%%", current.psiSomeThreshold),
                            value = current.psiSomeThreshold.toFloat(),
                            range = 1f..30f,
                            steps = 28,
                            onChangeFinished = { value ->
                                // 提前停止阈值不能高于触发阈值, 否则 sanitize 会静默改小.
                                update {
                                    val threshold = value.toDouble()
                                    it.copy(
                                        psiSomeThreshold = threshold,
                                        psiStopThreshold = minOf(it.psiStopThreshold, threshold)
                                    )
                                }
                            }
                        )
                        // 触发阈值可能被手动改坏 (负数 / 超大值), 先夹到滑块能表示的区间, 避免 coerceIn 抛异常.
                        val stopMax = current.psiSomeThreshold.toFloat().coerceIn(1f, 100f)
                        SliderRow(
                            title = "提前停止阈值",
                            valueText = String.format(Locale.US, "%.1f%%", current.psiStopThreshold),
                            value = current.psiStopThreshold.coerceIn(0.0, stopMax.toDouble()).toFloat(),
                            range = 0f..stopMax,
                            // 用 0.1% 的步长, 保证预设里的 0.5 / 1.0 / 2.0 都能对上.
                            steps = ((stopMax * 10).toInt() - 1).coerceAtLeast(1),
                            onChangeFinished = { value ->
                                // 提前停止阈值不能高于触发阈值, 否则 sanitize 会静默把它改小.
                                update {
                                    val stop = (value * 10f).roundToInt() / 10.0
                                    it.copy(psiStopThreshold = minOf(stop, it.psiSomeThreshold))
                                }
                            }
                        )
                        SliderRow(
                            title = "高压判定倍数",
                            valueText = String.format(Locale.US, "%.1fx", current.psiHighMultiplier),
                            value = current.psiHighMultiplier.toFloat(),
                            range = 1f..6f,
                            steps = 9,
                            onChangeFinished = { value ->
                                update { it.copy(psiHighMultiplier = value.toDouble()) }
                            }
                        )
                        // 冷却时间不能小于采样间隔, 这里把滑块下限抬到采样间隔 (夹到 5..119 秒, 保证区间非空).
                        val cooldownMinSec = (current.sampleIntervalMs / 1000).coerceIn(5, 119)
                        SliderRow(
                            title = "冷却时间 (不小于采样间隔)",
                            valueText = "${current.cooldownMs / 1000} 秒",
                            value = current.cooldownMs / 1000f,
                            range = cooldownMinSec.toFloat()..120f,
                            steps = (120 - cooldownMinSec - 1).coerceAtLeast(0),
                            onChangeFinished = { value ->
                                // 冷却不能小于采样间隔, 否则 sanitize 会静默拉高, UI 和落盘值不一致.
                                update {
                                    it.copy(
                                        cooldownMs = maxOf((value * 1000).toInt(), it.sampleIntervalMs)
                                    )
                                }
                            }
                        )
                        SliderRow(
                            title = "高压时连续回收轮数",
                            valueText = "${current.maxRoundsPerCycle} 轮",
                            value = current.maxRoundsPerCycle.toFloat(),
                            range = 1f..4f,
                            steps = 2,
                            onChangeFinished = { value ->
                                update { it.copy(maxRoundsPerCycle = value.toInt()) }
                            }
                        )
                        SliderRow(
                            title = "每轮最多应用数",
                            valueText = "${current.maxAppsPerRound} 个",
                            value = current.maxAppsPerRound.toFloat(),
                            range = 1f..8f,
                            steps = 6,
                            onChangeFinished = { value ->
                                update { it.copy(maxAppsPerRound = value.toInt()) }
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 回收量
                        SliderRow(
                            title = "最小回收收益",
                            valueText = "${current.minCgroupBytes / MB} MB",
                            value = (current.minCgroupBytes / MB).toFloat(),
                            range = 8f..256f,
                            steps = 30,
                            onChangeFinished = { value ->
                                update { it.copy(minCgroupBytes = value.toLong() * MB) }
                            }
                        )
                        SliderRow(
                            title = "单应用单次上限",
                            valueText = "${current.maxBytesPerApp / MB} MB",
                            value = (current.maxBytesPerApp / MB).toFloat(),
                            range = 32f..512f,
                            steps = 29,
                            onChangeFinished = { value ->
                                update { it.copy(maxBytesPerApp = value.toLong() * MB) }
                            }
                        )
                        SliderRow(
                            title = "单应用回收比例",
                            valueText = "${(current.reclaimRatio * 100).roundToInt()}%",
                            value = (current.reclaimRatio * 100).toFloat(),
                            range = 5f..50f,
                            steps = 8,
                            onChangeFinished = { value ->
                                update { it.copy(reclaimRatio = value / 100.0) }
                            }
                        )
                        SwitchRow(
                            title = "允许换出到 swap / zram",
                            subtitle = "向 memory.reclaim 写 \"N swap\", 没开 swap 会自动回退",
                            checked = current.useSwapReclaim,
                            onCheckedChange = { value -> update { it.copy(useSwapReclaim = value) } }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 调试
                        SwitchRow(
                            title = "只打印计划 (dryRun)",
                            subtitle = "只写日志不真正回收, 用来确认候选应用是否合理",
                            checked = current.dryRun,
                            onCheckedChange = { value -> update { it.copy(dryRun = value) } }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 前台内存压制的细节参数
                        Text(
                            text = "前台内存压制细节",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        SliderRow(
                            title = "软上限占物理内存",
                            valueText = "${(current.foregroundHighRatio * 100).roundToInt()}%",
                            value = (current.foregroundHighRatio * 100).toFloat(),
                            range = 50f..95f,
                            steps = 8,
                            onChangeFinished = { value ->
                                update { it.copy(foregroundHighRatio = value / 100.0) }
                            }
                        )
                        SwitchRow(
                            title = "前台冷页压缩",
                            subtitle = "内存有压力时对可见应用调用 compactApp, 把冷页标记为可回收 (需要进程压缩开启)",
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

                    // ----------------------------------------------------------
                    // E. 操作: 立即回收 / 刷新 / 恢复默认
                    // ----------------------------------------------------------
                    SectionCard("操作") {
                        Button(
                            onClick = { requestReclaimOnce() },
                            enabled = current.enabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("立即回收一次")
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    refreshPressure()
                                    refreshLog()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("刷新状态")
                            }
                            TextButton(
                                onClick = { showResetConfirm = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("恢复默认设置", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            text = "立即回收会忽略 PSI 阈值与冷却, 强制跑一轮候选筛选和回收.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "恢复默认会重置所有内存回收参数 (包括总开关), 无法撤销.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ----------------------------------------------------------
                    // F. 页脚
                    // ----------------------------------------------------------
                    Text(
                        text = "Cirno Recycle+ v" + BuildConfig.VERSION_NAME +
                            " · 作者 " + stringResource(R.string.app_author) +
                            "\n基于 Nep-Timeline/Cirno (GPL-3.0) 二次修改, 开源且免费",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }

    // 恢复默认的二次确认: 会覆盖所有内存参数, 避免误触.
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认设置") },
            text = {
                Text("会把所有内存回收参数（总开关、回收强度、前台压制、高级设置）重置为默认值，此操作无法撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    resetToDefaults()
                }) {
                    Text("恢复默认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 手动检查更新发现新版本时弹提示.
    updateInfo?.let { info ->
        UpdateAvailableDialog(
            info = info,
            onDismiss = { updateInfo = null }
        )
    }
}

/** 配置还没读出来时的占位卡: 加载动画 + 错误 / 提示文字. */
@Composable
private fun LoadingCard(errorText: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = errorText ?: "正在读取配置…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorText != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** A. 状态卡: 激活状态点 + PSI 一行 + 可折叠的最近日志. */
@Composable
private fun StatusCard(
    rootOk: Boolean,
    configLoaded: Boolean,
    psiText: String,
    logLines: List<String>,
    onRefresh: () -> Unit,
) {
    var logExpanded by remember { mutableStateOf(false) }

    val active = rootOk && configLoaded
    val dotColor = when {
        active -> Color(0xFF4CAF50)
        rootOk -> Color(0xFFFFA000)
        else -> Color.Gray
    }
    val statusText = when {
        active -> "已激活"
        rootOk -> "等待配置加载"
        else -> "未激活 (需要 root)"
    }
    // PSI 原始输出有两行 (some / full), 这里只取第一行压成一行显示.
    val psiLine = psiText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = dotColor, shape = CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = "内存压力 (PSI)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = psiLine.ifBlank { "未知" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = "最近日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { logExpanded = !logExpanded }) {
                    Text(if (logExpanded) "收起" else "展开")
                }
            }
            if (logExpanded) {
                if (logLines.isEmpty()) {
                    Text(
                        text = "还没有日志 (日志在 /data/misc/CirnoRecycle/log/current.log)",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    // 只显示最后 6 行, 避免状态卡被日志撑得太长.
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        logLines.takeLast(6).forEach { line ->
                            Text(text = line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** 普通分组卡: 标题 + 分隔线 + 内容. */
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

/** 可折叠分组卡: 点标题行展开 / 收起, 高级设置用它. */
@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
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
    onChangeFinished: (Float) -> Unit,
) {
    // 拖动过程中只改本地状态, 松手 (onValueChangeFinished) 才写文件, 避免每帧都写一次 JSON.
    // key 用基本类型 (而不是 range 对象), 保证拖动引发重组时不会把本地值重置回旧值.
    var local by remember(value, range.start, range.endInclusive) {
        mutableStateOf(value.coerceIn(range))
    }

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

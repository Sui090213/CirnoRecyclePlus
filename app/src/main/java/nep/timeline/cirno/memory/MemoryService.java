package nep.timeline.cirno.memory;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.configs.settings.GlobalSettings;
import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.entity.AppState;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.virtuals.ProcessRecord;

/**
 * 内存回收模块 (L0 memory.reclaim + L2 compact) 与前台内存压制.
 *
 * <p>工作方式:
 * <ol>
 *     <li>在独立的 Memory HandlerThread 上每 {@link MemorySettings#sampleIntervalMs} 采样一次 PSI;</li>
 *     <li>some / full avg10 超过阈值时, 从 Cirno 已有的 AppRecord 里挑出可回收的应用
 *         (非系统 / 非前台可见 / 非音频 / 非录音 / 非定位 / 非 VPN, 默认只挑已冻结应用);</li>
 *     <li>按 cgroup memory.current 从大到小, 对每个应用先写 memory.reclaim 回收页,
 *         再调用 CachedAppOptimizer.compactApp 请求进程压缩;</li>
 *     <li>每回收一个应用就重新看一次 PSI, 压力降下来就提前结束, 避免过度回收;</li>
 *     <li>有冷却时间, 避免持续高压下反复回收; 高压时可以在一轮里连续回收多遍.</li>
 * </ol>
 *
 * <p>为什么不用 scheduleTrimMemory: Cirno 冻结后的进程收不到 onTrimMemory, 而 memory.reclaim
 * 和 process_madvise 都是内核侧操作, 对冻结进程同样有效, 不需要先解冻.
 *
 * <p>L0 与 L2 可以单独开关 ({@link MemorySettings#enableReclaim} / {@link MemorySettings#enableCompact}),
 * 任何一层不可用时都会自动降级, 不影响另一层.
 */
public class MemoryService {
    static final int LEVEL_NORMAL = 0;
    static final int LEVEL_MODERATE = 1;
    static final int LEVEL_HIGH = 2;
    private static final long NO_CANDIDATE_LOG_INTERVAL_MS = 60_000L;

    /** 前台组 (foreground cgroup) 的候选路径, 按优先级排列. */
    private static final String[] FOREGROUND_GROUP_CANDIDATES = {
            "/sys/fs/cgroup/foreground",
            "/sys/fs/cgroup/apps/foreground",
            "/sys/fs/cgroup/system/foreground"
    };

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final PsiMonitor.Stats PSI_STATS = new PsiMonitor.Stats();

    private static volatile long lastCycleElapsed = 0L;
    private static volatile long lastForegroundCompactElapsed = 0L;
    private static volatile long lastNoCandidateLogElapsed = 0L;
    private static volatile long lastRequestSeq = 0L;
    private static volatile int lastLevel = -1;
    private static boolean requestBaselineInitialized = false;

    private static boolean unsupportedLogged = false;
    private static boolean psiUnsupportedLogged = false;
    private static boolean reclaimUnsupportedLogged = false;
    private static boolean foregroundGroupLogged = false;
    private static String foregroundGroupDir;

    private MemoryService() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true))
            return;

        Log.i("内存回收模块已启动, cgroup v2 = " + CgroupMemory.isAvailable()
                + ", memory.reclaim = " + CgroupMemory.isReclaimSupported()
                + ", PSI = " + PsiMonitor.isSupported()
                + ", top-app memory.high = " + ForegroundGuard.isSupported());
        Handlers.memory.post(MemoryService::loop);
    }

    private static void loop() {
        int delay;
        try {
            MemorySettings settings = getSettings();
            settings.sanitize();
            delay = settings.sampleIntervalMs;
            tick(settings);
        } catch (Throwable throwable) {
            Log.e("内存回收线程异常", throwable);
            delay = 5000;
        }
        Handlers.memory.postDelayed(MemoryService::loop, delay);
    }

    private static MemorySettings getSettings() {
        GlobalSettings globalSettings = GlobalVars.globalSettings;
        if (globalSettings == null || globalSettings.memory == null) {
            // 配置尚未加载完成 (或 JSON 里被写成 null) 时按关闭处理, 避免误回收.
            MemorySettings fallback = new MemorySettings();
            fallback.enabled = false;
            return fallback;
        }
        return globalSettings.memory;
    }

    private static void tick(MemorySettings settings) {
        if (!settings.enabled) {
            ForegroundGuard.restore();
            lastLevel = -1;
            return;
        }

        if (!CgroupMemory.isAvailable() && !PsiMonitor.isSupported()) {
            if (!unsupportedLogged) {
                unsupportedLogged = true;
                Log.w("当前内核不支持内存回收: cgroup v2 = " + CgroupMemory.isAvailable()
                        + ", PSI = " + PsiMonitor.isSupported());
            }
            return;
        }

        if (settings.enableReclaim && !CgroupMemory.isReclaimSupported() && !reclaimUnsupportedLogged) {
            reclaimUnsupportedLogged = true;
            Log.w("内核未提供 memory.reclaim (需要 Linux 5.19+), 将只使用 L2 内存压缩");
        }

        // 前台压制由开关直接驱动, 不依赖 PSI: 只要打开就保持软上限.
        if (settings.foregroundGuard) {
            ForegroundGuard.apply(settings.foregroundHighRatio);
        } else {
            ForegroundGuard.restore();
        }

        // 手动「立即回收一次」优先处理: 即使内核没有 PSI, 也应该能手动跑一轮回收.
        boolean forced = handleForcedRequest(settings);

        boolean psiOk = PsiMonitor.isSupported() && PsiMonitor.read(PSI_STATS);
        if (!psiOk) {
            if (!forced) {
                if (!PsiMonitor.isSupported() && !psiUnsupportedLogged) {
                    psiUnsupportedLogged = true;
                    Log.w("内核未开启 PSI (/proc/pressure/memory 不存在), 自动回收已停用 (手动回收仍可用)");
                }
                return;
            }
        }

        int level = psiOk ? levelOf(settings, PSI_STATS) : LEVEL_MODERATE;
        if (psiOk && level != lastLevel) {
            lastLevel = level;
            Log.i("内存压力变化: " + PSI_STATS.toShortString() + " -> " + levelName(level));
        }

        if (level == LEVEL_NORMAL && !forced)
            return;

        if (!forced) {
            if (settings.screenOffOnly && ScreenState.isInteractive())
                return;

            long now = SystemClock.elapsedRealtime();
            if (now - lastCycleElapsed < settings.cooldownMs)
                return;
            lastCycleElapsed = now;
        }

        if (settings.foregroundColdCompact)
            compactForegroundIfNeeded(settings, forced ? LEVEL_MODERATE : level);

        reclaimCycle(settings, forced ? LEVEL_MODERATE : level, forced);
    }

    /**
     * 检测「立即回收一次」请求. 序号变化时返回 true, 由调用方忽略 PSI 阈值与冷却.
     *
     * <p>不把 JSON 里的序号清零: 清零需要 system_server 回写配置, 可能覆盖掉 App 刚改的
     * 其它字段. 这里改成「首次采样只记录基线, 之后序号变化才触发」, 重启后残留的旧序号
     * 会被当成基线忽略, 不会在开机时莫名其妙回收一轮.
     */
    private static boolean handleForcedRequest(MemorySettings settings) {
        long requestSeq = settings.requestSeq;

        if (!requestBaselineInitialized) {
            requestBaselineInitialized = true;
            lastRequestSeq = requestSeq;
            return false;
        }

        if (requestSeq == 0L || requestSeq == lastRequestSeq)
            return false;

        lastRequestSeq = requestSeq;
        Log.i("收到手动回收请求 (seq=" + requestSeq + "), 立即执行一轮回收");
        return true;
    }

    static int levelOf(MemorySettings settings, PsiMonitor.Stats stats) {
        double pressure = pressureValue(stats);
        if (pressure >= settings.psiSomeThreshold * settings.psiHighMultiplier)
            return LEVEL_HIGH;
        if (pressure >= settings.psiSomeThreshold)
            return LEVEL_MODERATE;
        return LEVEL_NORMAL;
    }

    /**
     * 取一个用于分级的压力值: some 和 full 取较大者.
     * some 表示「有任务因为内存停摆」, full 表示「所有任务都停摆」, 两个都很重要.
     */
    private static double pressureValue(PsiMonitor.Stats stats) {
        return Math.max(stats.someAvg10, stats.fullAvg10);
    }

    private static void reclaimCycle(MemorySettings settings, int level, boolean forced) {
        int maxRounds = level == LEVEL_HIGH ? settings.maxRoundsPerCycle : 1;
        if (forced)
            maxRounds = Math.max(1, Math.min(maxRounds, 2));

        long totalReclaimed = 0L;
        int totalCompacted = 0;
        int totalApps = 0;
        boolean dryRunLogged = false;

        for (int round = 0; round < maxRounds; round++) {
            if (round > 0) {
                if (!PsiMonitor.read(PSI_STATS))
                    break;
                if (pressureValue(PSI_STATS) <= settings.psiStopThreshold) {
                    Log.i("内存压力已降到 " + PSI_STATS.toShortString() + ", 提前结束本轮回收");
                    break;
                }
                if (levelOf(settings, PSI_STATS) == LEVEL_NORMAL)
                    break;
            }

            List<Candidate> candidates = collectCandidates(settings);
            if (candidates.isEmpty()) {
                long now = SystemClock.elapsedRealtime();
                if (forced || now - lastNoCandidateLogElapsed >= NO_CANDIDATE_LOG_INTERVAL_MS) {
                    lastNoCandidateLogElapsed = now;
                    Log.d("内存回收: 没有可回收的应用 (压力级别 " + levelName(level)
                            + ", frozenOnly=" + settings.frozenOnly + ")");
                }
                break;
            }

            int acted = 0;
            int roundCompacted = 0;
            long roundReclaimed = 0L;

            for (Candidate candidate : candidates) {
                if (acted >= settings.maxAppsPerRound)
                    break;

                String name = candidate.appRecord.getPackageNameWithUser();
                long target = Math.min((long) (candidate.bytes * settings.reclaimRatio), settings.maxBytesPerApp);

                if (settings.dryRun) {
                    StringBuilder plan = new StringBuilder("[dryRun] 计划回收 ")
                            .append(name).append(": ").append(formatBytes(candidate.bytes))
                            .append(", frozen=").append(candidate.appRecord.isFrozen());
                    if (settings.enableReclaim)
                        plan.append(" -> memory.reclaim 目标 ").append(formatBytes(target))
                                .append(settings.useSwapReclaim ? " (允许 swap)" : "");
                    if (settings.enableCompact)
                        plan.append(" -> compact");
                    Log.i(plan.toString());
                    acted++;
                    dryRunLogged = true;
                    continue;
                }

                boolean did = false;

                if (settings.enableReclaim && target > 0L) {
                    long freed = CgroupMemory.reclaim(candidate.dir, target, settings.useSwapReclaim);
                    if (freed >= 0L) {
                        roundReclaimed += freed;
                        did = true;
                        long after = CgroupMemory.getCurrentBytes(candidate.dir);
                        Log.i("内存回收 " + name + ": " + formatBytes(candidate.bytes) + " -> "
                                + (after < 0L ? "未知" : formatBytes(after))
                                + " (请求 " + formatBytes(target) + ", 实际 " + formatBytes(freed) + ")");
                    } else if (freed == CgroupMemory.RESULT_FAILED) {
                        // EBUSY / EAGAIN / EINVAL: 没什么可回收的, 属于正常情况, 交给 L2.
                        Log.d("内存回收 " + name + " 无收益 (可能已无可回收页)");
                    }
                }

                if (settings.enableCompact) {
                    int compacted = compactProcesses(candidate.appRecord);
                    if (compacted > 0) {
                        roundCompacted += compacted;
                        did = true;
                    }
                }

                if (did)
                    acted++;
            }

            totalApps += acted;
            totalReclaimed += roundReclaimed;
            totalCompacted += roundCompacted;

            Log.i("内存回收完成: 压力=" + levelName(level) + ", 应用数=" + acted
                    + ", 共回收 " + formatBytes(roundReclaimed) + ", 压缩进程数=" + roundCompacted);

            // dryRun / 没有候选 / 本遍没东西可做时不再重复.
            if (settings.dryRun || dryRunLogged || acted == 0 || acted < settings.maxAppsPerRound)
                break;
        }

        if (totalApps > 1 || totalReclaimed > 0L || totalCompacted > 0) {
            Log.i("本轮回收汇总: 应用数=" + totalApps + ", 共回收 " + formatBytes(totalReclaimed)
                    + ", 压缩进程数=" + totalCompacted);
        }

        if (settings.foregroundReclaim && settings.enableReclaim) {
            if (settings.dryRun) {
                String dir = foregroundGroupDir;
                if (dir == null)
                    dir = CgroupMemory.findCgroupDir("memory.reclaim", FOREGROUND_GROUP_CANDIDATES);
                if (dir != null) {
                    long current = CgroupMemory.getCurrentBytes(dir);
                    if (current > 0L) {
                        long target = Math.min((long) (current * settings.reclaimRatio), settings.maxBytesPerApp);
                        Log.i("[dryRun] 计划回收前台组 " + dir + ": " + formatBytes(current)
                                + " -> memory.reclaim 目标 " + formatBytes(target));
                    }
                }
            } else {
                reclaimForegroundGroup(settings);
            }
        }
    }

    /**
     * 前台组回收: 直接对 foreground cgroup 写 memory.reclaim.
     *
     * <p>foreground 组里是「可见但没在交互」的应用, 回收它们的冷页不会影响当前操作,
     * 还能明显减轻内存压力; 找不到该 cgroup 时只提示一次.
     */
    private static void reclaimForegroundGroup(MemorySettings settings) {
        String dir = foregroundGroupDir;
        if (dir == null) {
            dir = CgroupMemory.findCgroupDir("memory.reclaim", FOREGROUND_GROUP_CANDIDATES);
            if (dir == null) {
                if (!foregroundGroupLogged) {
                    foregroundGroupLogged = true;
                    Log.w("当前内核没有 foreground 组的 memory.reclaim 接口, 前台组回收已跳过");
                }
                return;
            }
            foregroundGroupDir = dir;
        }

        long current = CgroupMemory.getCurrentBytes(dir);
        if (current < settings.minCgroupBytes)
            return;

        long target = Math.min((long) (current * settings.reclaimRatio), settings.maxBytesPerApp);
        if (target <= 0L)
            return;

        long freed = CgroupMemory.reclaim(dir, target, settings.useSwapReclaim);
        if (freed >= 0L) {
            long after = CgroupMemory.getCurrentBytes(dir);
            Log.i("前台组内存回收: " + formatBytes(current) + " -> "
                    + (after < 0L ? "未知" : formatBytes(after))
                    + " (请求 " + formatBytes(target) + ", 实际 " + formatBytes(freed) + ")");
        }
    }

    /**
     * 前台冷页压缩: 对「可见但不会被回收」的应用调用 compactApp, 把冷页标记为可回收.
     */
    private static void compactForegroundIfNeeded(MemorySettings settings, int level) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastForegroundCompactElapsed < settings.foregroundColdIntervalMs)
            return;

        AppRecord foreground = null;
        for (AppRecord appRecord : AppService.getAll()) {
            if (appRecord == null || appRecord.isSystem())
                continue;
            if (appRecord.getAppState() == null || !appRecord.getAppState().isVisible())
                continue;
            if (appRecord.getProcessRecords().isEmpty())
                continue;
            foreground = appRecord;
            break;
        }

        if (foreground == null)
            return;

        lastForegroundCompactElapsed = now;
        int compacted = compactProcesses(foreground);
        if (compacted > 0) {
            Log.i("前台冷页压缩 " + foreground.getPackageNameWithUser() + ": " + compacted + " 个进程 (压力="
                    + levelName(level) + ")");
        }
    }

    private static List<Candidate> collectCandidates(MemorySettings settings) {
        List<Candidate> candidates = new ArrayList<>();
        Set<Integer> seenUid = new HashSet<>();

        for (AppRecord appRecord : AppService.getAll()) {
            if (!isReclaimable(appRecord, settings))
                continue;

            int uid = appRecord.getUid();
            if (uid <= 0 || !seenUid.add(uid))
                continue;

            // 先按静态路径探测; 失败时从存活进程的 /proc/<pid>/cgroup 反查真实目录.
            String dir = CgroupMemory.getDir(uid);
            if (dir == null) {
                for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
                    if (processRecord == null || processRecord.isDeathProcess())
                        continue;
                    dir = CgroupMemory.resolveUidDirFromPid(processRecord.getPid());
                    if (dir != null)
                        break;
                }
            }
            if (dir == null)
                continue;

            long current = CgroupMemory.getCurrentBytes(dir);
            if (current < 0L || current < settings.minCgroupBytes)
                continue;

            candidates.add(new Candidate(appRecord, dir, current));
        }

        candidates.sort((left, right) -> Long.compare(right.bytes, left.bytes));
        return candidates;
    }

    /**
     * L2: 对应用的所有存活进程请求 compact.
     */
    private static int compactProcesses(AppRecord appRecord) {
        int count = 0;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            if (CompactHelper.compact(processRecord))
                count++;
        }
        return count;
    }

    /**
     * 与 Cirno 的冻结判定保持一致: 活跃应用不回收.
     */
    private static boolean isReclaimable(AppRecord appRecord, MemorySettings settings) {
        if (appRecord == null || appRecord.isSystem())
            return false;

        AppState appState = appRecord.getAppState();
        if (appState == null)
            return false;

        if (appState.isVisible() || appState.isLocation() || appState.isAudio()
                || appState.isRecording() || appState.isVpn())
            return false;

        if (settings.frozenOnly && !appRecord.isFrozen())
            return false;

        boolean hasLiveProcess = false;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord != null && !processRecord.isDeathProcess()) {
                hasLiveProcess = true;
                break;
            }
        }
        return hasLiveProcess;
    }

    private static String levelName(int level) {
        switch (level) {
            case LEVEL_HIGH:
                return "高压";
            case LEVEL_MODERATE:
                return "中等";
            default:
                return "正常";
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L)
            return bytes + "B";
        if (bytes < 1024L * 1024L)
            return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static final class Candidate {
        private final AppRecord appRecord;
        private final String dir;
        private final long bytes;

        private Candidate(AppRecord appRecord, String dir, long bytes) {
            this.appRecord = appRecord;
            this.dir = dir;
            this.bytes = bytes;
        }
    }
}

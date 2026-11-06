package nep.timeline.cirno.memory;

import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.entity.AppState;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.utils.ProcessStat;
import nep.timeline.cirno.utils.PushChannelGuard;
import nep.timeline.cirno.virtuals.ProcessRecord;

/**
 * 长时间冻结后的子进程深度回收.
 *
 * <p>只处理「已经完整冻结、冻结超过阈值、当前没有可见 / 音频 / 定位 / VPN」的应用.
 * 只杀容器型子进程 (:appbrand / :xweb / :sandbox / :video / :reader),
 * 主进程、推送通道、系统应用、白名单应用全部跳过; 游戏子进程默认不动, 避免丢未保存进度.
 *
 * <p>这不是 keepalive 模块那种「后台 5 分钟无脑杀子进程」: 默认只在内存有压力时执行,
 * 且默认 {@code reapApply=false} 只写 dry-run 日志; 用户确认名单后才真正 kill.
 */
public final class ProcessReaper {
    /** 同一个应用最多尝试两轮; 再出现就退避 6 小时, 防止 START_STICKY 服务反复拉起. */
    private static final int MAX_REAP_ROUNDS = 2;
    private static final long REAP_BACKOFF_MS = 6L * 60L * 60_000L;

    private ProcessReaper() {
    }

    public static void reap(MemorySettings settings) {
        if (settings == null || !settings.reapFrozenChildren)
            return;

        long now = SystemClock.elapsedRealtime();
        long minFrozenMs = Math.max(1, settings.reapMinFrozenMinutes) * 60_000L;
        int apps = 0;

        for (AppRecord appRecord : AppService.getAll()) {
            if (apps >= Math.max(1, settings.reapMaxAppsPerCycle))
                break;
            if (!canReap(appRecord, settings, now, minFrozenMs))
                continue;

            List<Candidate> candidates = collectCandidates(appRecord, settings);
            if (candidates.isEmpty())
                continue;

            int acted = 0;
            for (Candidate candidate : candidates) {
                if (acted >= Math.max(1, settings.reapMaxProcessesPerApp))
                    break;
                if (!stillValid(candidate.processRecord))
                    continue;

                String name = appRecord.getPackageNameWithUser();
                String processName = candidate.processRecord.getProcessName();
                int pid = candidate.processRecord.getPid();

                if (!settings.reapApply) {
                    Log.i("深度回收计划 (dry-run) " + name + " " + processName
                            + " pid=" + pid + " rss=" + formatBytes(candidate.rss)
                            + " 冻结=" + appRecord.getFrozenSinceMs() / 60_000L + "分钟");
                    acted++;
                    continue;
                }

                try {
                    Process.killProcess(pid);
                    acted++;
                    Log.i("深度回收 " + name + " 杀掉 " + processName
                            + " pid=" + pid + " rss=" + formatBytes(candidate.rss));
                } catch (Throwable throwable) {
                    Log.w("深度回收 " + name + " 杀掉 " + processName + " pid=" + pid + " 失败", throwable);
                }
            }

            if (acted == 0)
                continue;

            appRecord.setLastChildReapMs(now);
            if (settings.reapApply) {
                int count = appRecord.getChildReapCount() + 1;
                appRecord.setChildReapCount(count);
                if (count >= MAX_REAP_ROUNDS) {
                    appRecord.setChildReapBlockedUntilMs(now + REAP_BACKOFF_MS);
                    Log.i("深度回收 " + appRecord.getPackageNameWithUser()
                            + " 已连续回收 " + count + " 轮, 退避 6 小时");
                }
            }
            apps++;
        }
    }

    private static boolean canReap(AppRecord appRecord, MemorySettings settings, long now, long minFrozenMs) {
        if (appRecord == null || appRecord.isSystem())
            return false;
        if (!appRecord.isFrozen())
            return false;

        long frozenSince = appRecord.getFrozenSinceMs();
        if (frozenSince <= 0L || now - frozenSince < minFrozenMs)
            return false;

        if (appRecord.getChildReapBlockedUntilMs() > now)
            return false;
        if (now - appRecord.getLastChildReapMs() < Math.max(60_000, settings.reapCooldownMs))
            return false;

        AppState appState = appRecord.getAppState();
        return appState != null
                && !appState.isVisible()
                && !appState.isLocation()
                && !appState.isAudio()
                && !appState.isRecording()
                && !appState.isVpn();
    }

    private static List<Candidate> collectCandidates(AppRecord appRecord, MemorySettings settings) {
        List<Candidate> candidates = new ArrayList<>();

        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
                continue;
            if (FreezerService.isMainProcess(appRecord, processRecord.getProcessName()))
                continue;
            if (PushChannelGuard.isPushProcess(processRecord.getProcessName()))
                continue;
            if (!isReapableProcess(processRecord.getProcessName()))
                continue;

            int pid = processRecord.getPid();
            // 只动纯缓存级进程; adj 还是 service / perceptible 的子进程不碰.
            if (ProcessStat.getOomScoreAdj(pid) < 900)
                continue;

            long rss = ProcessStat.getRssBytes(pid);
            if (rss < 0L || rss < settings.reapMinProcessBytes)
                continue;

            candidates.add(new Candidate(processRecord, rss));
        }

        candidates.sort(Comparator.comparingLong((Candidate candidate) -> candidate.rss).reversed());
        return candidates;
    }

    /**
     * 只认容器型子进程. 这里故意用白名单, 不用黑名单:
     * 遇到不认识的子进程宁可跳过, 也不要杀掉应用的关键服务.
     */
    static boolean isReapableProcess(String processName) {
        if (processName == null)
            return false;
        int index = processName.lastIndexOf(':');
        if (index < 0 || index == processName.length() - 1)
            return false;

        String suffix = processName.substring(index + 1).toLowerCase(Locale.ROOT);
        return suffix.startsWith("appbrand")
                || suffix.startsWith("xweb")
                || suffix.startsWith("sandbox")
                || suffix.equals("video")
                || suffix.equals("reader");
    }

    /** kill 前复核进程还活着, 且 pid 没有被复用. */
    private static boolean stillValid(ProcessRecord processRecord) {
        if (processRecord == null || processRecord.isDeathProcess() || !processRecord.isFrozen())
            return false;
        long startTime = ProcessStat.getStartTime(processRecord.getPid());
        return startTime > 0L && startTime == processRecord.getStartTime();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L)
            return bytes + "B";
        if (bytes < 1024L * 1024L)
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L)
            return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static final class Candidate {
        private final ProcessRecord processRecord;
        private final long rss;

        private Candidate(ProcessRecord processRecord, long rss) {
            this.processRecord = processRecord;
            this.rss = rss;
        }
    }
}

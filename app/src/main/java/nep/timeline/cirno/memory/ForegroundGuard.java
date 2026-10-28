package nep.timeline.cirno.memory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nep.timeline.cirno.log.Log;

/**
 * 前台内存压制: 给 top-app / foreground cgroup 写 {@code memory.high} 软上限.
 *
 * <p>内核语义: {@code memory.high} 是软上限, 超过之后内核会先对分配方限流并积极回收,
 * 而不是像 {@code memory.max} 那样直接 OOM kill. 把它压在一个「正常使用不会碰到, 但
 * 前台应用失控膨胀时能兜底」的位置, 可以在整机内存耗尽前先把前台应用的内存增长刹住,
 * 给后台/冻结应用留出空间.
 *
 * <p>不同 ROM 的 cgroup 布局差异很大, 因此这里对候选路径逐个探测, 找不到
 * {@code memory.high} 就自动跳过并只打印一次警告, 不会影响其它功能.
 *
 * <p>安全约定:
 * <ul>
 *     <li>只改 {@code memory.high}, 绝不改 {@code memory.max};</li>
 *     <li>如果 ROM 自己已经设置了更严格的 memory.high, 不覆盖;</li>
 *     <li>记录原始值, 关闭开关时恢复;</li>
 *     <li>上限低于 512MB 时拒绝写入, 避免把设备写死.</li>
 * </ul>
 */
public class ForegroundGuard {
    /** top-app / foreground cgroup 的候选路径, 按优先级排列. */
    private static final String[] CANDIDATE_DIRS = {
            "/sys/fs/cgroup/top-app",
            "/sys/fs/cgroup/foreground",
            "/sys/fs/cgroup/apps/top-app",
            "/sys/fs/cgroup/apps/foreground",
            "/sys/fs/cgroup/system/top-app",
            "/sys/fs/cgroup/uid_1000/top-app"
    };

    private static final long MIN_LIMIT_BYTES = 512L * 1024 * 1024;

    private static boolean resolved = false;
    private static String cgroupDir;
    private static boolean unsupportedLogged = false;
    private static boolean writeFailedLogged = false;

    private static String originalValue;
    private static long appliedLimit = -1L;

    private ForegroundGuard() {
    }

    /** 当前内核 / ROM 是否提供了可写的 top-app memory.high. */
    public static synchronized boolean isSupported() {
        return resolveDir() != null;
    }

    /**
     * 应用前台内存压制. 幂等: 同一个上限重复调用不会反复写文件.
     *
     * @param ratio 上限占物理内存的比例 (0.50 ~ 0.95)
     */
    public static synchronized void apply(double ratio) {
        String dir = resolveDir();
        if (dir == null)
            return;

        long total = CgroupMemory.getTotalMemBytes();
        if (total <= 0L) {
            logWriteFailed("读不到 /proc/meminfo MemTotal");
            return;
        }

        long limit = (long) (total * ratio);
        if (limit < MIN_LIMIT_BYTES) {
            logWriteFailed("计算出的上限小于 512MB, 已拒绝写入 (" + CgroupMemory.readText(new File(dir, "memory.high")) + ")");
            return;
        }

        File highFile = new File(dir, "memory.high");
        String current = CgroupMemory.readText(highFile);
        if (current == null) {
            logWriteFailed("读取 memory.high 失败");
            return;
        }
        current = current.trim();
        if (current.isEmpty())
            current = "max";

        if (limit == appliedLimit) {
            // 已经写过了, 不重复写.
            return;
        }

        long romLimit = parseLimit(current);
        if (romLimit > 0L && romLimit <= limit) {
            // ROM 自己设置的限制更严格, 尊重 ROM, 不覆盖.
            Log.i("前台压制: ROM 已设置更严格的 memory.high=" + current + ", 跳过");
            appliedLimit = limit;
            originalValue = current;
            return;
        }

        if (!writeValue(highFile, Long.toString(limit))) {
            logWriteFailed("写入 " + highFile + " 失败");
            return;
        }

        originalValue = current;
        appliedLimit = limit;
        Log.i("前台压制已启用: " + dir + "/memory.high = " + limit
                + " (" + formatPercent(ratio) + " of " + formatBytes(total) + "), 原值=" + current);
    }

    /**
     * 恢复原始 memory.high. 关闭开关 / 总开关时调用.
     */
    public static synchronized void restore() {
        if (appliedLimit < 0L || cgroupDir == null || originalValue == null) {
            appliedLimit = -1L;
            return;
        }

        File highFile = new File(cgroupDir, "memory.high");
        if (writeValue(highFile, originalValue)) {
            Log.i("前台压制已恢复: " + highFile + " = " + originalValue);
        } else {
            Log.w("前台压制恢复失败: " + highFile + " = " + originalValue);
        }

        appliedLimit = -1L;
        originalValue = null;
    }

    private static String resolveDir() {
        if (resolved)
            return cgroupDir;

        resolved = true;
        cgroupDir = CgroupMemory.findCgroupDir("memory.high", CANDIDATE_DIRS);
        if (cgroupDir == null && !unsupportedLogged) {
            unsupportedLogged = true;
            Log.w("当前内核没有 top-app/foreground 的 memory.high 接口, 前台内存压制已跳过");
        }
        return cgroupDir;
    }

    private static boolean writeValue(File file, String value) {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return true;
        } catch (IOException e) {
            // 大概率是 SELinux 策略不允许 system_server 写这个文件.
            lastWriteError = e.toString();
            return false;
        }
    }

    private static volatile String lastWriteError;

    private static void logWriteFailed(String reason) {
        if (writeFailedLogged)
            return;
        writeFailedLogged = true;
        String error = lastWriteError;
        Log.w("前台内存压制不可用: " + reason + (error == null ? "" : " (" + error + ")"));
        lastWriteError = null;
    }

    private static long parseLimit(String text) {
        if (text == null)
            return -1L;
        String value = text.trim();
        if (value.isEmpty() || "max".equals(value))
            return -1L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String formatPercent(double ratio) {
        return String.format(java.util.Locale.US, "%.0f%%", ratio * 100.0);
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

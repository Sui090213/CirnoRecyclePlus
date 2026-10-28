package nep.timeline.cirno.memory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.log.Log;

/**
 * 前台内存压制: 给 top-app / foreground cgroup 写 {@code memory.high} 软上限.
 *
 * <p>内核语义: {@code memory.high} 是软上限, 超过之后内核会先对分配方限流并积极回收,
 * 而不是像 {@code memory.max} 那样直接 OOM kill. 把它压在一个「正常使用不会碰到, 但
 * 前台应用失控膨胀时能兜底」的位置, 可以在整机内存耗尽前先把前台应用的内存增长刹住,
 * 给后台 / 冻结应用留出空间.
 *
 * <p>不同 ROM 的 cgroup 布局差异很大, 因此这里对候选路径逐个探测, 找不到
 * {@code memory.high} 就自动跳过并只打印一次警告, 不会影响其它功能.
 *
 * <p>安全约定:
 * <ul>
 *     <li>只改 {@code memory.high}, 绝不改 {@code memory.max};</li>
 *     <li>如果 ROM 自己已经设置了更严格的 memory.high, 不覆盖;</li>
 *     <li>写入前把 ROM 的原始值持久化到 {@code /data/misc/CirnoRecycle/foreground_guard.orig},
 *         这样即使 system_server 软重启 (LSPosed 常见) 也不会把「我们自己写的值」当成原值;
 *     </li>
 *     <li>关闭开关时恢复原值; 检测到值被外部改动时不恢复, 只清理状态文件;</li>
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

    /** 原始值 / 我们写入值的持久化文件 (system_server 软重启后仍能正确恢复). */
    private static final String STATE_FILE = GlobalVars.CONFIG_DIR + "/foreground_guard.orig";

    private static boolean resolved = false;
    private static String cgroupDir;
    private static boolean unsupportedLogged = false;
    private static boolean romStricterLogged = false;
    private static boolean writeFailedLogged = false;
    private static String lastWriteError;

    private static boolean stateLoaded = false;
    private static String originalValue;
    private static long appliedLimit = -1L;
    private static long lastRestoreWarnMs = 0L;

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
            logWriteFailed("计算出的上限小于 512MB, 已拒绝写入");
            return;
        }

        File highFile = new File(dir, "memory.high");
        String current = readLimit(highFile);
        if (current == null) {
            logWriteFailed("读取 memory.high 失败");
            return;
        }

        loadState();

        // 已经有我们写入的状态, 但文件里的值被外部改掉了: 认为外部接管, 丢弃旧状态.
        if (originalValue != null && appliedLimit > 0L && parseLimit(current) != appliedLimit) {
            Log.i("前台压制: memory.high 被外部修改为 " + current + ", 丢弃旧的原值记录");
            clearState();
        }

        // 没有状态记录时, 当前值就是 ROM 的原始值.
        if (originalValue == null) {
            long romLimit = parseLimit(current);
            if (romLimit > 0L && romLimit <= limit) {
                // ROM 自己设置的限制更严格, 尊重 ROM, 不覆盖.
                appliedLimit = -1L;
                if (!romStricterLogged) {
                    romStricterLogged = true;
                    Log.i("前台压制: ROM 已设置更严格的 memory.high=" + current + ", 跳过");
                }
                return;
            }
            originalValue = current;
        }

        if (limit == appliedLimit) {
            // 已经是我们写入的值, 不重复写.
            return;
        }

        if (!writeValue(highFile, Long.toString(limit))) {
            logWriteFailed("写入 " + highFile + " 失败");
            return;
        }

        appliedLimit = limit;
        saveState();
        Log.i("前台压制已启用: " + dir + "/memory.high = " + limit
                + " (" + formatPercent(ratio) + " of " + formatBytes(total) + "), 原值=" + originalValue);
    }

    /**
     * 恢复原始 memory.high. 关闭开关 / 总开关时调用.
     *
     * <p>如果发现当前值和「我们上次写入的值」不一致, 说明有别的程序改过, 这时不恢复
     * (避免覆盖别人的设置), 只清理状态文件.
     *
     * <p>写失败 / 暂时读不到当前值时**保留状态文件**, 下一个采样周期会再次尝试恢复
     * (本方法每个 tick 都会被调用), 不能因为一次瞬时错误就把 ROM 原值弄丢.
     */
    public static synchronized void restore() {
        loadState();

        if (originalValue == null && appliedLimit < 0L) {
            return;
        }

        String dir = resolveDir();
        if (dir == null) {
            // 这次探测不到目录, 保留状态, 下次再试.
            return;
        }

        File highFile = new File(dir, "memory.high");
        String current = readLimit(highFile);
        if (current == null) {
            // 瞬时读取失败: 保留状态文件, 下次 tick 重试.
            warnRestoreRetry("暂时读不到 memory.high");
            return;
        }

        long currentLimit = parseLimit(current);

        // 当前值被外部改过 (不是我们写入的值): 别人接管了, 放弃恢复, 只清理状态.
        if (appliedLimit > 0L && currentLimit != appliedLimit) {
            Log.i("前台压制: memory.high 已被外部修改为 " + current + ", 不再恢复");
            clearState();
            return;
        }

        if (originalValue == null || appliedLimit <= 0L) {
            clearState();
            return;
        }

        if (!isValidOriginalValue(originalValue)) {
            // 状态文件损坏 (被截断 / 手改坏), 宁可不写也不要往 memory.high 写一个危险值.
            Log.w("前台压制: 保存的原值 \"" + originalValue + "\" 非法, 已丢弃状态文件");
            clearState();
            return;
        }

        if (!writeValue(highFile, originalValue)) {
            // 写失败: 保留状态文件, 下次 tick 继续尝试.
            warnRestoreRetry("恢复写入失败 (" + highFile + " = " + originalValue + ")");
            return;
        }

        Log.i("前台压制已恢复: " + highFile + " = " + originalValue);
        clearState();
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

    /** 读取 memory.high 并去掉换行; 读不到返回 null. */
    private static String readLimit(File file) {
        String text = CgroupMemory.readText(file);
        if (text == null)
            return null;
        String value = text.trim();
        if (value.isEmpty())
            return "max";
        int newline = value.indexOf('\n');
        if (newline >= 0)
            value = value.substring(0, newline).trim();
        return value;
    }

    // ---------------------------------------------------------------------
    // 状态持久化
    // ---------------------------------------------------------------------

    /** restore 失败每 5 秒都会被重试, 日志做 60 秒节流, 避免刷屏. */
    private static void warnRestoreRetry(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastRestoreWarnMs < 60_000L)
            return;
        lastRestoreWarnMs = now;
        Log.w("前台压制恢复暂未成功, 保留原值记录稍后重试: " + reason);
    }

    private static void loadState() {
        if (stateLoaded)
            return;
        stateLoaded = true;

        String text = CgroupMemory.readText(new File(STATE_FILE));
        if (text == null)
            return;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("original=")) {
                originalValue = line.substring("original=".length()).trim();
            } else if (line.startsWith("applied=")) {
                try {
                    appliedLimit = Long.parseLong(line.substring("applied=".length()).trim());
                } catch (NumberFormatException ignored) {
                    appliedLimit = -1L;
                }
            }
        }

        if (originalValue != null && originalValue.isEmpty())
            originalValue = "max";

        // 校验读出来的内容; 损坏的状态文件会让 restore 把危险值写进 memory.high.
        if (originalValue != null && !isValidOriginalValue(originalValue)) {
            Log.w("前台压制: 状态文件内容非法 (original=" + originalValue + "), 已丢弃");
            originalValue = null;
            appliedLimit = -1L;
            deleteStateFile();
            return;
        }
        if (appliedLimit > 0L && appliedLimit < MIN_LIMIT_BYTES) {
            Log.w("前台压制: 状态文件记录的写入值异常 (" + appliedLimit + "), 已丢弃");
            originalValue = null;
            appliedLimit = -1L;
            deleteStateFile();
        }
    }

    /** 只接受 "max" 或 >= 512MB 的合法数字, 避免状态文件损坏后写坏系统全局值. */
    private static boolean isValidOriginalValue(String value) {
        if (value == null)
            return false;
        String trimmed = value.trim();
        if ("max".equals(trimmed))
            return true;
        try {
            long parsed = Long.parseLong(trimmed);
            return parsed >= MIN_LIMIT_BYTES;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void saveState() {
        if (originalValue == null || appliedLimit < 0L) {
            clearState();
            return;
        }

        // 先写临时文件再 rename, 避免 system_server 在写一半时崩溃留下半截状态.
        File file = new File(STATE_FILE);
        File temp = new File(STATE_FILE + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            try (FileOutputStream outputStream = new FileOutputStream(temp)) {
                String content = "original=" + originalValue + "\napplied=" + appliedLimit + "\n";
                outputStream.write(content.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            if (!temp.renameTo(file)) {
                // rename 失败时直接覆写原文件 (不要先删旧文件, 否则两次失败会丢掉原值记录).
                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    outputStream.write(("original=" + originalValue + "\napplied=" + appliedLimit + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException e) {
                    Log.w("保存前台压制原值失败: " + e);
                }
            }
        } catch (IOException e) {
            Log.w("保存前台压制原值失败: " + e);
        } finally {
            if (temp.exists())
                temp.delete();
        }
    }

    private static void clearState() {
        originalValue = null;
        appliedLimit = -1L;
        deleteStateFile();
    }

    private static void deleteStateFile() {
        try {
            File file = new File(STATE_FILE);
            if (file.exists() && !file.delete())
                Log.w("删除前台压制状态文件失败: " + STATE_FILE);
        } catch (Throwable ignored) {
            // 删除失败不影响功能, 下次启动会用当前值重新建立状态.
        }
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

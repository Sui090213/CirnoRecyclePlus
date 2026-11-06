package nep.timeline.cirno.memory;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * cgroup v2 内存接口.
 *
 * <p>路径规则与 Cirno 的 {@link nep.timeline.cirno.utils.FrozenRW} 保持一致, 但做了更宽松的
 * 探测 (部分 ROM 即使是非隔离布局, 应用也仍然放在 apps/ 下):
 * <ul>
 *     <li>非隔离布局: /sys/fs/cgroup/uid_&lt;uid&gt;</li>
 *     <li>隔离布局 + 系统应用: /sys/fs/cgroup/system/uid_&lt;uid&gt;</li>
 *     <li>隔离布局 + 普通应用: /sys/fs/cgroup/apps/uid_&lt;uid&gt;</li>
 * </ul>
 *
 * <p>另外提供 {@link #resolveUidDirFromPid(int)}: 直接从 {@code /proc/<pid>/cgroup} 解析
 * 进程真正所在的 cgroup 目录, 作为静态路径探测失败时的兜底.
 *
 * <p>注意: 这里只接受 "uid_*" 这一层的 cgroup 作为回收入口. 如果某个 ROM 只在父级
 * (apps/system) 开启 memory 控制器, 写 memory.reclaim 会回收整个子树而不是单个应用,
 * 风险太大, 因此直接跳过.
 *
 * <p>memory.reclaim 需要 Linux 5.19+; 旧内核上 {@link #reclaim(String, long, boolean)} 返回
 * {@link #RESULT_UNSUPPORTED}, 调用方应降级到 L2 进程压缩.
 */
public class CgroupMemory {
    /** 该 cgroup 没有 memory.reclaim 接口 (内核太老或布局不支持). */
    public static final long RESULT_UNSUPPORTED = -2L;
    /** 写入失败 (EBUSY / EAGAIN / EINVAL 等), 属于正常情况, 不算致命错误. */
    public static final long RESULT_FAILED = -1L;

    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String APPS_ROOT = CGROUP_ROOT + "/apps";
    private static final String SYSTEM_ROOT = CGROUP_ROOT + "/system";
    private static final String MEMINFO = "/proc/meminfo";

    /** 与 FrozenRW 相同的布局探测: 根目录下存在 uid_1000 说明系统应用没有单独隔离. */
    private static final boolean cgroupV2SysAppIsolated =
            !new File(CGROUP_ROOT + "/uid_1000/cgroup.freeze").exists();

    private static final Map<Integer, String> DIR_CACHE = new ConcurrentHashMap<>();

    private CgroupMemory() {
    }

    /**
     * cgroup v2 + memory 控制器是否可用 (只做粗判, 用于启动日志 / 提前降级).
     */
    public static boolean isAvailable() {
        if (!new File(CGROUP_ROOT + "/cgroup.controllers").exists())
            return false;

        return hasMemoryController()
                || new File(APPS_ROOT, "memory.current").exists()
                || new File(SYSTEM_ROOT, "memory.current").exists()
                || new File(CGROUP_ROOT, "memory.current").exists();
    }

    private static boolean hasMemoryController() {
        String controllers = readText(new File(CGROUP_ROOT, "cgroup.controllers"));
        if (controllers == null)
            return false;
        for (String controller : controllers.trim().split("\\s+")) {
            if ("memory".equals(controller))
                return true;
        }
        return false;
    }

    /**
     * 内核是否提供 memory.reclaim (Linux 5.19+). 只做粗判, 用于启动时提示.
     */
    public static boolean isReclaimSupported() {
        return new File(APPS_ROOT, "memory.reclaim").exists()
                || new File(SYSTEM_ROOT, "memory.reclaim").exists()
                || new File(CGROUP_ROOT, "memory.reclaim").exists()
                || new File(CGROUP_ROOT + "/uid_1000", "memory.reclaim").exists();
    }

    /**
     * 该 uid 的 cgroup 是否支持 memory.reclaim.
     */
    public static boolean isReclaimSupported(int uid) {
        String dir = getDir(uid);
        return dir != null && new File(dir, "memory.reclaim").exists();
    }

    /**
     * 找到该 uid 上真正带 memory 控制器的 cgroup 目录; 找不到返回 null.
     * 只会返回 uid_* 这一层, 不会回退到父级目录.
     */
    public static String getDir(int uid) {
        String cached = DIR_CACHE.get(uid);
        if (cached != null) {
            if (new File(cached, "memory.current").exists())
                return cached;
            DIR_CACHE.remove(uid);
        }

        for (String candidate : candidates(uid)) {
            if (new File(candidate, "memory.current").exists()) {
                DIR_CACHE.put(uid, candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * 从 {@code /proc/<pid>/cgroup} 解析该进程所在应用 (uid_*) 的 cgroup 目录.
     *
     * <p>例: {@code 0::/apps/uid_10123/pid_4567} -> {@code /sys/fs/cgroup/apps/uid_10123}.
     * 解析失败 / 目录不存在返回 null. 只作为 {@link #getDir(int)} 的兜底, 结果会缓存.
     */
    public static String resolveUidDirFromPid(int pid) {
        if (pid <= 0)
            return null;

        String text = readText(new File("/proc/" + pid + "/cgroup"));
        if (text == null)
            return null;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty())
                continue;

            int index = line.indexOf("::");
            if (index < 0 || index == line.length() - 2)
                continue;

            String path = line.substring(index + 2).trim();
            if (!path.startsWith("/"))
                continue;

            String dir = CGROUP_ROOT + path;
            // 向上找到 uid_* 这一层的 cgroup (进程可能在 pid_<pid> 子 cgroup 里).
            while (dir.startsWith(CGROUP_ROOT + "/")) {
                String name = new File(dir).getName();
                if (name.startsWith("uid_")) {
                    if (new File(dir, "memory.current").exists()) {
                        try {
                            int uid = Integer.parseInt(name.substring("uid_".length()));
                            DIR_CACHE.put(uid, dir);
                        } catch (NumberFormatException ignored) {
                            // 解析不出 uid 也没关系, 目录本身可用.
                        }
                        return dir;
                    }
                    break;
                }
                dir = new File(dir).getParent();
                if (dir == null)
                    break;
            }
        }
        return null;
    }

    private static List<String> candidates(int uid) {
        List<String> list = new ArrayList<>(4);

        String primary = uid < Process.FIRST_APPLICATION_UID ? SYSTEM_ROOT : APPS_ROOT;
        if (!cgroupV2SysAppIsolated)
            list.add(CGROUP_ROOT + "/uid_" + uid);

        list.add(primary + "/uid_" + uid);

        // 兜底: 少数 ROM 的布局与 FrozenRW 的探测结果不一致, 三个位置都试一遍.
        if (!primary.equals(APPS_ROOT))
            list.add(APPS_ROOT + "/uid_" + uid);
        if (!primary.equals(SYSTEM_ROOT))
            list.add(SYSTEM_ROOT + "/uid_" + uid);
        list.add(CGROUP_ROOT + "/uid_" + uid);

        return list;
    }

    /**
     * 读取 uid 对应 cgroup 的 memory.current, 失败返回 -1.
     */
    public static long getCurrentBytes(int uid) {
        return getCurrentBytes(getDir(uid));
    }

    /**
     * 读取指定 cgroup 目录的 memory.current, 目录为 null / 读取失败返回 -1.
     */
    public static long getCurrentBytes(String dir) {
        if (dir == null)
            return -1L;

        long value = readLong(new File(dir, "memory.current"));
        if (value >= 0L)
            return value;

        // 目录被移除 (进程全部退出) 时刷新一次缓存.
        for (Map.Entry<Integer, String> entry : DIR_CACHE.entrySet()) {
            if (dir.equals(entry.getValue())) {
                DIR_CACHE.remove(entry.getKey());
                break;
            }
        }
        return -1L;
    }

    /**
     * 尝试从 uid 对应 cgroup 回收 {@code bytes} 字节 (失败时自动用 pid 解析兜底).
     */
    public static long reclaim(int uid, long bytes, boolean swap) {
        String dir = getDir(uid);
        if (dir == null)
            return RESULT_UNSUPPORTED;
        return reclaim(dir, bytes, swap);
    }

    /**
     * 尝试从指定 cgroup 目录回收 {@code bytes} 字节.
     *
     * @param swap 是否允许把匿名页换出到 zram / swap (向 memory.reclaim 写入 "N swap")
     * @return 实际观察到的回收量 (字节); {@link #RESULT_UNSUPPORTED} 表示内核没有该接口;
     * {@link #RESULT_FAILED} 表示写入失败 (EBUSY / EAGAIN / EINVAL, 通常是已经没东西可回收).
     */
    public static long reclaim(String dir, long bytes, boolean swap) {
        if (dir == null || bytes <= 0L)
            return 0L;

        File reclaimFile = new File(dir, "memory.reclaim");
        if (!reclaimFile.exists())
            return RESULT_UNSUPPORTED;

        long before = readLong(new File(dir, "memory.current"));

        boolean ok;
        if (swap) {
            ok = writeReclaim(reclaimFile, bytes + " swap");
            if (!ok) {
                // 没有 swap / zram, 或者内核不支持 swap 参数: 回退到普通回收.
                ok = writeReclaim(reclaimFile, Long.toString(bytes));
            }
        } else {
            ok = writeReclaim(reclaimFile, Long.toString(bytes));
        }

        if (!ok)
            return RESULT_FAILED;

        long after = readLong(new File(dir, "memory.current"));
        if (before < 0L || after < 0L)
            return 0L;

        // memory.current 在回收前后可能因为并发分配而抖动, 结果只用来做展示/统计.
        return Math.max(0L, before - after);
    }

    private static boolean writeReclaim(File file, String value) {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            return true;
        } catch (IOException ignored) {
            // 常见的 EBUSY / EAGAIN / EINVAL: cgroup 里已经没什么可回收的, 属于正常情况.
            return false;
        }
    }

    /**
     * 物理内存总量 (字节), 读不到返回 0.
     */
    public static long getTotalMemBytes() {
        return getMemInfoBytes("MemTotal:", 0L);
    }

    /**
     * 可用内存 (字节), 读不到返回 -1.
     *
     * <p>用 {@code MemAvailable} 而不是 {@code MemFree}: 前者已经把可回收的 page cache
     * 算进去, 更接近「不触发换页 / 回收时还能给新应用用的内存」.
     */
    public static long getAvailableMemBytes() {
        return getMemInfoBytes("MemAvailable:", -1L);
    }

    private static long getMemInfoBytes(String key, long fallback) {
        String text = readText(new File(MEMINFO));
        if (text == null)
            return fallback;

        for (String line : text.split("\n")) {
            if (!line.startsWith(key))
                continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2)
                continue;
            try {
                return Long.parseLong(parts[1]) * 1024L;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 探测一个带指定控制文件的 cgroup 目录, 找不到返回 null.
     *
     * @param fileName 需要存在的控制文件, 例如 "memory.high"
     * @param dirs     候选目录, 按优先级排列
     */
    public static String findCgroupDir(String fileName, String... dirs) {
        if (fileName == null || dirs == null)
            return null;

        for (String dir : dirs) {
            if (dir == null)
                continue;
            if (new File(dir, fileName).exists())
                return dir;
        }
        return null;
    }

    public static long readLong(File file) {
        String text = readText(file);
        if (text == null)
            return -1L;

        try {
            String firstLine = text.trim();
            int newline = firstLine.indexOf('\n');
            if (newline >= 0)
                firstLine = firstLine.substring(0, newline).trim();
            return Long.parseLong(firstLine);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    public static String readText(File file) {
        if (file == null || !file.exists())
            return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder builder = new StringBuilder(64);
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException ignored) {
            return null;
        }
    }
}

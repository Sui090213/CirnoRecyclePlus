package nep.timeline.cirno.runtime;

import android.os.SystemClock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import nep.timeline.cirno.GlobalVars;

/**
 * App 进程与 system_server 之间的运行时状态 / 控制通道 (文件名与公共工具).
 *
 * <p>两个进程 (App 与 LSPosed 注入到 system_server 的模块) 不在同一个地址空间, 也不方便
 * 直接调用 binder, 所以用一个很小的文件协议通信:
 *
 * <ul>
 *     <li>{@link #STATE_FILE}: 由 system_server 每 2 秒 (内容有变化时) 原子覆盖写,
 *         内容是所有正在运行应用的冻结状态与进程列表, App 只读, 用来显示「已冻结」和进程列表;</li>
 *     <li>{@link #ACTIONS_FILE}: 由 App 用 root 原子写 (临时文件 + mv),
 *         system_server 轮询后执行 (手动冻结/解冻进程), 执行结果回写到 STATE_FILE 里.</li>
 * </ul>
 *
 * <p>两个进程都用 {@link #bootTimeMs()} 给文件打「开机标记」: 上一次开机残留的旧文件
 * (比如模块被禁用后遗留、或者旧的未执行操作) 会因为开机时间对不上而被丢弃, 不会被误执行.
 */
public final class RuntimeFiles {
    /** 运行时状态文件名 (放在配置目录里, 由 system_server 写). */
    public static final String STATE_FILE_NAME = "RuntimeState.json";
    /** App 下发的运行时操作文件名 (由 App 用 root 写). */
    public static final String ACTIONS_FILE_NAME = "RuntimeActions.json";

    /** 运行时状态文件绝对路径. */
    public static final String STATE_FILE = GlobalVars.CONFIG_DIR + "/" + STATE_FILE_NAME;
    /** 运行时操作文件绝对路径. */
    public static final String ACTIONS_FILE = GlobalVars.CONFIG_DIR + "/" + ACTIONS_FILE_NAME;

    /** 开机时间容差: 两个进程算出来的开机时间允许有几十毫秒差异. */
    public static final long BOOT_TIME_TOLERANCE_MS = 60_000L;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 缓存的开机时刻: /proc/stat 一次开机内不会变, 不需要反复读. */
    private static volatile long cachedBootTimeMs;

    private RuntimeFiles() {
    }

    /** 共享的 Gson 实例 (Gson 是线程安全的). */
    public static Gson gson() {
        return GSON;
    }

    /**
     * 本次开机的开机时刻 (epoch 毫秒).
     *
     * <p>优先读 {@code /proc/stat} 的 {@code btime} (内核记录的开机 wall-clock 秒数):
     * 它不会因为用户改时间 / NTP 校时而变化, 比「当前时间 - 开机时长」可靠得多.
     * 读不到时退化为 {@code currentTimeMillis - elapsedRealtime}.
     *
     * <p>App 进程和 system_server 都能在无 root 的情况下算出来, 且结果一致,
     * 用它来判断状态文件 / 操作文件是不是本次开机写的, 这样时钟跳变也不会误丢手动操作.
     */
    public static long bootTimeMs() {
        long cached = cachedBootTimeMs;
        if (cached > 0L)
            return cached;

        long value = readBootTimeFromProcStat();
        if (value <= 0L)
            value = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        cachedBootTimeMs = value;
        return value;
    }

    /** 从 /proc/stat 的 btime 行读取开机时刻; 失败返回 -1. */
    private static long readBootTimeFromProcStat() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/stat"))) {
                if (line.startsWith("btime ")) {
                    long seconds = Long.parseLong(line.substring("btime ".length()).trim());
                    if (seconds > 0L)
                        return seconds * 1000L;
                }
            }
        } catch (Throwable ignored) {
            // 没权限 / 内核不提供 btime: 用退化算法.
        }
        return -1L;
    }
}

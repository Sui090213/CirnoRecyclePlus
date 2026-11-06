package nep.timeline.cirno.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 读取 /proc/&lt;pid&gt;/stat 里的进程启动时间, 用于防止 pid 复用导致「手动冻结错进程」.
 *
 * <p>App 侧拿到状态文件里的 pid 后, 用户可能过一会儿才点「冻结」; 这段时间进程可能已经退出,
 * 系统把同一个 pid 分配给同 uid 的另一个进程. 只按 pid 匹配就会冻错进程, 所以执行前需要用
 * starttime (内核里单调递增的时钟滴答数) 校验「还是同一个进程」.
 */
public final class ProcessStat {
    private ProcessStat() {
    }

    /**
     * @return 进程的 oom_score_adj; 读取失败 / 进程不存在时返回 {@link Integer#MIN_VALUE}
     */
    public static int getOomScoreAdj(int pid) {
        if (pid <= 0)
            return Integer.MIN_VALUE;

        try {
            String content = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/oom_score_adj")), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(content);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * @return 进程 RSS (字节); 读取失败 / 进程不存在时返回 -1
     */
    public static long getRssBytes(int pid) {
        if (pid <= 0)
            return -1L;

        try {
            String content = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/status")), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (!line.startsWith("VmRSS:"))
                    continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2)
                    return -1L;
                return Long.parseLong(parts[1]) * 1024L;
            }
        } catch (Throwable ignored) {
            return -1L;
        }
        return -1L;
    }

    /**
     * @return 进程启动时间 (开机以来的时钟滴答数); 读取失败 / 进程不存在时返回 -1
     */
    public static long getStartTime(int pid) {
        if (pid <= 0)
            return -1;

        try {
            String content = new String(Files.readAllBytes(Paths.get("/proc/" + pid + "/stat")), StandardCharsets.UTF_8);
            // stat 的第二列 comm 可能包含空格 / 括号, 必须从最后一个 ')' 之后开始切.
            int end = content.lastIndexOf(')');
            if (end < 0 || end + 2 >= content.length())
                return -1;

            String[] parts = content.substring(end + 2).trim().split("\\s+");
            // parts[0] 是 state (整个 stat 的第 3 列), starttime 是第 22 列 => 下标 19.
            if (parts.length <= 19)
                return -1;
            return Long.parseLong(parts[19]);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}

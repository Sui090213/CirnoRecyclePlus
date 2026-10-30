package nep.timeline.cirno.runtime;

/** 单个进程的运行时状态. */
public class ProcessRuntimeState {
    public int pid;
    public String name;
    public boolean frozen;
    /**
     * 进程启动时间 (/proc/&lt;pid&gt;/stat 第 22 列, 时钟滴答数), 用于手动冻结前校验 pid 没被复用.
     * 读取失败时为 -1, 表示不校验.
     */
    public long startTime = -1L;
}

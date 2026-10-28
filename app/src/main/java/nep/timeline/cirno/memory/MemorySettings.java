package nep.timeline.cirno.memory;

/**
 * 内存回收 / 前台内存压制模块的全局配置.
 *
 * <p>该对象会被 Gson 序列化到 {@code /data/misc/CirnoRecycle/GlobalSettings.json} 的
 * {@code memory} 字段里, 因此新增字段时请务必给它一个合理的默认值: 旧配置文件反序列化时
 * 缺失的字段会自动使用这里的默认值.
 *
 * <p>所有开关都能在 App 的「内存回收设置」界面里修改, 改完写回 JSON, system_server
 * 通过 {@code ConfigFileObserver} 热重载, 不需要重启.
 */
public class MemorySettings {
    /** 内存回收总开关. 关闭后 L0/L2 与前台压制全部停用. */
    public boolean enabled = true;

    /** 只打印回收计划, 不真正执行. 用于在真机上验证候选筛选是否合理. */
    public boolean dryRun = false;

    /** L0: 是否写 cgroup v2 {@code memory.reclaim} 回收页缓存 / 匿名页 (需要 Linux 5.19+). */
    public boolean enableReclaim = true;

    /** L2: 是否调用系统 {@code CachedAppOptimizer.compactApp} 做进程内存压缩 (MADV_COLD / MADV_PAGEOUT). */
    public boolean enableCompact = true;

    /**
     * 是否把匿名页换出到 zram / swap (向 memory.reclaim 写入 "N swap").
     *
     * <p>默认关闭: 只有设备确实配置了 swap / zram 时才有意义, 否则内核会返回 EINVAL,
     * 代码会自动回退到普通回收.
     */
    public boolean useSwapReclaim = false;

    /**
     * 是否只回收「已经被 Cirno 冻结」的应用.
     *
     * <p>默认开启: 冻结进程不会再被用户直接操作, 回收它们的页不会影响前台体验;
     * 还没被冻结的后台应用可能马上就要被切回前台, 回收它们更容易造成卡顿.
     */
    public boolean frozenOnly = true;

    /** 是否只在息屏时回收. 息屏时回收不影响交互, 更省心. */
    public boolean screenOffOnly = false;

    /** PSI 采样间隔 (毫秒). 太小会浪费 CPU, 太大会错过压力峰值. */
    public int sampleIntervalMs = 5000;

    /** PSI {@code some avg10} 触发阈值 (百分比). 达到该值开始回收. */
    public double psiSomeThreshold = 5.0;

    /** PSI {@code some avg10} 达到 {@code 阈值 * 该倍数} 时判定为高压, 一轮里可以连续回收多次. */
    public double psiHighMultiplier = 2.0;

    /** 一轮回收中途, PSI {@code some avg10} 降到该值以下就提前结束, 避免过度回收. */
    public double psiStopThreshold = 1.0;

    /** 两次回收周期之间的最小间隔 (毫秒), 防止持续高压下反复回收抖动. */
    public int cooldownMs = 15000;

    /** 高压时同一轮最多连续回收几遍 (每遍重新采样 PSI 并重新挑选应用). */
    public int maxRoundsPerCycle = 2;

    /** 每个回收周期最多处理多少个应用. */
    public int maxAppsPerRound = 4;

    /** cgroup {@code memory.current} 小于该值时跳过, 回收收益太低. */
    public long minCgroupBytes = 32L * 1024 * 1024;

    /** 单个应用单次最多回收多少字节. */
    public long maxBytesPerApp = 128L * 1024 * 1024;

    /** 单次回收目标 = 该应用 cgroup {@code memory.current} * reclaimRatio. */
    public double reclaimRatio = 0.10;

    /**
     * 前台内存压制开关: 给 top-app / foreground cgroup 写 {@code memory.high} 软上限,
     * 让前台应用在内存失控增长时先被内核限流 (触发 GC / onTrimMemory), 而不是直接
     * 触发整机 OOM 杀掉后台应用.
     *
     * <p>默认关闭: 不同 ROM 的 cgroup 布局差异很大, 且过低的 soft limit 会造成卡顿.
     */
    public boolean foregroundGuard = false;

    /** 前台内存压制上限 = 物理内存 * 该比例 (0.50 ~ 0.95). */
    public double foregroundHighRatio = 0.80;

    /**
     * 前台冷页压缩开关: 内存有压力时, 对「当前可见的应用」调用 {@code compactApp},
     * 把它的冷页标记成可回收页 (MADV_COLD), 压制前台进程常驻内存.
     *
     * <p>默认关闭, 属于实验性功能.
     */
    public boolean foregroundColdCompact = false;

    /** 前台冷页压缩的最小间隔 (毫秒). */
    public int foregroundColdIntervalMs = 30000;

    /**
     * 前台组内存回收开关: 直接对 {@code foreground} 组 (可见应用, 不含当前 top-app) 的
     * cgroup 写 memory.reclaim, 把「看得见但没在前台交互」的应用的冷页回收掉.
     *
     * <p>这是「前台进程内存压制」的另一条实现路径, 与逐应用回收互补; 默认关闭,
     * 需要内核把 memory 控制器开在 foreground 组上.
     */
    public boolean foregroundReclaim = false;

    /**
     * 手动触发序号. App 里点「立即回收一次」会把当前时间戳写进来,
     * system_server 检测到序号变化后强制跑一轮回收 (不受 PSI 阈值和冷却限制),
     * 跑完把序号清零.
     */
    public long requestSeq = 0L;

    /**
     * 校正非法配置, 避免手动改 JSON 改出 0 间隔 / 负数导致线程空转或无限循环.
     */
    public void sanitize() {
        if (sampleIntervalMs < 1000)
            sampleIntervalMs = 1000;
        if (sampleIntervalMs > 120_000)
            sampleIntervalMs = 120_000;

        if (cooldownMs < sampleIntervalMs)
            cooldownMs = sampleIntervalMs;
        if (cooldownMs > 10 * 60_000)
            cooldownMs = 10 * 60_000;

        if (psiSomeThreshold <= 0.0)
            psiSomeThreshold = 5.0;
        if (psiSomeThreshold > 100.0)
            psiSomeThreshold = 100.0;

        if (psiHighMultiplier < 1.0)
            psiHighMultiplier = 1.0;
        if (psiHighMultiplier > 20.0)
            psiHighMultiplier = 20.0;

        if (psiStopThreshold < 0.0)
            psiStopThreshold = 0.0;
        if (psiStopThreshold > psiSomeThreshold)
            psiStopThreshold = psiSomeThreshold;

        if (maxRoundsPerCycle < 1)
            maxRoundsPerCycle = 1;
        if (maxRoundsPerCycle > 4)
            maxRoundsPerCycle = 4;

        if (maxAppsPerRound < 1)
            maxAppsPerRound = 1;
        if (maxAppsPerRound > 32)
            maxAppsPerRound = 32;

        if (minCgroupBytes < 0)
            minCgroupBytes = 0;

        if (maxBytesPerApp < 0)
            maxBytesPerApp = 0;

        if (reclaimRatio <= 0.0)
            reclaimRatio = 0.10;
        if (reclaimRatio > 1.0)
            reclaimRatio = 1.0;

        if (foregroundHighRatio < 0.50)
            foregroundHighRatio = 0.50;
        if (foregroundHighRatio > 0.95)
            foregroundHighRatio = 0.95;

        if (foregroundColdIntervalMs < 10_000)
            foregroundColdIntervalMs = 10_000;
        if (foregroundColdIntervalMs > 10 * 60_000)
            foregroundColdIntervalMs = 10 * 60_000;
    }
}

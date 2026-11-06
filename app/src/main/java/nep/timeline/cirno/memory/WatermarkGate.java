package nep.timeline.cirno.memory;

/**
 * MemAvailable 水位判定 (纯逻辑, 便于单元测试).
 *
 * <p>用途: PSI 不可用 / 不准确时, 用「剩余可用内存百分比」作为兜底.
 * 水位判定带迟滞:
 * <ul>
 *     <li>未触发时, 可用内存 &lt;= trigger 才进入触发态;</li>
 *     <li>已触发时, 要回升到 trigger + hysteresis 才退出触发态;</li>
 *     <li>触发后, 可用内存低于 trigger * 0.6 视为高压.</li>
 * </ul>
 *
 * <p>实例保存「当前是否处于触发态」, 由 {@link MemoryService} 持有.
 * 逻辑不依赖 Android 框架, 可以直接在 JVM 单元测试里覆盖.
 */
public final class WatermarkGate {
    private boolean active;

    /**
     * 更新并返回水位压力等级.
     *
     * @param settings      内存配置; {@code watermarkFallback=false} 时直接返回 NORMAL
     * @param totalBytes    MemTotal (字节)
     * @param availableBytes MemAvailable (字节), 读取失败时传负数
     * @return {@link MemoryService#LEVEL_NORMAL} / {@link MemoryService#LEVEL_MODERATE} / {@link MemoryService#LEVEL_HIGH}
     */
    public int level(MemorySettings settings, long totalBytes, long availableBytes) {
        if (settings == null || !settings.watermarkFallback || totalBytes <= 0L || availableBytes < 0L) {
            active = false;
            return MemoryService.LEVEL_NORMAL;
        }

        double availablePct = availableBytes * 100.0 / totalBytes;
        double trigger = settings.availableTriggerPct;
        double stop = trigger + settings.availableHysteresisPct;

        if (!active) {
            if (availablePct <= trigger)
                active = true;
        } else if (availablePct >= stop) {
            active = false;
        }

        if (!active)
            return MemoryService.LEVEL_NORMAL;

        if (availablePct <= trigger * 0.6)
            return MemoryService.LEVEL_HIGH;
        return MemoryService.LEVEL_MODERATE;
    }

    public boolean isActive() {
        return active;
    }

    /** 关闭兜底 / 恢复默认时清掉迟滞状态. */
    public void reset() {
        active = false;
    }
}

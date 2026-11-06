package nep.timeline.cirno.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 纯 JVM 单元测试: 覆盖 PSI 解析 / 压力分级 / 配置校正这些不依赖 Android 框架的逻辑.
 *
 * <p>运行: ./gradlew testDebugUnitTest
 */
public class MemoryLogicTest {
    @Test
    public void parsePsiSomeAndFull() {
        String text = "some avg10=12.50 avg60=6.25 avg300=1.00 total=123456\n"
                + "full avg10=3.75 avg60=1.50 avg300=0.25 total=654321\n";

        PsiMonitor.Stats stats = new PsiMonitor.Stats();
        assertTrue(PsiMonitor.parse(text, stats));
        assertEquals(12.50, stats.someAvg10, 0.0001);
        assertEquals(6.25, stats.someAvg60, 0.0001);
        assertEquals(1.00, stats.someAvg300, 0.0001);
        assertEquals(123456L, stats.someTotal);
        assertEquals(3.75, stats.fullAvg10, 0.0001);
        assertEquals(654321L, stats.fullTotal);
        assertTrue(stats.fullPresent);
    }

    @Test
    public void parsePsiWithoutFullLine() {
        PsiMonitor.Stats stats = new PsiMonitor.Stats();
        assertTrue(PsiMonitor.parse("some avg10=5.00 avg60=0.00 avg300=0.00 total=1\n", stats));
        assertEquals(5.00, stats.someAvg10, 0.0001);
        assertFalse(stats.fullPresent);
        assertEquals(0.0, stats.fullAvg10, 0.0001);
    }

    @Test
    public void parsePsiRejectsGarbage() {
        PsiMonitor.Stats stats = new PsiMonitor.Stats();
        assertFalse(PsiMonitor.parse("", stats));
        assertFalse(PsiMonitor.parse(null, stats));
        assertFalse(PsiMonitor.parse("some avg10=not-a-number avg60=0 avg300=0 total=0\n", stats));
    }

    @Test
    public void pressureLevels() {
        MemorySettings settings = new MemorySettings();
        settings.psiSomeThreshold = 5.0;
        settings.psiHighMultiplier = 2.0;

        PsiMonitor.Stats stats = new PsiMonitor.Stats();
        stats.someAvg10 = 1.0;
        assertEquals(MemoryService.LEVEL_NORMAL, MemoryService.levelOf(settings, stats));

        stats.someAvg10 = 5.0;
        assertEquals(MemoryService.LEVEL_MODERATE, MemoryService.levelOf(settings, stats));

        stats.someAvg10 = 10.0;
        assertEquals(MemoryService.LEVEL_HIGH, MemoryService.levelOf(settings, stats));

        // full 压力也算: some 低但 full 高时同样要触发.
        stats.someAvg10 = 0.0;
        stats.fullAvg10 = 11.0;
        assertEquals(MemoryService.LEVEL_HIGH, MemoryService.levelOf(settings, stats));
    }

    @Test
    public void watermarkGateUsesHysteresis() {
        MemorySettings settings = new MemorySettings();
        settings.watermarkFallback = true;
        settings.availableTriggerPct = 10.0;
        settings.availableHysteresisPct = 3.0;

        WatermarkGate gate = new WatermarkGate();
        long total = 1000L;

        // 12% 可用: 正常.
        assertEquals(MemoryService.LEVEL_NORMAL, gate.level(settings, total, 120L));
        // 10% 可用: 触发.
        assertEquals(MemoryService.LEVEL_MODERATE, gate.level(settings, total, 100L));
        // 回到 12%: 还在迟滞区间内, 不能立刻停.
        assertEquals(MemoryService.LEVEL_MODERATE, gate.level(settings, total, 120L));
        // 13% 可用: 退出.
        assertEquals(MemoryService.LEVEL_NORMAL, gate.level(settings, total, 130L));
        // 5% 可用: 高压.
        assertEquals(MemoryService.LEVEL_HIGH, gate.level(settings, total, 50L));
    }

    @Test
    public void watermarkGateCanBeDisabled() {
        MemorySettings settings = new MemorySettings();
        settings.watermarkFallback = false;

        WatermarkGate gate = new WatermarkGate();
        assertEquals(MemoryService.LEVEL_NORMAL, gate.level(settings, 1000L, 10L));
        assertFalse(gate.isActive());
    }

    @Test
    public void reaperOnlyAcceptsContainerProcesses() {
        assertTrue(ProcessReaper.isReapableProcess("com.tencent.mm:appbrand0"));
        assertTrue(ProcessReaper.isReapableProcess("com.tencent.mm:xweb"));
        // 游戏子进程默认不动, 避免丢未保存进度.
        assertFalse(ProcessReaper.isReapableProcess("com.example:gameservice"));
        assertFalse(ProcessReaper.isReapableProcess("com.tencent.mm:push"));
        assertFalse(ProcessReaper.isReapableProcess("com.tencent.mm:MSF"));
        assertFalse(ProcessReaper.isReapableProcess("com.tencent.mm"));
        assertFalse(ProcessReaper.isReapableProcess("com.example:daemon"));
    }

    @Test
    public void newSettingsHaveSafeDefaults() {
        MemorySettings settings = new MemorySettings();
        assertTrue(settings.watermarkFallback);
        assertEquals(10.0, settings.availableTriggerPct, 0.0001);
        assertEquals(3.0, settings.availableHysteresisPct, 0.0001);

        // 深度回收默认关闭，且默认只 dry-run；用户必须自己打开才会真正 kill.
        assertFalse(settings.reapFrozenChildren);
        assertFalse(settings.reapApply);
        assertTrue(settings.reapOnlyUnderPressure);
        assertEquals(30, settings.reapMinFrozenMinutes);
    }

    @Test
    public void sanitizeFixesInsaneValues() {
        MemorySettings settings = new MemorySettings();
        settings.sampleIntervalMs = 0;
        settings.cooldownMs = -5;
        settings.psiSomeThreshold = -1.0;
        settings.psiStopThreshold = 99.0;
        settings.reclaimRatio = 0.0;
        settings.maxAppsPerRound = 0;
        settings.maxRoundsPerCycle = 0;
        settings.foregroundHighRatio = 9.99;

        settings.sanitize();

        assertTrue(settings.sampleIntervalMs >= 1000);
        assertTrue(settings.cooldownMs >= settings.sampleIntervalMs);
        assertTrue(settings.psiSomeThreshold > 0.0);
        assertTrue(settings.psiStopThreshold <= settings.psiSomeThreshold);
        assertTrue(settings.reclaimRatio > 0.0 && settings.reclaimRatio <= 1.0);
        assertTrue(settings.maxAppsPerRound >= 1);
        assertTrue(settings.maxRoundsPerCycle >= 1);
        assertTrue(settings.foregroundHighRatio >= 0.50 && settings.foregroundHighRatio <= 0.95);
    }

    @Test
    public void sanitizeKeepsSaneValues() {
        MemorySettings settings = new MemorySettings();
        MemorySettings copy = new MemorySettings();
        settings.sanitize();

        assertEquals(5000, settings.sampleIntervalMs);
        assertEquals(15000, settings.cooldownMs);
        assertEquals(5.0, settings.psiSomeThreshold, 0.0001);
        assertEquals(0.10, settings.reclaimRatio, 0.0001);
        assertEquals(4, settings.maxAppsPerRound);
        assertEquals(0.80, settings.foregroundHighRatio, 0.0001);
        assertEquals(copy.minCgroupBytes, settings.minCgroupBytes);
        assertEquals(copy.maxBytesPerApp, settings.maxBytesPerApp);
    }
}

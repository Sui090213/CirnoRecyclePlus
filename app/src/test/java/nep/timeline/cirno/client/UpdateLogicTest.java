package nep.timeline.cirno.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 应用内更新检查里「版本比较」和「进程名规范化」的纯逻辑测试.
 *
 * <p>这两个函数不碰 Android API, 可以在 JVM 单元测试里直接跑.
 */
public class UpdateLogicTest {
    @Test
    public void compareVersionNamesHandlesVPrefixAndMissingParts() {
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("v1.2.0", "1.2.0") == 0);
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("1.2.0", "1.1.9") > 0);
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("1.10", "1.9") > 0);
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("2.0", "1.9.9") > 0);
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("1.1.0", "1.2.0") < 0);
        assertEquals(0, UpdateChecker.INSTANCE.compareVersionNames("1.2.0", "1.2.0.0"));
    }

    @Test
    public void compareVersionNamesIgnoresNonNumericSuffix() {
        assertEquals(0, UpdateChecker.INSTANCE.compareVersionNames("1.2.0-beta", "1.2.0"));
        assertTrue(UpdateChecker.INSTANCE.compareVersionNames("1.2.1-beta", "1.2.0") > 0);
    }

    @Test
    public void normalizeKeepsPrivateProcessUnderPackage() {
        assertEquals("com.example.app:push", ProcessCatalog.INSTANCE.normalize("com.example.app", ":push"));
        assertEquals("com.example.app:push", ProcessCatalog.INSTANCE.normalize("com.example.app", " com.example.app:push "));
        assertEquals("com.example.app", ProcessCatalog.INSTANCE.normalize("com.example.app", null));
        assertEquals("com.example.app", ProcessCatalog.INSTANCE.normalize("com.example.app", ""));
        // 共享进程 (完整进程名) 不能被包名覆盖.
        assertEquals("com.other.process", ProcessCatalog.INSTANCE.normalize("com.example.app", "com.other.process"));
    }
}

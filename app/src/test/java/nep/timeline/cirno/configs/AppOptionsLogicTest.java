package nep.timeline.cirno.configs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.LinkedHashSet;

import nep.timeline.cirno.configs.settings.AppOptions;

/**
 * 纯 JVM 单元测试: 单应用冻结选项的默认值 / 进程规则 / JSON 兼容性.
 *
 * <p>这个类不能引用任何 Android 类, 否则单元测试会跑在 JVM 上直接报 "not mocked".
 */
public class AppOptionsLogicTest {
    @Test
    public void defaultsAreSafe() {
        AppOptions options = new AppOptions();

        assertTrue("默认参与冻结", options.enabled);
        assertTrue("默认播放音频时跳过冻结", options.keepAudio);
        assertTrue("默认允许内存回收", options.allowReclaim);
        assertTrue("默认收到推送时解冻", options.wakeOnPush);
        assertFalse("默认不保留网络", options.keepNetwork);
        assertFalse("默认不保留闹钟", options.keepAlarms);
        assertEquals(AppOptions.PROCESS_MODE_ALL, options.processMode);
    }

    @Test
    public void sanitizeFixesInvalidProcessMode() {
        AppOptions options = new AppOptions();

        options.processMode = 99;
        options.sanitize();
        assertEquals(AppOptions.PROCESS_MODE_ALL, options.processMode);

        options.processMode = -1;
        options.sanitize();
        assertEquals(AppOptions.PROCESS_MODE_ALL, options.processMode);

        options.processMode = AppOptions.PROCESS_MODE_ONLY;
        options.sanitize();
        assertEquals("合法值不能被改掉", AppOptions.PROCESS_MODE_ONLY, options.processMode);

        options.processMode = AppOptions.PROCESS_MODE_EXCEPT;
        options.sanitize();
        assertEquals(AppOptions.PROCESS_MODE_EXCEPT, options.processMode);
    }

    @Test
    public void selectedIsNeverNull() {
        AppOptions options = new AppOptions();
        options.selectedProcesses = null;

        assertNotNull(options.selected());
        assertTrue(options.selected().isEmpty());

        // 通过 selected() 写入之后, 内部字段也应该被补上, 后续序列化不会丢.
        options.selected().add("com.example:push");
        assertNotNull(options.selectedProcesses);
        assertEquals(1, options.selectedProcesses.size());
    }

    @Test
    public void processModeAllFreezesEverything() {
        AppOptions options = new AppOptions();
        options.processMode = AppOptions.PROCESS_MODE_ALL;
        options.selected().add("com.example:push");

        assertTrue(options.shouldFreezeProcess("com.example"));
        assertTrue(options.shouldFreezeProcess("com.example:push"));
        assertTrue("进程名未知时保持旧行为(全部冻结)", options.shouldFreezeProcess(null));
    }

    @Test
    public void processModeOnlyFreezesSelected() {
        AppOptions options = new AppOptions();
        options.processMode = AppOptions.PROCESS_MODE_ONLY;
        options.selected().add("com.example:push");
        options.selected().add("com.example:remote");

        assertTrue(options.shouldFreezeProcess("com.example:push"));
        assertTrue(options.shouldFreezeProcess("com.example:remote"));
        assertFalse(options.shouldFreezeProcess("com.example"));
        assertFalse(options.shouldFreezeProcess("com.example:other"));
        // 「只冻结选中」模式下进程名未知 = 不在选中名单里, 不能冻.
        assertFalse(options.shouldFreezeProcess(null));
    }

    @Test
    public void processModeExceptKeepsSelectedRunning() {
        AppOptions options = new AppOptions();
        options.processMode = AppOptions.PROCESS_MODE_EXCEPT;
        options.selected().add("com.example");

        assertFalse("选中的主进程不冻结", options.shouldFreezeProcess("com.example"));
        assertTrue("其它进程照常冻结", options.shouldFreezeProcess("com.example:push"));
        assertTrue("没选中的进程也要冻结", options.shouldFreezeProcess("com.example:other"));
    }

    @Test
    public void gsonKeepsDefaultsForMissingFields() {
        Gson gson = new Gson();

        // 旧版本配置文件里没有 appOptions 字段时, 反序列化出来的对象必须还是安全的默认值.
        AppOptions options = gson.fromJson("{}", AppOptions.class);
        assertNotNull(options);
        assertTrue(options.enabled);
        assertTrue(options.keepAudio);
        assertTrue("旧配置缺省时默认推送唤醒", options.wakeOnPush);
        assertNotNull(options.selected());

        // 有人手动把 selectedProcesses 写成 null 时也不能 NPE.
        AppOptions nullSet = gson.fromJson("{\"selectedProcesses\":null}", AppOptions.class);
        assertNotNull(nullSet);
        assertNotNull(nullSet.selected());
        assertTrue(nullSet.selected().isEmpty());
    }

    @Test
    public void gsonRoundTripKeepsProcessRule() {
        Gson gson = new Gson();

        AppOptions options = new AppOptions();
        options.enabled = false;
        options.keepAudio = false;
        options.keepNetwork = true;
        options.keepAlarms = true;
        options.allowReclaim = false;
        options.wakeOnPush = false;
        options.processMode = AppOptions.PROCESS_MODE_ONLY;
        options.selectedProcesses = new LinkedHashSet<>();
        options.selectedProcesses.add("com.example:push");

        String json = gson.toJson(options);
        AppOptions parsed = gson.fromJson(json, AppOptions.class);

        assertNotNull(parsed);
        assertFalse(parsed.enabled);
        assertFalse(parsed.keepAudio);
        assertTrue(parsed.keepNetwork);
        assertTrue(parsed.keepAlarms);
        assertFalse(parsed.allowReclaim);
        assertFalse(parsed.wakeOnPush);
        assertEquals(AppOptions.PROCESS_MODE_ONLY, parsed.processMode);
        assertTrue(parsed.shouldFreezeProcess("com.example:push"));
        assertFalse(parsed.shouldFreezeProcess("com.example"));
    }
}

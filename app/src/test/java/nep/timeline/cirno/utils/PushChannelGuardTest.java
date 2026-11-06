package nep.timeline.cirno.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 推送通道过滤的纯字符串测试. */
public class PushChannelGuardTest {
    @Test
    public void recognizesPushProcesses() {
        assertTrue(PushChannelGuard.isPushProcess("com.tencent.mm:push"));
        assertTrue(PushChannelGuard.isPushProcess("com.tencent.mm:PushService"));
        assertTrue(PushChannelGuard.isPushProcess("com.tencent.mobileqq:MSF"));
        assertTrue(PushChannelGuard.isPushProcess("com.example:mipush"));
        assertTrue(PushChannelGuard.isPushProcess("com.example:pushservice"));
    }

    @Test
    public void doesNotTreatContainersAsPush() {
        assertFalse(PushChannelGuard.isPushProcess("com.tencent.mm:appbrand0"));
        assertFalse(PushChannelGuard.isPushProcess("com.tencent.mm:xweb"));
        assertFalse(PushChannelGuard.isPushProcess("com.example:gameservice"));
        assertFalse(PushChannelGuard.isPushProcess("com.example:daemon"));
        assertFalse(PushChannelGuard.isPushProcess("com.example"));
    }

    @Test
    public void recognizesDedicatedPushPackages() {
        assertTrue(PushChannelGuard.isPushPackage("com.xiaomi.xmsf"));
        assertTrue(PushChannelGuard.isPushPackage("com.xiaomi.xmsfkeeper"));
        assertTrue(PushChannelGuard.isPushPackage("com.google.android.gms"));
        assertFalse(PushChannelGuard.isPushPackage("com.tencent.mm"));
        assertFalse(PushChannelGuard.isPushPackage(null));
    }
}

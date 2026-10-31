package nep.timeline.cirno.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 推送 action 识别测试 (纯字符串逻辑, 不依赖 Android 运行时).
 */
public class PushUtilsTest {
    @Test
    public void recognizesFcmActions() {
        assertTrue(PushUtils.isPushAction("com.google.android.c2dm.intent.RECEIVE"));
        assertTrue(PushUtils.isPushAction("com.example.android.c2dm.intent.RECEIVE"));
        assertTrue(PushUtils.isPushAction("com.google.android.c2dm.intent.REGISTRATION"));
    }

    @Test
    public void recognizesVendorAndUnifiedPush() {
        assertTrue(PushUtils.isPushAction("org.unifiedpush.android.connector.MESSAGE"));
        assertTrue(PushUtils.isPushAction("com.meizu.flyme.push.intent.MESSAGE"));
        assertTrue(PushUtils.isPushAction("com.huawei.android.push.intent.RECEIVE"));
        assertTrue(PushUtils.isPushAction("com.xiaomi.mipush.RECEIVE_MESSAGE"));
        assertTrue(PushUtils.isPushAction("com.vivo.pushclient.action.RECEIVE"));
        assertTrue(PushUtils.isPushAction("com.oppo.push.intent.MESSAGE"));
    }

    @Test
    public void ignoresNormalBroadcasts() {
        assertFalse(PushUtils.isPushAction(null));
        assertFalse(PushUtils.isPushAction(""));
        assertFalse(PushUtils.isPushAction("android.intent.action.BOOT_COMPLETED"));
        assertFalse(PushUtils.isPushAction("com.example.NORMAL_BROADCAST"));
    }
}

package nep.timeline.cirno;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 回归测试: {@link CommonConstants} 的静态初始化不能再因为 Set.of 重复元素而崩.
 *
 * <p>v1.2.1 把 {@code app.revanced.android.gms} 在 Set.of(...) 里写了两遍,
 * Set.of 遇到重复元素会抛 IllegalArgumentException, 整个类初始化失败, App 一打开
 * 应用列表就闪退. 这里直接触发类加载, 并检查 Google 服务集合.
 */
public class CommonConstantsTest {
    @Test
    public void classInitializesWithoutDuplicateSetCrash() {
        assertTrue(CommonConstants.isWhitelistApps("com.google.android.gms"));
        assertTrue(CommonConstants.isWhitelistApps("com.google.android.gsf"));
        assertTrue(CommonConstants.isWhitelistApps("app.revanced.android.gms"));
        assertFalse(CommonConstants.isWhitelistApps("com.tencent.mm"));
        assertFalse("null 包名不能触发 NPE", CommonConstants.isWhitelistApps(null));
    }

    @Test
    public void googleFamilyIsExposedSeparately() {
        assertTrue(CommonConstants.isGoogleFamily("com.google.android.gms"));
        assertTrue(CommonConstants.isGoogleFamily("com.android.vending"));
        assertTrue(CommonConstants.isGoogleFamily("app.revanced.android.gms"));
        assertFalse(CommonConstants.isGoogleFamily("com.tencent.mm"));
        assertFalse("null 包名不能触发 NPE", CommonConstants.isGoogleFamily(null));
    }
}

package nep.timeline.cirno.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 网络代理 / 防火墙保护名单的纯字符串测试. */
public class NetworkProxyGuardTest {
    @Test
    public void recognizesCommonProxyAndFirewallPackages() {
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.github.kr328.clash"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.github.metacubex.clash.meta"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.follow.clash"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("io.nekohasekai.sagernet"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("io.nekohasekai.sfa"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.v2ray.ang"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.github.shadowsocks"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("com.wireguard.android"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("dev.ukanth.ufirewall"));
        assertTrue(NetworkProxyGuard.isNetworkProxy("eu.faircode.netguard"));
    }

    @Test
    public void doesNotProtectOrdinaryApps() {
        assertFalse(NetworkProxyGuard.isNetworkProxy("com.tencent.mm"));
        assertFalse(NetworkProxyGuard.isNetworkProxy("com.example.app"));
        assertFalse(NetworkProxyGuard.isNetworkProxy(null));
    }
}

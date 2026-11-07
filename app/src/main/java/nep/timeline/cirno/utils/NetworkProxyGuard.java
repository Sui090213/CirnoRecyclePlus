package nep.timeline.cirno.utils;

/**
 * 网络代理 / 防火墙类应用保护.
 *
 * <p>这类应用通常通过 VpnService、TProxy + iptables/nftables 或本地防火墙规则接管整机流量.
 * 它们被 cgroup 冻住后, 规则仍然存在, 但用户态监听 / 转发进程不再运行, 所有流量都会
 * 进入没有 listener 的端口, 表现就是「系统网络不可用」.
 *
 * <p>VpnStateHook 只能覆盖标准 VpnService, 覆盖不到 root + TProxy 的代理应用,
 * 所以这里对常见代理 / 防火墙包做兜底保护.
 */
public final class NetworkProxyGuard {
    private static final String[] PACKAGE_PREFIXES = {
            "com.github.kr328.clash",
            "com.github.metacubex.clash",
            "com.follow.clash",
            "io.nekohasekai.",
            "dev.nekohasekai.",
            "com.v2ray.",
            "com.github.shadowsocks",
            "com.wireguard.",
            "com.tailscale.",
            "com.adguard.",
            "dev.ukanth.ufirewall",
            "eu.faircode.netguard",
            "com.getsurfboard",
            "com.surfboardapp.",
    };

    private NetworkProxyGuard() {
    }

    public static boolean isNetworkProxy(String packageName) {
        if (packageName == null || packageName.isEmpty())
            return false;
        for (String prefix : PACKAGE_PREFIXES) {
            if (packageName.startsWith(prefix))
                return true;
        }
        return false;
    }
}

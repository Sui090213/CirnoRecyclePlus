package nep.timeline.cirno.utils;

import java.util.Locale;

/**
 * 推送通道保护过滤.
 *
 * <p>内存回收只应该动「普通后台进程」. 厂商推送 / 消息长连接进程如果被 compact 或 kill,
 * 轻则通知延迟, 重则要等应用下次打开才重新建连. 这里集中判定哪些包 / 进程属于推送通道,
 * 供 {@code MemoryService} 和 {@code ProcessReaper} 共用.
 *
 * <p>只做包名 / 进程名字符串判断, 不依赖 Android 运行时, 可以直接单元测试.
 */
public final class PushChannelGuard {
    /** 专门承担系统级推送的包; 这些包整体不参与内存回收. */
    private static final String[] PUSH_PACKAGES = {
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.xiaomi.xmsf",
            "com.xiaomi.xmsfkeeper",
            "com.xiaomi.mipush",
            "com.huawei.hms",
            "com.huawei.hwid",
            "com.vivo.push",
            "com.vivo.pushservice",
            "com.oppo.push",
            "com.coloros.mcs",
            "com.oplus.push",
            "com.meizu.cloud.pushsdk",
            "com.igexin.push",
            "com.tencent.android.tpush",
            "com.baidu.push",
            "com.umeng.message",
    };

    private PushChannelGuard() {
    }

    /** 是否是专门承担推送的包 (整体跳过内存回收). */
    public static boolean isPushPackage(String packageName) {
        if (packageName == null || packageName.isEmpty())
            return false;
        for (String pkg : PUSH_PACKAGES) {
            if (pkg.equals(packageName))
                return true;
        }
        return false;
    }

    /**
     * 是否是消息推送通道进程.
     *
     * <p>只认真正的长连接进程: :push / :pushservice / :MSF / :mipush 等.
     * 不能把 :appbrand / :xweb / :sandbox 也算进来, 它们是容器进程, 可以回收.
     */
    public static boolean isPushProcess(String processName) {
        if (processName == null || processName.isEmpty())
            return false;

        int index = processName.lastIndexOf(':');
        if (index < 0 || index == processName.length() - 1)
            return false;

        String suffix = processName.substring(index + 1).toLowerCase(Locale.ROOT);
        if (suffix.startsWith("push"))
            return true;
        if (suffix.equals("msf") || suffix.startsWith("msf"))
            return true;
        if (suffix.equals("mipush") || suffix.startsWith("mipush"))
            return true;
        if (suffix.equals("xmsf") || suffix.startsWith("xmsf"))
            return true;
        if (suffix.equals("mcs") || suffix.startsWith("mcs"))
            return true;
        if (suffix.equals("hms") || suffix.startsWith("hms"))
            return true;
        if (suffix.contains("pushservice"))
            return true;
        return false;
    }
}

package nep.timeline.cirno.utils;

import android.content.ComponentName;
import android.content.Intent;

/**
 * 推送相关 action 的识别.
 *
 * <p>为什么需要单独处理: 后台应用被冻结后，FCM 广播到达时如果系统直接投递，广播会停在
 * 冻结进程的 Binder 队列里，表现为「推送收不到 / 亮屏或打开应用后才收到」。所以收到推送
 * 广播时要先把目标应用解冻，并给它一个足够长的「投递窗口」拉取正文 / 重建连接，再重新冻结。
 *
 * <p>除了 Google FCM，这里也覆盖 UnifiedPush、魅族 / 华为 / 小米 / vivo / OPPO 厂商推送，
 * 这些通道的 action 各不相同，但都走「广播唤醒应用」同一条路。
 */
public final class PushUtils {
    /**
     * 推送唤醒后的保活窗口 (毫秒).
     *
     * <p>取 15 秒: 足够应用处理广播、启动 service、拉取消息正文；又不会让后台应用长期不冻结.
     */
    public static final long PUSH_HOLD_MS = 15_000L;

    private static final String[] PUSH_ACTIONS = {
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.android.c2dm.intent.REGISTRATION",
            "com.google.android.intent.action.GCM_RECONNECT",
            "com.google.android.gms.gcm.ACTION_TASK_READY",
            "org.unifiedpush.android.connector.MESSAGE",
            "com.meizu.flyme.push.intent.MESSAGE",
            "com.huawei.android.push.intent.RECEIVE",
            "com.huawei.intent.action.PUSH",
            "com.xiaomi.mipush.RECEIVE_MESSAGE",
            "com.xiaomi.mipush.MESSAGE_ARRIVED",
            "com.vivo.pushclient.action.RECEIVE",
            "com.oppo.push.intent.MESSAGE",
            "com.igexin.sdk.action.pushmanager",
    };

    private PushUtils() {
    }

    /** 是否是推送类 action. */
    public static boolean isPushAction(String action) {
        if (action == null || action.isEmpty())
            return false;

        // FCM / 旧版 GCM 的 action 以 ".android.c2dm.intent.RECEIVE" 结尾,
        // 不同应用前缀不一样 (com.google.android.c2dm / com.example.android.c2dm 等).
        if (action.endsWith(".android.c2dm.intent.RECEIVE"))
            return true;

        for (String pushAction : PUSH_ACTIONS) {
            if (pushAction.equals(action))
                return true;
        }

        return action.startsWith("com.google.android.c2dm.intent.")
                || action.startsWith("com.google.firebase.messaging.");
    }

    /** 取广播 / 服务的目标包名 (显式组件优先, 其次 setPackage). */
    public static String packageOf(Intent intent) {
        if (intent == null)
            return null;
        ComponentName component = intent.getComponent();
        if (component != null)
            return component.getPackageName();
        return intent.getPackage();
    }
}

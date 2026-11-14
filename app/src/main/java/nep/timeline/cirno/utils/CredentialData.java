package nep.timeline.cirno.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.entity.AppRecord;

/**
 * 凭证管理器 / 通行密钥 (CredentialManager) 活跃会话登记表.
 *
 * <p>为什么要登记: CredentialManager 请求期间 provider 进程 (密码管理器 / 通行密钥服务)
 * 需要持续响应 Binder 调用, 被冻结会表现为「选择通行密钥时界面一直转圈」, 所以会话期间
 * {@link AppRecord#isProtected()} 要返回 true.
 *
 * <p>为什么 key 用对象身份: provider 会话 (ProviderSession) 没有稳定的业务 id, 请求结束
 * (RequestSession.finishSession) 时能拿到的只有当初返回的那个对象本身. 这里用
 * {@link System#identityHashCode(Object)} 作为 key, 并且**不持有** ProviderSession 引用:
 * 这样不会把一个已经结束的 provider 会话对象一直吊在内存里, 结束 hook 负责把登记删掉.
 *
 * <p>已知取舍: identityHashCode 理论上可能冲突 (不同对象得到同一个值). provider 会话数量级
 * 很小 (一次请求最多几个到几十个 provider), 冲突概率可以忽略; 一旦冲突只会导致豁免提前失效,
 * 不会影响 system_server 正确性.
 *
 * <p>会话带最大寿命: finishSession 没挂上 / 异常销毁时, 没有寿命的登记会永久豁免; 超过
 * {@link #MAX_SESSION_AGE_MS} 的会话在查询时被丢弃.
 */
public class CredentialData {
    /** 单个通行密钥会话的最长豁免时间: 正常会话是秒级, 10 分钟只用于兜底清理泄漏. */
    private static final long MAX_SESSION_AGE_MS = 10L * 60_000L;

    private static final class Session {
        final AppRecord appRecord;
        final long createdAt;

        Session(AppRecord appRecord) {
            this.appRecord = appRecord;
            this.createdAt = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - createdAt > MAX_SESSION_AGE_MS;
        }
    }

    private static final Map<Integer, Session> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private CredentialData() {
    }

    private static Integer makeSessionKey(Object providerSession) {
        return System.identityHashCode(providerSession);
    }

    /**
     * 登记一个 provider 会话.
     *
     * @return 是否新增 (重复登记同一个 ProviderSession 时返回 false, 表示只是刷新).
     */
    public static boolean putSession(Object providerSession, AppRecord appRecord) {
        if (providerSession == null || appRecord == null)
            return false;
        return ACTIVE_SESSIONS.put(makeSessionKey(providerSession), new Session(appRecord)) == null;
    }

    /**
     * 注销一个 provider 会话.
     *
     * @return 该会话登记的 AppRecord; 没有登记时返回 null.
     */
    public static AppRecord removeSession(Object providerSession) {
        if (providerSession == null)
            return null;
        Session session = ACTIVE_SESSIONS.remove(makeSessionKey(providerSession));
        return session == null ? null : session.appRecord;
    }

    /** 该应用当前是否有活跃的凭证管理器会话. */
    public static boolean hasActiveSession(AppRecord appRecord) {
        if (appRecord == null)
            return false;

        for (Map.Entry<Integer, Session> entry : ACTIVE_SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.expired()) {
                ACTIVE_SESSIONS.remove(entry.getKey(), session);
                continue;
            }
            if (appRecord.equals(session.appRecord))
                return true;
        }
        return false;
    }

    /** 当前登记的会话总数. */
    public static int getSessionCount() {
        return ACTIVE_SESSIONS.size();
    }
}

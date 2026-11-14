package nep.timeline.cirno.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.entity.AppRecord;

/**
 * 自动填充 (Autofill) 活跃会话登记表.
 *
 * <p>为什么要登记: 自动填充 provider (密码管理器 / 系统自动填充) 一旦被冻结, system_server
 * 里的 startSession / 请求填充会一直等 Binder 回包, 用户看到的就是「点输入框后密码候选不弹,
 * 甚至输入法界面卡住」. 冻结侧在 {@link AppRecord#isProtected()} 里查询这里, 有活跃会话就跳过冻结.
 *
 * <p>线程模型: 登记 / 移除发生在 system_server 各个 Binder 线程, 查询发生在 Freezer 线程和
 * 2 秒一次的配置对齐 (FreezerService.reconcile) 里, 所以用 ConcurrentHashMap, 不用 synchronized
 * 大锁, 避免冻结线程被自动填充的 IPC 卡住.
 *
 * <p>key 用 "userId:sessionId": sessionId 是 system_server 分配的 int, 只在同一个 userId 的
 * AutofillManagerServiceImpl 内唯一, 加上 userId 才能跨用户唯一.
 *
 * <p>会话带最大寿命: 如果 remove 回调因为 ROM 方法改名 / 服务异常销毁而没有到达, 没有寿命的
 * 登记会让 provider 永久免冻结. 超过 {@link #MAX_SESSION_AGE_MS} 的会话在查询时被丢弃.
 */
public class AutofillData {
    /** 单个自动填充会话的最长豁免时间: 正常会话是秒级, 10 分钟只用于兜底清理泄漏. */
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

    private static final Map<String, Session> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private AutofillData() {
    }

    private static String makeSessionKey(int userId, int sessionId) {
        return userId + ":" + sessionId;
    }

    /**
     * 登记一个活跃会话.
     *
     * @return 是否新增 (同一 key 重复上报时返回 false, 表示只是刷新).
     */
    public static boolean putSession(int userId, int sessionId, AppRecord appRecord) {
        if (appRecord == null)
            return false;
        return ACTIVE_SESSIONS.put(makeSessionKey(userId, sessionId), new Session(appRecord)) == null;
    }

    /**
     * 注销一个会话.
     *
     * @return 该会话登记的 AppRecord; 没有登记时返回 null.
     */
    public static AppRecord removeSession(int userId, int sessionId) {
        Session session = ACTIVE_SESSIONS.remove(makeSessionKey(userId, sessionId));
        return session == null ? null : session.appRecord;
    }

    /**
     * 该应用当前是否有活跃的自动填充会话.
     *
     * <p>比较用 {@link AppRecord#equals(Object)} (packageName + userId), 不要求是同一个对象实例:
     * 应用重装 / 用户切换后 AppService 可能给出新的 AppRecord, 但业务上仍是同一个应用.
     */
    public static boolean hasActiveSession(AppRecord appRecord) {
        return getActiveSessionCount(appRecord) > 0;
    }

    /** 该应用当前的活跃会话数 (日志 / 判断「移除后是否还有别的会话」用). */
    public static int getActiveSessionCount(AppRecord appRecord) {
        if (appRecord == null)
            return 0;

        int count = 0;
        for (Map.Entry<String, Session> entry : ACTIVE_SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.expired()) {
                ACTIVE_SESSIONS.remove(entry.getKey(), session);
                continue;
            }
            if (appRecord.equals(session.appRecord))
                count++;
        }
        return count;
    }

    /** 当前登记的会话总数. */
    public static int getSessionCount() {
        return ACTIVE_SESSIONS.size();
    }
}

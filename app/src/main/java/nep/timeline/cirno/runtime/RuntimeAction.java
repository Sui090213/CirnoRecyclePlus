package nep.timeline.cirno.runtime;

/**
 * App 下发给 system_server 的一次手动操作 (手动冻结/解冻某个应用或某个进程).
 *
 * <p>进程级操作只在「应用不在前台」时才允许执行: 冻结正在前台交互的应用会直接卡住屏幕.
 */
public class RuntimeAction {
    /** 冻结整个应用 (按应用的进程规则决定冻结哪些进程). */
    public static final String TYPE_FREEZE_APP = "freezeApp";
    /** 解冻整个应用. */
    public static final String TYPE_THAW_APP = "thawApp";
    /** 冻结指定 pid. */
    public static final String TYPE_FREEZE_PID = "freezePid";
    /** 解冻指定 pid. */
    public static final String TYPE_THAW_PID = "thawPid";

    public String type;
    public String packageName;
    public int userId;
    /** 进程级操作时使用; 应用级操作填 0 即可. */
    public int pid;
    /**
     * 进程启动时间 (来自 RuntimeState.json), 用于执行前校验 pid 没被系统复用给别的进程.
     * 0 或 -1 表示不校验.
     */
    public long startTime = -1L;
}

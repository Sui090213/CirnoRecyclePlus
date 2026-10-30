package nep.timeline.cirno.runtime;

import java.util.ArrayList;
import java.util.List;

/** 单个应用的运行时冻结状态 (由 system_server 生成). */
public class AppRuntimeState {
    public String packageName;
    public int userId;
    public int uid;

    /** Cirno 记录的整应用冻结标记 (至少有进程被冻结时为 true). */
    public boolean frozen;

    /** 存活进程数. */
    public int liveProcesses;

    /** 其中已冻结的进程数. */
    public int frozenProcesses;

    // 以下状态与 Cirno 的冻结判定一致, 界面可以据此解释「为什么这个应用还没被冻结」.
    public boolean visible;
    public boolean audio;
    public boolean location;
    public boolean recording;
    public boolean vpn;

    /** 应用的所有存活进程. */
    public List<ProcessRuntimeState> processes = new ArrayList<>();
}

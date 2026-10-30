package nep.timeline.cirno.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * system_server 写给 App 的运行时状态快照.
 *
 * <p>只包含「当前有存活进程」的应用, 没运行的应用不写进来, 这样文件通常只有几 KB,
 * 每 2 秒覆盖一次也不会给闪存造成负担.
 */
public class RuntimeState {
    /** 协议版本, 以后改字段时 App 可以用它判断兼容性. */
    public int version = 1;

    /** 本次开机的开机时刻, App 用它丢弃上一次开机残留的状态. */
    public long bootTimeMs;

    /** 本文件最后一次写入的时间 (epoch 毫秒). */
    public long updatedAtMs;

    /** system_server 最后执行过的操作序号, App 可以用它确认自己的请求是否被执行. */
    public long lastActionSeq;

    /** 最后一次手动操作的结果说明 (给界面显示一句提示). */
    public String lastActionMessage = "";

    /** 最后一次手动操作的执行时间 (epoch 毫秒), 0 表示本次开机还没执行过. */
    public long lastActionAtMs;

    /** 正在运行的应用状态列表. */
    public List<AppRuntimeState> apps = new ArrayList<>();
}

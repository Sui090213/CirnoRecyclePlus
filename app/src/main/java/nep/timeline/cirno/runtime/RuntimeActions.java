package nep.timeline.cirno.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * App 写入 {@code RuntimeActions.json} 的内容.
 *
 * <p>{@link #seq} 单调递增: system_server 只执行序号比「上次已执行序号」更大的那一批,
 * 这样模块软重启 (system_server 重启) 后旧文件不会被执行第二次.
 */
public class RuntimeActions {
    /** 写文件时的开机时刻, 用来丢弃上一次开机残留的操作. */
    public long bootTimeMs;

    /** 操作序号, App 用当前时间戳保证单调递增. */
    public long seq;

    /** 操作列表. */
    public List<RuntimeAction> actions = new ArrayList<>();
}

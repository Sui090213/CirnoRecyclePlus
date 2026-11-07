package nep.timeline.cirno.configs.settings;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个应用的冻结选项 (在 App 里长按应用弹出的面板里设置).
 *
 * <p>这些字段会被 Gson 序列化到 {@code /data/misc/CirnoRecycle/ApplicationSettings.json}
 * 的 {@code appOptions} 字段里 (key 是 {@code packageName#userId}).
 * 新增字段必须给合理默认值, 否则旧配置文件反序列化后是 0 / null, 行为会变得很奇怪.
 *
 * <p>白名单应用: 整应用不参与自动冻结, 但如果 {@link #processMode} 是「只冻选中 / 排除选中」,
 * 子进程仍会按规则部分冻结 (主进程永远保留), 详见 FreezerService.
 */
public class AppOptions {
    /** 自动冻结时冻结应用的全部进程. */
    public static final int PROCESS_MODE_ALL = 0;
    /** 自动冻结时只冻结 {@link #selectedProcesses} 里的进程. */
    public static final int PROCESS_MODE_ONLY = 1;
    /** 自动冻结时冻结除 {@link #selectedProcesses} 以外的进程. */
    public static final int PROCESS_MODE_EXCEPT = 2;

    /** 是否参与自动冻结. false = 这个应用永远不冻结 (仍然可以被内存回收). */
    public boolean enabled = true;

    /**
     * 后台播放音频时是否跳过冻结.
     *
     * <p>默认开启, 与 Cirno 原有行为一致: 正在放歌 / 放视频的应用冻结了会卡声音.
     * 关掉之后即使正在播放音频也会被冻结 (少数滥用音频的应用可以这样治).
     */
    public boolean keepAudio = true;

    /**
     * 冻结时是否保留网络连接 (Android 15 及以下会调用 socketDestroy, 打开后跳过).
     *
     * <p>1.4.1 起默认 true: 断网副作用太大, 而且 socketDestroy 不会移除 iptables/nftables
     * 规则, 代理/防火墙类应用被冻结后会出现「规则还在但整机网络不可用」.
     * 旧配置文件里的 false 仍然生效, 但网络代理/文件选择器现在会被 isProtected 直接挡住.
     */
    public boolean keepNetwork = true;

    /** 冻结时是否保留闹钟 (默认移除, 打开后不移除, 推送类应用可以这样保活). */
    public boolean keepAlarms = false;

    /** 是否允许内存回收模块回收这个应用的 cgroup 内存. */
    public boolean allowReclaim = true;

    /**
     * 收到推送 (FCM / 厂商推送广播) 时是否临时解冻.
     *
     * <p>默认开启: 冻结中的应用收到推送广播时先解冻 15 秒处理消息 / 拉取正文, 之后重新冻结.
     * 关掉后广播仍会投递, 但不会额外解冻, 消息可能要等打开应用才处理.
     */
    public boolean wakeOnPush = true;

    /**
     * Google 服务专用: 是否允许冻结这个应用.
     *
     * <p>GMS / GSF / Play 商店等默认受保护, 防止冻结后 FCM 推送收不到; 不使用 FCM
     * (或使用其他推送通道) 的用户可以在应用面板里显式打开. 没有打开时,
     * {@code AppRecord.isProtected()} 仍然会把这些应用当成受保护应用.
     *
     * <p>默认 false, 旧配置文件里没有这个字段时反序列化也是 false, 保持 FCM 安全行为.
     */
    public boolean allowFreezeGoogle = false;

    /** 自动冻结时冻结哪些进程: 全部 / 只冻结选中 / 排除选中. */
    public int processMode = PROCESS_MODE_ALL;

    /** 进程规则里选中的进程名 (完整进程名, 例如 com.tencent.mm:push). */
    public Set<String> selectedProcesses = new LinkedHashSet<>();

    /** 取选中进程集合, 兼容 JSON 里被写成 null 的情况. */
    public Set<String> selected() {
        if (selectedProcesses == null)
            selectedProcesses = new LinkedHashSet<>();
        return selectedProcesses;
    }

    /**
     * 按进程规则判断某个进程该不该被自动冻结.
     *
     * @param processName 完整进程名, 例如 {@code com.tencent.mm:push}; 为 null 时按「全部冻结」处理
     */
    public boolean shouldFreezeProcess(String processName) {
        if (processMode == PROCESS_MODE_ONLY)
            // 「只冻选中」语义下, 进程名未知 = 不在选中列表里, 不冻结.
            return processName != null && selected().contains(processName);
        if (processMode == PROCESS_MODE_EXCEPT)
            return processName == null || !selected().contains(processName);
        return true;
    }

    /** 校正手动改 JSON 改出来的非法值. */
    public void sanitize() {
        if (processMode != PROCESS_MODE_ONLY && processMode != PROCESS_MODE_EXCEPT)
            processMode = PROCESS_MODE_ALL;
        selected();
    }
}

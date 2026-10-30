package nep.timeline.cirno.runtime;

import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import nep.timeline.cirno.configs.checkers.AppConfigs;
import nep.timeline.cirno.configs.settings.AppOptions;
import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.entity.AppState;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.ProcessStat;
import nep.timeline.cirno.utils.RWUtils;
import nep.timeline.cirno.virtuals.ProcessRecord;

/**
 * 运行时状态服务 (运行在 system_server 里).
 *
 * <p>做两件事:
 * <ol>
 *     <li>每 2 秒把所有「有存活进程」的应用的冻结状态 / 进程列表写成
 *         {@link RuntimeFiles#STATE_FILE}, App 读它来显示「已冻结」和进程列表;</li>
 *     <li>轮询 {@link RuntimeFiles#ACTIONS_FILE}, 执行 App 下发的「手动冻结/解冻进程」
 *         请求, 并把执行结果写回状态文件.</li>
 * </ol>
 *
 * <p>为什么用文件而不是 binder / ContentProvider: 模块跑在 system_server 里, 而 App 是普通
 * 应用进程, 两者之间没有现成的通道; 文件 + root 读写最省事, 也不需要额外权限.
 * 写状态文件用「临时文件 + rename」保证 App 永远读不到半个 JSON.
 *
 * <p>性能: 状态内容没变化时只在超过 {@link #HEARTBEAT_MS} 时补写一次心跳时间戳,
 * 正常待机时几乎不产生磁盘写入.
 */
public final class RuntimeStateService {
    private static final int LOOP_INTERVAL_MS = 2000;
    private static final long HEARTBEAT_MS = 10_000L;
    /** 操作序号允许比当前时间戳大多少: 超过说明文件被手改成非法值 / long 溢出. */
    private static final long MAX_ACTION_SEQ_FUTURE_MS = 10L * 366L * 24L * 3600L * 1000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** 上一次写入的状态内容 (不含 updatedAtMs, 用于判断是否有变化). */
    private static String lastSignature;
    private static long lastWriteElapsedMs;
    private static long lastExecutedSeq;
    private static long lastActionAtMs;
    private static String lastActionMessage = "";
    private static boolean writeFailedLogged;
    /** 读取 RuntimeActions.json 失败时只提示一次 (调用方每 2 秒轮询, 不能刷屏日志). */
    private static boolean actionsReadFailedLogged;

    private RuntimeStateService() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true))
            return;

        // 先处理「本次开机之前就存在的操作文件」: 只登记序号基线, 不重放.
        // 必须在第一次 tick 之前做, 否则模块启动后 App 立刻下发的第一个真实操作会被当成基线丢掉.
        initializeActionBaseline();
        Log.i("运行时状态服务已启动 (冻结状态显示 / 手动进程冻结通道)");
        Handlers.runtime.post(RuntimeStateService::loop);
    }

    /**
     * 模块启动时读一次已有的 {@code RuntimeActions.json}.
     *
     * <p>两种情况会把它丢掉: 上一次开机残留 (bootTime 对不上), 或者序号不大于基线
     * (说明这次 system_server 软重启前已经执行过). 之后 App 再写入的新序号才会被执行.
     */
    private static void initializeActionBaseline() {
        File file = new File(RuntimeFiles.ACTIONS_FILE);
        if (!file.exists())
            return;

        String json = readActionsFile(file);
        if (json == null || json.trim().isEmpty())
            return;

        try {
            RuntimeActions actions = RuntimeFiles.gson().fromJson(json, RuntimeActions.class);
            if (actions == null)
                return;

            if (Math.abs(actions.bootTimeMs - RuntimeFiles.bootTimeMs()) > RuntimeFiles.BOOT_TIME_TOLERANCE_MS) {
                // 上一次开机留下的操作: 直接删掉, 绝不能执行.
                file.delete();
                return;
            }

            // 序号被手改成 0 / 负数 / 天文数字时, 直接丢弃文件并重建基线, 避免手动操作通道永久失效.
            if (!isSeqValid(actions.seq)) {
                file.delete();
                return;
            }

            if (actions.seq > lastExecutedSeq)
                lastExecutedSeq = actions.seq;
        } catch (RuntimeException ignored) {
            // 文件坏了就当成没有; App 下一次写入会覆盖它.
        }
    }

    /** 操作序号是否合法 (正数且不在太远的未来; 用于识别手改文件和 long 溢出). */
    private static boolean isSeqValid(long seq) {
        return seq > 0L && seq <= System.currentTimeMillis() + MAX_ACTION_SEQ_FUTURE_MS;
    }

    private static void loop() {
        try {
            tick();
        } catch (Throwable throwable) {
            Log.e("运行时状态服务异常", throwable);
        }
        Handlers.runtime.postDelayed(RuntimeStateService::loop, LOOP_INTERVAL_MS);
    }

    private static void tick() {
        boolean actionExecuted = processPendingActions();

        // 先把「应用选项 / 进程规则改动」对齐到实际 cgroup 状态 (每 2 秒一次):
        // 关掉自动冻结后必须解冻、规则排除的进程必须解冻、全部冻结但还没上报的要补上报.
        reconcileFreezeStates();

        RuntimeState state = buildState();
        long now = SystemClock.elapsedRealtime();

        // 先算「去掉时间戳后的内容」作为签名: 只要状态没变, 就不必每 2 秒写一次文件.
        state.updatedAtMs = 0L;
        String signature = RuntimeFiles.gson().toJson(state);

        boolean changed = !signature.equals(lastSignature);
        boolean heartbeat = now - lastWriteElapsedMs >= HEARTBEAT_MS;
        if (!changed && !heartbeat && !actionExecuted)
            return;

        state.updatedAtMs = System.currentTimeMillis();
        if (writeState(state)) {
            lastSignature = signature;
            lastWriteElapsedMs = now;
            writeFailedLogged = false;
        } else if (!writeFailedLogged) {
            writeFailedLogged = true;
            Log.w("写入运行时状态失败, 界面可能无法显示冻结状态: " + RuntimeFiles.STATE_FILE);
        }
    }

    /** 按当前配置对齐每个应用的冻结状态 (见 FreezerService.reconcile). */
    private static void reconcileFreezeStates() {
        List<AppRecord> records = AppService.getAll();
        if (records == null)
            return;

        for (AppRecord appRecord : records) {
            if (appRecord == null)
                continue;
            try {
                FreezerService.reconcile(appRecord);
            } catch (Throwable throwable) {
                Log.w("对齐冻结状态失败: " + throwable);
            }
        }
    }

    private static RuntimeState buildState() {
        RuntimeState state = new RuntimeState();
        state.version = 1;
        state.bootTimeMs = RuntimeFiles.bootTimeMs();
        state.lastActionSeq = lastExecutedSeq;
        state.lastActionMessage = lastActionMessage;
        state.lastActionAtMs = lastActionAtMs;

        List<AppRecord> records = AppService.getAll();
        if (records == null)
            return state;

        for (AppRecord appRecord : records) {
            if (appRecord == null || appRecord.getPackageName() == null)
                continue;

            try {
                AppRuntimeState app = buildAppState(appRecord);
                if (app != null)
                    state.apps.add(app);
            } catch (Throwable throwable) {
                // 单个应用状态生成失败 (反射字段被回收等) 不能影响整个列表.
                Log.w("生成应用运行状态失败: " + throwable);
            }
        }

        state.apps.sort((left, right) -> {
            int result = left.packageName.compareTo(right.packageName);
            return result != 0 ? result : Integer.compare(left.userId, right.userId);
        });
        return state;
    }

    private static AppRuntimeState buildAppState(AppRecord appRecord) {
        List<ProcessRuntimeState> processes = new ArrayList<>();
        int live = 0;
        int frozenCount = 0;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;

            ProcessRuntimeState process = new ProcessRuntimeState();
            process.pid = processRecord.getPid();
            process.name = processRecord.getProcessName();
            process.frozen = processRecord.isFrozen();
            // 用 ProcessRecord 缓存的启动时间, 避免每 2 秒对每个进程都读一次 /proc.
            process.startTime = processRecord.getStartTime();
            processes.add(process);
            live++;
            if (process.frozen)
                frozenCount++;
        }

        // 没有存活进程的应用不写进状态文件: 界面显示「未运行」即可, 也能让文件保持很小.
        if (live == 0)
            return null;

        AppRuntimeState app = new AppRuntimeState();
        app.packageName = appRecord.getPackageName();
        app.userId = appRecord.getUserId();
        app.uid = appRecord.getUid();
        app.frozen = appRecord.isFrozen();
        app.liveProcesses = live;
        app.frozenProcesses = frozenCount;
        app.processes = processes;

        AppState appState = appRecord.getAppState();
        if (appState != null) {
            app.visible = appState.isVisible();
            app.audio = appState.isAudio();
            app.location = appState.isLocation();
            app.recording = appState.isRecording();
            app.vpn = appState.isVpn();
        }
        return app;
    }

    private static boolean writeState(RuntimeState state) {
        File target = new File(RuntimeFiles.STATE_FILE);
        File dir = target.getParentFile();
        try {
            if (dir != null && !dir.exists() && !dir.mkdirs())
                return false;

            String json = RuntimeFiles.gson().toJson(state);
            File temp = new File(dir, RuntimeFiles.STATE_FILE_NAME + ".tmp");
            RWUtils.writeStringToFile(temp, json);

            // rename 在同一分区内是原子的: 读方要么看到旧文件, 要么看到完整的新文件.
            if (temp.renameTo(target))
                return true;

            if (target.exists() && !target.delete())
                return false;
            if (temp.renameTo(target))
                return true;

            temp.delete();
            return false;
        } catch (IOException | RuntimeException e) {
            Log.w("写入运行时状态异常: " + e);
            return false;
        }
    }

    /**
     * 读取 App 写的操作文件.
     *
     * <p>App 用 root 写这个文件, 属主是 root; system_server 以 system 用户读, 所以 App 侧写完
     * 会 chmod 0644. 万一还是读不到 (某些 root 方案权限严格), 只提示一次, 不每 2 秒刷日志.
     */
    private static String readActionsFile(File file) {
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            actionsReadFailedLogged = false;
            return json;
        } catch (IOException | RuntimeException e) {
            if (!actionsReadFailedLogged) {
                actionsReadFailedLogged = true;
                Log.w("读取手动操作文件失败, 手动冻结可能不可用: " + e);
            }
            return null;
        }
    }

    /**
     * 轮询并执行 App 下发的操作.
     *
     * @return 本次是否执行了操作 (执行后需要立即写一版状态文件, 让界面尽快看到结果)
     */
    private static boolean processPendingActions() {
        File file = new File(RuntimeFiles.ACTIONS_FILE);
        if (!file.exists())
            return false;

        String json = readActionsFile(file);
        if (json == null || json.trim().isEmpty())
            return false;

        RuntimeActions actions;
        try {
            actions = RuntimeFiles.gson().fromJson(json, RuntimeActions.class);
        } catch (RuntimeException e) {
            return false;
        }
        if (actions == null)
            return false;

        // 上一次开机残留的操作: 直接删掉, 绝对不能执行.
        if (Math.abs(actions.bootTimeMs - RuntimeFiles.bootTimeMs()) > RuntimeFiles.BOOT_TIME_TOLERANCE_MS) {
            file.delete();
            return false;
        }

        // 序号非法 (手改文件 / long 溢出): 删掉并重建基线, 否则之后所有合法操作都会被判成「旧序号」.
        if (!isSeqValid(actions.seq)) {
            lastExecutedSeq = 0L;
            Log.w("手动操作文件序号非法 (seq=" + actions.seq + "), 已删除并重置基线");
            file.delete();
            return false;
        }

        // 启动基线已经在 initializeActionBaseline() 里登记过, 这里只执行更新的批次.
        if (actions.seq <= lastExecutedSeq)
            return false;

        lastExecutedSeq = actions.seq;
        boolean executed = false;
        StringBuilder messages = new StringBuilder();

        if (actions.actions != null) {
            for (RuntimeAction action : actions.actions) {
                if (action == null || action.type == null)
                    continue;

                String message;
                try {
                    message = execute(action);
                } catch (Throwable throwable) {
                    message = "执行失败: " + throwable.getClass().getSimpleName();
                    Log.w("手动冻结操作异常: " + throwable);
                }
                executed = true;
                if (message != null && !message.isEmpty()) {
                    if (messages.length() > 0)
                        messages.append("; ");
                    messages.append(message);
                }
            }
        }

        lastActionAtMs = System.currentTimeMillis();
        lastActionMessage = messages.length() == 0 ? "没有可执行的操作" : messages.toString();
        Log.i("执行 App 手动操作: " + lastActionMessage);
        return executed;
    }

    private static String execute(RuntimeAction action) {
        AppRecord appRecord = AppService.get(action.packageName, action.userId);
        if (appRecord == null)
            return "找不到应用 " + action.packageName;

        switch (action.type) {
            case RuntimeAction.TYPE_FREEZE_APP:
                return freezeApp(appRecord);
            case RuntimeAction.TYPE_THAW_APP: {
                boolean changed = FreezerService.thaw(appRecord);
                return changed
                        ? "已解冻 " + appRecord.getPackageNameWithUser()
                        : appRecord.getPackageNameWithUser() + " 当前没有冻结";
            }
            case RuntimeAction.TYPE_FREEZE_PID:
                return freezePid(appRecord, action);
            case RuntimeAction.TYPE_THAW_PID:
                return thawPid(appRecord, action);
            default:
                return "未知操作类型 " + action.type;
        }
    }

    private static String freezeApp(AppRecord appRecord) {
        String name = appRecord.getPackageNameWithUser();
        if (appRecord.isSystem())
            return "系统应用 / 白名单不允许冻结: " + name;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        if (!options.enabled)
            return "该应用已设置为「不冻结」: " + name;

        AppState appState = appRecord.getAppState();
        if (appState != null) {
            if (appState.isVisible())
                return "应用正在前台, 已阻止冻结: " + name;
            if (appState.isAudio() && options.keepAudio)
                return "应用正在后台播放音频, 按设置不冻结: " + name;
            if (appState.isRecording())
                return "应用正在录音, 已跳过: " + name;
            if (appState.isLocation())
                return "应用正在使用定位, 已跳过: " + name;
            if (appState.isVpn())
                return "应用正在使用 VPN, 已跳过: " + name;
        }

        FreezerService.freezer(appRecord);

        if (appRecord.isFrozen())
            return "已冻结 " + name;

        boolean anyFrozen = false;
        boolean allExcluded = true;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            if (processRecord.isFrozen())
                anyFrozen = true;
            if (AppConfigs.shouldFreezeProcess(options, processRecord.getProcessName()))
                allExcluded = false;
        }
        if (anyFrozen)
            return "已部分冻结 " + name;
        if (allExcluded)
            return "进程规则排除了全部进程, 没有可冻结的进程: " + name;
        return "没有可冻结的进程: " + name;
    }

    private static String freezePid(AppRecord appRecord, RuntimeAction action) {
        int pid = action.pid;
        String name = appRecord.getPackageNameWithUser();
        if (appRecord.isSystem())
            return "系统应用 / 白名单不允许冻结: " + name;

        AppOptions options = AppConfigs.getOptions(appRecord.getPackageName(), appRecord.getUserId());
        if (!options.enabled)
            return "该应用已设置为「不冻结」: " + name;

        AppState appState = appRecord.getAppState();
        if (appState != null) {
            if (appState.isVisible())
                return "应用正在前台, 已阻止冻结进程 " + pid;
            if (appState.isAudio() && options.keepAudio)
                return "应用正在后台播放音频, 按设置不冻结进程 " + pid;
            if (appState.isRecording())
                return "应用正在录音, 已阻止冻结进程 " + pid;
            if (appState.isLocation())
                return "应用正在使用定位, 已阻止冻结进程 " + pid;
            if (appState.isVpn())
                return "应用正在使用 VPN, 已阻止冻结进程 " + pid;
        }

        ProcessRecord target = findProcess(appRecord, pid);
        if (target == null)
            return "进程 " + pid + " 已经退出";

        // 手动冻结也不能绕过进程规则: 否则下一个 tick 的 reconcile 会立刻把它解冻,
        // 用户只会看到「已冻结」的提示但状态马上又变回未冻结.
        if (!AppConfigs.shouldFreezeProcess(options, target.getProcessName())) {
            String rule = options.processMode == AppOptions.PROCESS_MODE_ONLY ? "只冻选中" : "排除选中";
            return "进程规则「" + rule + "」排除了该进程, 请先调整规则再手动冻结";
        }

        // 校验 pid 没有被系统复用给别的进程 (状态文件里带的 startTime 是冻结时的快照).
        if (action.startTime > 0L) {
            long currentStartTime = ProcessStat.getStartTime(pid);
            if (currentStartTime > 0L && currentStartTime != action.startTime)
                return "进程 " + pid + " 已经重启 (pid 被复用), 请刷新后重试";
        }

        if (target.isFrozen())
            return "进程 " + pid + " 已经是冻结状态";

        if (!FreezerService.freezeProcess(target))
            return "写入 cgroup.freeze 失败 (进程可能已退出): " + pid;

        target.setFrozen(true);
        // 只有全部存活进程都冻结了才把应用标记为已冻结, 部分冻结靠进程列表展示;
        // 变成「全部冻结」时这里会补上报系统.
        FreezerService.updateReportedState(appRecord);
        Log.i("手动冻结进程: " + name + " pid=" + pid);

        // 这里不通知系统「整个 uid 冻结」, 也不动闹钟 / 网络: 手动操作只改进程状态,
        // 避免系统按整个应用冻结去调度. (如果之后全部进程都冻结, reconcile 会补上报.)
        return appRecord.isFrozen() ? "已冻结进程 " + pid + " (应用已全部冻结)" : "已冻结进程 " + pid;
    }

    private static String thawPid(AppRecord appRecord, RuntimeAction action) {
        int pid = action.pid;
        String name = appRecord.getPackageNameWithUser();
        ProcessRecord target = findProcess(appRecord, pid);
        if (target == null)
            return "进程 " + pid + " 已经退出";
        if (action.startTime > 0L) {
            long currentStartTime = ProcessStat.getStartTime(pid);
            if (currentStartTime > 0L && currentStartTime != action.startTime)
                return "进程 " + pid + " 已经重启 (pid 被复用), 请刷新后重试";
        }
        if (!target.isFrozen())
            return "进程 " + pid + " 当前没有冻结";

        FreezerService.thawProcess(target);
        // 解冻后如果不再「全部冻结」, 这里会补上报 unfrozen.
        FreezerService.updateReportedState(appRecord);

        Log.i("手动解冻进程: " + name + " pid=" + pid);
        return "已解冻进程 " + pid;
    }

    private static ProcessRecord findProcess(AppRecord appRecord, int pid) {
        if (pid <= 0)
            return null;
        for (ProcessRecord processRecord : appRecord.getProcessRecords()) {
            if (processRecord == null || processRecord.isDeathProcess())
                continue;
            if (processRecord.getPid() == pid)
                return processRecord;
        }
        return null;
    }
}

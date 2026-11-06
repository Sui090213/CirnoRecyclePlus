# Cirno Recycle+ 内存回收 / 前台压制设计说明

本文说明 `app/src/main/java/nep/timeline/cirno/memory/` 下这套东西是怎么工作的、为什么这么设计、以及失败时怎么降级。

## 1. 为什么冻结之后还要回收

Cirno 的冻结只解决 CPU / 调度问题：进程被写 `cgroup.freeze` 之后不再参与调度，但**它占的页仍然躺在内存里**。而且冻结进程：

- 收不到 `onTrimMemory`，应用层没法自己释放内存；
- 不会执行 GC，堆里的垃圾也清不掉；
- 系统内存紧张时，LMKD 还是会按 oom_score 去杀后台 —— 你刚冻住的应用一样会被杀。

所以「冻结」和「省内存」是两件事。这套模块补的是后者：**从内核侧直接回收页，冻结进程不需要解冻也能生效**。

## 2. 总体结构

| 文件 | 作用 |
|---|---|
| `MemoryService.java` | 主循环：PSI 采样 + 水位判定 → 压力分级 → 选候选 → 执行 L0/L2 → 反馈提前停止 |
| `PsiMonitor.java` | 解析 `/proc/pressure/memory`（some / full 行），纯静态方法，可单元测试 |
| `WatermarkGate.java` | MemAvailable 水位 + 迟滞判定，PSI 不可用或低估压力时兜底；纯逻辑可测试 |
| `CgroupMemory.java` | cgroup v2 路径探测、读 `memory.current` / `MemAvailable`、写 `memory.reclaim`；支持从 `/proc/<pid>/cgroup` 反查目录 |
| `CompactHelper.java` | 反射调用系统 `CachedAppOptimizer.compactApp`，按方法签名动态适配 |
| `ForegroundGuard.java` | 前台内存压制：给 top-app / foreground cgroup 写 `memory.high` 软上限 |
| `ProcessReaper.java` | 长时间冻结后的容器子进程深度回收；默认 dry-run，用户可切换实际 kill |
| `utils/PushChannelGuard.java` | 推送包 / 推送进程识别，回收和 compact 时跳过消息长连接 |
| `ScreenState.java` | 「仅息屏时回收」用的屏幕状态查询 |
| `MemorySettings.java` | 配置对象（Gson 序列化到 `GlobalSettings.json` 的 `memory` 字段） |

接入点只有三处：

| 文件 | 改动 |
|---|---|
| `configs/settings/GlobalSettings.java` | 新增 `memory` 字段 |
| `threads/Handlers.java` | 新增 `memory` 后台 HandlerThread |
| `master/AndroidHooks.java` | `MemoryService.start()` |

所以它不会干扰 Cirno 原来的冻结 / 解冻 / 广播 / 网络等 hook，出问题最多是回收不生效，不会把冻结搞坏。

## 3. 触发：PSI + MemAvailable 水位兜底

`/proc/pressure/memory` 的格式：

```
some avg10=0.00 avg60=0.00 avg300=0.00 total=0
full avg10=0.00 avg60=0.00 avg300=0.00 total=0
```

- `some`：至少有一个任务因为内存停摆的时间占比；
- `full`：所有任务都停摆的时间占比。

本项目取 **`max(some avg10, full avg10)`** 作为压力值，分三级：

| 级别 | 条件 | 行为 |
|---|---|---|
| 正常 | `< psiSomeThreshold` | 不回收（前台压制的 `memory.high` 仍然维持） |
| 中等 | `>= psiSomeThreshold` | 跑 1 轮回收 |
| 高压 | `>= psiSomeThreshold * psiHighMultiplier` | 最多跑 `maxRoundsPerCycle` 轮，每轮重新采样并重新选应用 |

另外：

- `cooldownMs`：两次「回收周期」之间最小间隔，避免持续高压下反复回收；
- `psiStopThreshold`：回收过程中 PSI 降到该值以下就提前结束，避免过度回收；
- `screenOffOnly`：亮屏时不自动回收；
- 手动「立即回收一次」：写 `requestSeq` 时间戳，system_server 检测到变化后无视阈值和冷却强制跑一轮。

**v1.4.0 新增 MemAvailable 水位兜底**（`WatermarkGate`）：

- 读取 `/proc/meminfo` 的 `MemAvailable / MemTotal`，剩余可用内存低于 `availableTriggerPct`（默认 10%）时进入触发态；
- 触发后要回升到 `availableTriggerPct + availableHysteresisPct`（默认 +3%）才退出，避免在阈值附近反复回收；
- PSI 可用时，最终级别取 `max(PSI 级别, 水位级别)`：PSI 低估压力时水位仍能保底；PSI 不可用时水位单独工作；
- 低于触发水位的 60% 视为高压，允许连续回收多轮。

## 4. 候选筛选

从 Cirno 已有的 `AppRecord` 里挑，**不额外 hook**，所以选出来的应用和冻结器看到的状态一致：

```
appRecord.isSystem()          -> 跳过（系统应用 / 当前输入法 / 白名单 / 内置白名单）
PushChannelGuard.isPushPackage -> 跳过（专门承担系统推送的包）
appState.isVisible()          -> 跳过（可见应用）
appState.isLocation/audio/recording/vpn -> 跳过（正在定位 / 放音 / 录音 / VPN）
frozenOnly && !appRecord.isFrozen()      -> 跳过（默认只回收冻结应用）
appOptions.allowReclaim == false         -> 跳过（长按面板里关掉了「允许内存回收」）
没有存活进程                    -> 跳过
cgroup memory.current < minCgroupBytes   -> 跳过（收益太低）
```

同一 uid 只处理一次（多用户 / 多进程应用会共享一个 uid cgroup）。

## 5. cgroup 路径探测

不同 ROM 的布局不一样，优先按 Cirno `FrozenRW` 的规则探测：

| 布局 | 路径 |
|---|---|
| 非隔离 | `/sys/fs/cgroup/uid_<uid>` |
| 隔离 + 系统应用 | `/sys/fs/cgroup/system/uid_<uid>` |
| 隔离 + 普通应用 | `/sys/fs/cgroup/apps/uid_<uid>` |

探测判据是该目录下存在 `memory.current`（说明 memory 控制器开到了 uid 这一层）。**只接受 `uid_*` 这一层**：如果 ROM 只在父级 `apps/` / `system/` 开 memory 控制器，写 `memory.reclaim` 会回收整个子树而不是单个应用，风险太大，直接跳过。

静态探测失败时，会读存活进程的 `/proc/<pid>/cgroup`（形如 `0::/apps/uid_10123/pid_4567`），向上找到 `uid_*` 那一层目录作为兜底，并缓存结果。

## 6. 执行：L0 + L2

对每个候选，按 `memory.current` 从大到小处理：

### L0 `memory.reclaim`

- 单次目标 = `min(memory.current * reclaimRatio, maxBytesPerApp)`；
- 若开启 `useSwapReclaim`，写入 `"<bytes> swap"`，让内核把匿名页换出到 zram / swap；写入失败（`EINVAL`，没有 swap 或内核不支持）自动回退到 `"<bytes>"`；
- 写入成功与否看异常；回收量用写入前后的 `memory.current` 差值估算（并发分配会造成抖动，只用于日志展示）；
- 失败（`EBUSY` / `EAGAIN` / `EINVAL`）属于正常情况，不刷屏，直接交给 L2；
- 需要 Linux 5.19+。没有 `memory.reclaim` 时 `RESULT_UNSUPPORTED`，整个 L0 静默降级。

### L2 `compactApp`

复用 Cirno 已有的 `CachedAppOptimizer` 实例（由 `CacheEnableFreezerHook` / `CacheUseFreezerHook` 拿到），反射调用 `compactApp`：

- 先找方法名 `compactApp`、第一个参数是 `com.android.server.am.ProcessRecord` 的重载；
- 后续参数按类型填：`boolean` → `true`（强制压缩），`int` → `CompactSource` 常量；
- 部分 Android 版本把 `CompactProfile` / `CompactSource` 做成了**枚举**，这时按枚举常量名做保守匹配（`FULL` / `APP` 等），匹配不到就用第一个常量；
- 调用时按框架习惯持有 AMS 的 `mProcLock`（每个进程一次调用，调用之间会释放锁）；
- 方法找不到 / 系统没开压缩（`mUseCompaction=false`）/ 调用抛异常时只打一次日志并跳过。

## 7. 前台内存压制（实验性，默认关）

「压制前台进程内存」有三条实现路径，全部默认关闭：

### 7.1 `memory.high` 软上限 —— `ForegroundGuard`

给 top-app / foreground cgroup 写 `memory.high = MemTotal * foregroundHighRatio`（默认 80%）。

- `memory.high` 是**软上限**：超过后内核会先对分配方限流、积极回收，而不是像 `memory.max` 那样直接 OOM kill；
- 位置很关键：放在 top-app 上意味着前台应用内存失控时先卡它自己，给后台（包括冻结应用）留出空间；
- 安全约定：
  - 只改 `memory.high`，**绝不改 `memory.max`**；
  - ROM 自己已经设了更严格的 `memory.high` 时**不覆盖**；
  - **ROM 原值和「我们写入的值」都会持久化到 `/data/misc/CirnoRecycle/foreground_guard.orig`**，这样即使 system_server 软重启（LSPosed 环境常见），也不会把自己的写入值误当成 ROM 原值；关闭开关时按记录恢复；
  - 检测到 `memory.high` 被其他程序改过时，只清理状态文件、不强行恢复（避免覆盖别人的设置）；
  - 计算出的上限低于 512MB 时拒绝写入；
  - 找不到 top-app/foreground 的 `memory.high` 文件时打印一次警告并跳过，不影响其它功能。

### 7.2 前台冷页压缩 —— `foregroundColdCompact`

内存有压力时，找到当前 `visible` 的应用，对它调用 `compactApp`，把冷页标记成可回收页（`MADV_COLD`）。间隔由 `foregroundColdIntervalMs` 控制（默认 30 秒）。

### 7.3 前台组直接回收 —— `foregroundReclaim`

对 `foreground` cgroup 写 `memory.reclaim`（可见但没在交互的应用所在的组），不含 top-app。目标同样是 `current * reclaimRatio`，上限 `maxBytesPerApp`。找不到该 cgroup 时提示一次并跳过。

> 为什么不做 `process_madvise` / 改 LMKD / 改 zram：
> - `process_madvise` 需要 pidfd + `CAP_SYS_NICE` + JNI，system_server 里大概率 EPERM；
> - LMKD 参数要 `resetprop` + 重启 lmkd，是 root shell 的事；
> - zram 参数属于 root 引导脚本，配错了会直接影响整机。
> 这些都不适合塞进 LSPosed 模块，所以没做。

## 8. 失败降级总表

| 情况 | 表现 | 降级 |
|---|---|---|
| 内核 < 5.19，无 `memory.reclaim` | 日志警告一次 | 只走 L2 |
| cgroup 只有父级 memory 控制器 | 找不到 `uid_*` 的 `memory.current` | 该应用跳过 |
| `memory.reclaim` 写入 EBUSY/EAGAIN | 记 debug 日志 | 走 L2 |
| 系统关压缩 / 找不到 compactApp | 日志警告一次 | 只走 L0 |
| 没有 top-app `memory.high` | 日志警告一次 | 前台压制跳过 |
| 没有 PSI | 日志警告一次 | v1.4.0 起改走 MemAvailable 水位兜底；水位也不可用才停用 |
| 没有 LSPosed | 模块代码不加载 | App 里看不到日志，需要用户检查 |

## 9. 配置

配置在 `/data/misc/CirnoRecycle/GlobalSettings.json` 的 `memory` 字段，字段含义见 README 的「设置项说明」。App 内的设置页会通过 root 读写这个 JSON（单线程写队列 + 临时文件原子替换），system_server 的 `ConfigFileObserver` 监听目录变化，2 秒内热重载。

字段都带默认值：旧配置缺字段时 Gson 反序列化会自动用默认值，升级不需要手动迁移。配置读取失败时 system_server 会保留上一次的配置，App 侧会锁定写入并提示用户，避免损坏的配置被默认值覆盖。

## 10. 验证方法

```bash
# 内核能力
adb shell cat /proc/pressure/memory
adb shell cat /sys/fs/cgroup/cgroup.controllers
adb shell ls /sys/fs/cgroup/apps/uid_*/memory.reclaim 2>/dev/null | head

# 运行日志
adb shell 'tail -f /data/misc/CirnoRecycle/log/current.log'
adb logcat -s CirnoRecycle

# 冻结状态
adb shell 'for f in /sys/fs/cgroup/*/uid_*/pid_*/cgroup.freeze; do [ "$(cat $f)" = 1 ] && echo $f; done'
```

建议顺序：

1. 只开总开关 + L0/L2，默认参数跑一天；
2. 开 `dryRun` 看候选应用列表是否符合预期；
3. 需要更激进再调 `psiSomeThreshold` / `reclaimRatio`；
4. 前台压制最后再试，卡顿就关。

## 11. 已知限制

- `memory.reclaim` 需要 **Linux 5.19+**；更老的安卓内核只能靠 L2。
- 部分 ROM 的 memory 控制器只开在父级 cgroup，此时逐应用回收不可用（代码会跳过，不会误回收整棵子树）。
- L2 依赖系统 `CachedAppOptimizer` 的内部实现，属于反射调用：Android 大版本升级后方法签名可能变化，代码会动态适配，适配失败只降级不崩溃。
- 前台三层压制都是**实验性**的：不同 ROM 的 cgroup 布局 / SELinux 策略差异很大，探测不到就跳过；`memory.high` 压得太低会造成卡顿，所以默认关闭、下限 50%、最低 512MB。
- 回收量用 `memory.current` 前后差值估算，并发分配时会不准，只用于日志统计，不影响实际回收。

## 12. 单应用冻结选项（v1.1.0，v1.2.0 扩展白名单）

每个应用可以单独配置冻结行为，存在 `ApplicationSettings.json` 的 `appOptions` 里
（key 是 `packageName#userId`，`AppOptions.java`）：

| 字段 | 默认 | 作用 |
|---|---|---|
| `enabled` | `true` | 参与自动冻结；false 时这个应用永远不冻结（仍可被内存回收） |
| `keepAudio` | `true` | 后台播放音频时跳过冻结；关掉后播放中也会冻 |
| `keepNetwork` | `false` | 冻结时保留网络连接（Android 15 及以下跳过 `socketDestroy`） |
| `keepAlarms` | `false` | 冻结时保留闹钟（跳过 `ForceAppStandbyListener.removeAlarmsForUid`） |
| `allowReclaim` | `true` | 允许内存回收模块回收这个应用 |
| `wakeOnPush` | `true` | 收到 FCM / 厂商推送广播时临时解冻 15 秒（v1.2.1） |
| `allowFreezeGoogle` | `false` | Google 服务专用：打开后才解除保护、允许自动 / 手动冻结（v1.2.2） |
| `processMode` | `0` | `0` 冻结全部进程 / `1` 只冻结 `selectedProcesses` / `2` 排除 `selectedProcesses` |
| `selectedProcesses` | `[]` | 进程规则用到的进程名集合 |

`FreezerService.freezer()` 的流程因此变成：

1. 应用 `isProtected()`（系统应用 / 输入法 / 内置保护应用）→ 跳过；Google 全家桶默认也走保护，
   只有用户在应用面板里显式打开 `allowFreezeGoogle` 后才不再跳过；
2. `enabled == false` → 跳过；
3. **用户白名单（v1.2.3）→ 直接跳过，且 `reconcile` 解冻该应用全部已冻结进程**；
   白名单不再受 `processMode` 影响，子进程 / 悬浮窗进程也不会被自动或手动冻结；
4. 可见 / 定位 / 录音 / VPN → 跳过；显式允许冻结 Google 服务时跳过定位 / 录音 / VPN；
   音频且 `keepAudio` → 跳过；
5. 逐个存活进程按 `processMode` 决定冻不冻；
6. **只有全部存活进程都冻结成功**才：
   - 设置 `appRecord.frozen = true`；
   - 上报 `reportOneUidFrozenStateChanged` / `socketDestroy`（受 `keepNetwork` 影响；
     v1.2.1 起 `keepNetwork` 在 Android 16 上也不再上报 frozen）；
   - 移除闹钟（受 `keepAlarms` 影响）；
7. 只冻结了一部分时只记录进程级 `frozen` 标记（`manuallyFrozen` 区分手动 / 自动来源），
   **不上报整个 uid 冻结**，避免系统按「整应用冻结」错误调度。

对应的 `thaw()` 也改成遍历所有进程解冻，不再依赖 `appRecord.frozen`，所以「部分冻结」也能被正确解冻。

### 12.0.1 配置 mtime 兜底（v1.2.3）

`ConfigFileObserver` 在部分 ROM / root shell 写 `/data/misc` 时可能漏事件。`RuntimeStateService`
每 2 秒调用 `ConfigManager.manager.reloadIfChanged()`，按 `GlobalSettings.json` /
`ApplicationSettings.json` 的 mtime 变化重载配置，确保白名单 / `allowFreezeGoogle` 修改在
2 秒内被 system_server 看到。

### 12.1 配置变化后即时生效（v1.2.0）

`RuntimeStateService` 每 2 秒调用一次 `FreezerService.reconcile()`。v1.2.0 起 `reconcile` 除了
「解冻不该冻的进程」，还会在下面这种情况补发一次自动冻结调度（每个应用 30 秒最多一次）：

- 应用已经在后台、没有可见 / 音频保护，且按当前规则至少有一个进程应该被冻结，但当前没有任何冻结进程。

这样「刚从白名单移除 / 刚打开参与自动冻结 / 刚把规则改成只冻选中」不需要等下一次「进入后台」事件才生效。
白名单 + 「全部进程」模式下，`reconcile` 只解冻**自动冻结**残留，保留用户手动冻结的进程。

## 13. 运行时状态 / 手动进程冻结通道（v1.1.0）

App 与 system_server 不在同一进程，也没有现成的 binder 接口，所以用两个很小的文件通信
（`/data/misc/CirnoRecycle/`，由 root 创建的 system:system 0770 目录）：

| 文件 | 写入方 | 读取方 | 内容 |
|---|---|---|---|
| `RuntimeState.json` | system_server（`RuntimeStateService`） | App（`RuntimeClient`） | 有存活进程的应用列表、冻结进程数、每个进程的 pid / 名称 / 是否冻结、最后一次手动操作结果 |
| `RuntimeActions.json` | App（`RuntimeClient.sendActions`） | system_server（`RuntimeStateService` 轮询） | 手动冻结 / 解冻应用或进程的操作列表 |

可靠性设计：

- **写文件原子化**：system_server 写临时文件再 `rename`；App 用 root 的 `临时文件 + mv`。读方永远读不到半个 JSON。
- **开机标记**：两个文件都带 `bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()`。上一次开机残留的文件（比如模块被停用后留在磁盘上的旧操作）会因开机时间对不上被丢弃，绝不执行。
- **单调序号**：`RuntimeActions.seq` 取 `max(当前时间戳, 旧序号 + 1)`；system_server 只执行比「已执行序号」更大的批次，模块软重启后第一次看到文件只登记基线、不重放。
- **性能**：状态内容没变时只在超过 10 秒时补写一次心跳时间戳；应用没在跑就不写进状态文件，正常文件只有几 KB。
- **过期判定**：App 侧要求状态文件是本次开机写的且 90 秒内更新过，否则显示「状态未知」，不会把旧状态当当前状态。
- **安全限制（system_server 侧执行时校验）**：
  - 应用 `isProtected()` / `enabled == false` → 拒绝冻结；
  - 白名单应用：允许冻结子进程，但拒绝冻结主进程；允许整应用解冻，拒绝整应用冻结；
  - 应用当前可见（前台）→ 拒绝冻结进程，避免卡住正在使用的界面；
  - 正在录音 / 定位 / VPN，或音频且 `keepAudio` → 拒绝（应用级、进程级手动冻结都会检查）；
  - 进程已退出 / 已经是目标状态 → 直接返回原因；
  - 进程级操作会校验 pid 对应的进程启动时间（`RuntimeState.json` 里的 `startTime`，来自 `/proc/<pid>/stat`），
    pid 被系统复用给别的进程时拒绝执行，避免冻错进程；
  - 解冻不做限制。
- **反馈**：执行结果（成功 / 拒绝原因）写回 `RuntimeState.json` 的 `lastActionMessage` / `lastActionAtMs`，界面用 Toast 显示。
- **配置变化对齐**：`RuntimeStateService` 每 2 秒调用一次 `FreezerService.reconcile()`：
  被设为「不冻结」/ 内置保护的已冻结应用会被主动解冻；进程规则排除的已冻结进程会被解冻；
  白名单应用只解冻自动冻结的残留、保留手动冻结；全部冻结但还没上报系统的会补一次通知
  （不移闹钟 / 断网由应用选项决定）；已经在后台且按规则该冻的会补发一次冻结调度（见 12.1）。
- **文件清理**：`RuntimeActions.json` 不会被 system_server 主动删除，只有两种情况会被删掉：
  上一次开机残留（bootTime 对不上）、或者 seq 非法（手改 / long 溢出）——避免手动操作通道永久失效。
- **软重启取舍**：如果操作已经写进文件、但 system_server 还没来得及执行就软重启（LSPosed 环境常见），
  重启后第一次看到该文件会把它登记为「基线」而不是重放，避免开机时突然冻结某些应用。
  这种情况下 App 里会显示「已发送」但状态没变，再点一次即可（界面上也会提示「操作已发送，正在执行…」后无结果）。

## 13.5 长时间冻结后的子进程深度回收（v1.4.0）

`ProcessReaper` 只在用户显式打开「回收非必要子进程」后工作，默认 `reapApply=false` 只写 dry-run 日志。

触发条件（全部满足）：

- 应用完整冻结超过 `reapMinFrozenMinutes`（默认 30 分钟）；
- 不是系统 / 白名单 / 可见 / 音频 / 定位 / 录像 / VPN；
- 不是 `PushChannelGuard.isPushPackage` 的推送包；
- 每个应用有 `reapCooldownMs`（默认 10 分钟）冷却，连续 2 轮后 6 小时退避。

只杀白名单内的容器型子进程：`:appbrand*`、`:xweb*`、`:sandbox*`、`:video`、`:reader`；游戏 `:game` / `:gameservice` 默认不动，避免丢未保存进度；
主进程、`:push` / `:MSF` / `:mipush` 等推送通道、`oom_score_adj < 900` 的 service 进程、未知子进程一律跳过。

kill 前会核对 `/proc/<pid>/stat` 的 starttime，防止 pid 复用；使用 `Process.killProcess`，不经过 shell。

## 14. 验证方法（补充手动冻结）

```bash
# 状态文件是否在刷新 (updatedAtMs 应该一直在变)
adb shell su -c 'cat /data/misc/CirnoRecycle/RuntimeState.json' | head -c 400

# 某个应用的进程冻结状态
adb shell su -c 'cat /sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze'

# 手动操作通道 (App 里点「冻结」后这个文件会被更新; 正常执行后不会被删除)
adb shell su -c 'cat /data/misc/CirnoRecycle/RuntimeActions.json'

# 最近一次手动操作结果
adb shell su -c 'grep -o "lastActionMessage[^,]*" /data/misc/CirnoRecycle/RuntimeState.json'
```

## 15. v1.2.0 的界面 / 更新检查（不涉及 system_server）

这些功能只在 App 进程里，不参与冻结 / 回收，因此不需要 root，也不会影响模块稳定性：

- **更新检查**（`client/UpdateChecker.kt`）：读仓库根目录的 `update.json`（GitHub raw + jsDelivr 镜像），
  比较版本名（APK 的 `versionCode` 是小整数，和模块的 `versionCode`（12000 这一套）不是一套编号）；有新版只弹窗提示并跳浏览器下载，不在 App 内静默安装。`update.json` 同时给
  Magisk / KernelSU 的模块更新使用（`module.prop` 的 `updateJson`）。
- **主题**（`ui/Theme.kt`）：Material3；深色模式跟随系统 / 强制浅色 / 强制深色；
  莫奈取色用 `dynamicLight/DarkColorScheme`；自定义主题色把种子色转 HSL 后生成一套配色，不引入第三方取色库。
- **首页搜索**：应用名在 IO 线程预加载成 Map，输入时只做字符串过滤。
- **后台应用优先排序**：按 `RuntimeState.json` 里每个应用的存活进程数 / 是否可见排序。
- **完整进程列表**：`client/ProcessCatalog.kt` 从 `PackageManager` 的四大组件里收集 `android:process`
  （`:push` 会补全成 `包名:push`），再和运行中的进程合并；未运行的进程也能参与进程规则。
- 以上偏好存在 App 本地 `SharedPreferences`（`client/AppPrefs.kt`），和 `GlobalSettings.json` /
  `ApplicationSettings.json` 完全独立。

## 16. 推送 / 网络唤醒（v1.2.1 / v1.2.2）

FCM 的链路是：应用服务器 → Google FCM → GMS 长连接 → `com.google.android.c2dm.intent.RECEIVE`
广播 → 目标应用。模块要保证这条链路不被「冻结」破坏：

1. **推送基础设施默认保护**：`CommonConstants.whiteApps` 增加了 `com.google.android.gms`（FCM 长连接）、
   `com.google.android.gsf`、`com.android.vending`、`com.google.android.configupdater`、
   `com.google.android.ext.services`、`com.google.android.ext.shared` 和 MicroG 相关包。
   无论这些包是不是系统应用，都不参与冻结 / 回收。
   - v1.2.2 起这些应用仍然默认受保护，但会显示在首页列表里（名字前带 `(Google 保护)`）。
     不使用 FCM 的用户可以逐个打开 `AppOptions.allowFreezeGoogle` 解除保护；打开该开关时
     会同时打开 `enabled`（参与自动冻结），关闭时会恢复保护并解冻。
   - `CommonConstants` 的白名单/Google 集合已从 `Set.of(...)` 改为
     `LinkedHashSet` + `Collections.unmodifiableSet`：`Set.of` 遇到重复元素会在类初始化阶段抛
     `IllegalArgumentException`（v1.2.1 的闪退就是 `app.revanced.android.gms` 重复了一次），
     新写法会自动去重，不会再因此崩进程。
   - `ServiceStartHook` / `BroadcastIntentHook` / `BroadcastSkipHook` 的回调都包了
     `try/catch (Throwable)`，Hook 内部异常不会再抛回 system_server。
2. **广播意图先解冻**（`BroadcastIntentHook`）：
   - 遍历广播参数按类型找 `Intent`，不再依赖硬编码下标（OEM 改参数布局时旧实现会失效）；
   - 同时兜底 hook `ActivityManagerService.broadcastIntentWithFeature` / `broadcastIntent` /
     `broadcastIntentWithCallback`，防止 ROM 把内部方法挪类；
   - 推送 action 由 `PushUtils` 统一判断（FCM / UnifiedPush / 魅族 / 华为 / 小米 / vivo / OPPO）；
   - 按**包名**解冻，不依赖 userId 参数。
3. **推送广播不再被跳过**（`BroadcastSkipHook`，Android 14+）：
   - 适配 AOSP 16 的 `shouldSkipMessage(BroadcastRecord, Object)` 与部分 ROM 的
     `(BroadcastRecord, BroadcastFilter)` 两种签名；
   - 推送广播直接放行，并在投递决定处再解冻一次；非推送广播仍然跳过冻结进程。
4. **15 秒投递窗口**：`FreezerService.FREEZE_HOLD_UNTIL_MS` 按包名记录
   `SystemClock.elapsedRealtime()` 截止时间。窗口内 `freezer()` 不冻结，只重新安排「窗口结束后的冻结」；
   即使进程是刚被推送拉起、AppRecord 还没建好，`ProcessAddHook` 3 秒后的冻结也会被挡住。
   `temporaryUnfreezeIfNeedByPackage()` 负责解冻 + 记录窗口；`AppOptions.wakeOnPush` 可单应用关闭。
5. **服务启动 / 绑定唤醒**（`ServiceStartHook`）：hook AMS 的 `startService` / `bindService` /
   `bindServiceInstance`，目标应用已冻结时先解冻 15 秒（只处理冻结应用，避免频繁服务启动拖慢 Freezer 线程）。
6. **keepNetwork 在 Android 16 生效**：`notifyFrozenLocked` 在 `keepNetwork` 打开时既不调用
   `socketDestroy`（≤ Android 15），也不上报 `reportOneUidFrozenStateChanged`（Android 16+），
   避免系统的冻结网络策略继续断网。

验证：

```bash
# 收到推送时日志里应出现 MESSAGE PUSH / 放行推送广播
adb shell su -c 'grep -E "MESSAGE PUSH|放行推送广播" /data/misc/CirnoRecycle/log/current.log | tail'
# 推送窗口结束后应用应重新被冻结 (窗口内 cgroup.freeze = 0)
adb shell su -c 'cat /sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze'
```

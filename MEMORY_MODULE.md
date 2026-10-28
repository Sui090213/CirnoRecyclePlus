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
| `MemoryService.java` | 主循环：PSI 采样 → 压力分级 → 选候选 → 执行 L0/L2 → 反馈提前停止 |
| `PsiMonitor.java` | 解析 `/proc/pressure/memory`（some / full 行），纯静态方法，可单元测试 |
| `CgroupMemory.java` | cgroup v2 路径探测、读 `memory.current`、写 `memory.reclaim`；支持从 `/proc/<pid>/cgroup` 反查目录 |
| `CompactHelper.java` | 反射调用系统 `CachedAppOptimizer.compactApp`，按方法签名动态适配 |
| `ForegroundGuard.java` | 前台内存压制：给 top-app / foreground cgroup 写 `memory.high` 软上限 |
| `ScreenState.java` | 「仅息屏时回收」用的屏幕状态查询 |
| `MemorySettings.java` | 配置对象（Gson 序列化到 `GlobalSettings.json` 的 `memory` 字段） |

接入点只有三处：

| 文件 | 改动 |
|---|---|
| `configs/settings/GlobalSettings.java` | 新增 `memory` 字段 |
| `threads/Handlers.java` | 新增 `memory` 后台 HandlerThread |
| `master/AndroidHooks.java` | `MemoryService.start()` |

所以它不会干扰 Cirno 原来的冻结 / 解冻 / 广播 / 网络等 hook，出问题最多是回收不生效，不会把冻结搞坏。

## 3. 触发：PSI

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

## 4. 候选筛选

从 Cirno 已有的 `AppRecord` 里挑，**不额外 hook**，所以选出来的应用和冻结器看到的状态一致：

```
appRecord.isSystem()          -> 跳过（系统应用 / 当前输入法 / 白名单 / 内置白名单）
appState.isVisible()          -> 跳过（可见应用）
appState.isLocation/audio/recording/vpn -> 跳过（正在定位 / 放音 / 录音 / VPN）
frozenOnly && !appRecord.isFrozen()      -> 跳过（默认只回收冻结应用）
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
- 调用时按框架习惯持有 AMS 的 `mProcLock`；
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
  - 记录原始值（`max` 或数字），关闭开关时恢复；
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
| 没有 PSI | 日志警告一次 | 回收整体停用 |
| 没有 LSPosed | 模块代码不加载 | App 里看不到日志，需要用户检查 |

## 9. 配置

配置在 `/data/misc/CirnoRecycle/GlobalSettings.json` 的 `memory` 字段，字段含义见 README 的「设置项说明」。App 内的设置页会通过 root 读写这个 JSON，system_server 的 `ConfigFileObserver` 监听目录变化，2 秒内热重载。

字段都带默认值：旧配置缺字段时 Gson 反序列化会自动用默认值，升级不需要手动迁移。

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

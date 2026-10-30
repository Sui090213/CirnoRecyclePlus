# Changelog

本文件只记录 **Cirno Recycle+（二改版）** 相对上游 Cirno 的改动。上游自己的更新记录请见
[Freezer-Team/Cirno](https://github.com/Freezer-Team/Cirno)。

## v1.1.0 — 2026-10-30

在 v1.0.0 的基础上加「看得见、管得住」的冻结控制：冻结后界面会显示「已冻结」，
每个应用可以单独配置冻结行为，并且可以按进程选择性冻结；同时把过于复杂的内存设置页改简单了。

### 新增

- **冻结状态显示**
  - system_server 里的 `RuntimeStateService` 每 2 秒（内容不变时只每 10 秒补一次心跳）把正在运行的应用、冻结进程数、各进程 pid / 冻结状态原子写入 `/data/misc/CirnoRecycle/RuntimeState.json`；
  - 首页应用列表直接显示：`已冻结` / `部分冻结` / `前台运行` / `后台播放` / `后台运行 · N 进程` / `未运行`；
  - 应用详情页顶部同样显示实时状态；状态文件带「开机时间 + 更新时间」校验，上一次开机残留或模块没在跑时显示「状态未知」，不会误报「未运行」。
- **单应用冻结选项**（首页长按应用弹出底部面板；点进详情页是同一套内容）
  - 参与自动冻结（关掉 = 这个应用永远不冻结，但仍然可以被回收）；
  - 后台播放音频时不冻结（默认开，与 Cirno 原行为一致；关掉后播放中也会冻结）；
  - 冻结时保留网络连接（Android 15 及以下跳过 `socketDestroy`）；
  - 冻结时保留闹钟（默认不移除）；
  - 允许内存回收（内存回收模块会跳过这个应用）；
  - 白名单（完全不冻结也不回收）。
  - 选项存在 `ApplicationSettings.json` 的 `appOptions`（key 为 `packageName#userId`），写盘走原来的单线程写队列 + 原子替换。
- **自定义进程冻结**
  - 进程级规则：`全部进程` / `只冻结选中进程` / `排除选中进程`，自动冻结时按规则执行；
  - 进程列表：列出该应用所有存活进程（主进程 / 子进程、pid、是否已冻结），可以手动「冻结 / 解冻」单个进程，也可以冻结 / 解冻整个应用；
  - App 通过 `RuntimeActions.json` 下发操作，system_server 轮询执行（带 `bootTimeMs` + 单调 `seq`，模块软重启不会重放旧请求）；
  - 安全限制：应用在前台时拒绝冻结它的进程（否则会把正在用的界面冻住），录音 / 定位 / VPN / 后台播放（按选项）也会被拒绝并给出原因；
  - 执行结果回写到状态文件，界面用 Toast 显示「已冻结进程 123」/「应用正在前台, 已阻止冻结」等。
- **更简单的内存设置界面**
  - 基础设置：总开关、回收强度三档预设（保守 / 均衡 / 激进）、仅息屏回收、只回收冻结应用、`memory.reclaim`、进程压缩；
  - 前台内存压制单独一张卡，只有总开关 + 一句话说明；
  - 原来二十多个滑块全部收进「高级设置」，默认折叠，需要再展开；
  - 新增「恢复默认设置」。

### 变更

- `FreezerService` 现在按应用选项 / 进程规则决定冻结哪些进程；**只有全部存活进程都冻结成功**时才上报 uid frozen state 并断网 / 移闹钟，部分冻结只记录进程级状态，避免系统按「整个应用已冻结」错误调度。
- `FreezerService.thaw` 不再依赖 `appRecord.frozen`，能正确解冻「部分冻结」的进程。
- `MemoryService` 尊重应用级「允许内存回收」开关。
- 应用级「不冻结」的应用不再参与「解冻后重新安排冻结」的调度（解冻本身仍然执行，见下面修复）。
- 版本号 1.0.0 → 1.1.0，versionCode 2，模块 `versionCode=11000`。

### 修复

- **关闭「参与自动冻结」后应用被冻住打不开**：`temporaryUnfreezeIfNeed` 现在始终执行解冻，只跳过「解冻后重新安排冻结」；
  关掉开关时 App 也会主动下发一次解冻，system_server 每 2 秒的配置对齐会兜底解冻。
- **全部进程冻结时可能永远不通知系统**：新增 `AppRecord.reportedFrozen`，全部冻结但还没上报过时会补上报
  （uid frozen / socketDestroy / 移闹钟），重复调用不会重复上报；`thaw` 只在真正上报过时上报 unfrozen。
- **手动操作连点丢操作**：`RuntimeClient.sendActions` 现在会合并文件里尚未执行的操作、串行化并发发送、
  保证 seq 单调递增；序号非法（手改 / long 溢出）时 system_server 会删文件并重建基线，避免通道永久失效。
- **配置读写竞态导致设置回滚**：`ConfigManagerJson` 的读 / 写入口加锁，界面读配置也会和写队列串行；
  单应用选项的「修改内存对象」移到写线程执行，不再和 Gson 序列化并发改集合。
- **进程规则 / 白名单变化后不对齐**：`FreezerService.reconcile()` 每 2 秒跑一次，解冻被规则排除的进程、
  解冻被设为「不冻结 / 白名单」的应用。
- **手动冻结缺少安全检查**：进程级冻结补上录音 / 定位 / VPN 检查，并用 `/proc/<pid>/stat` 的启动时间
  校验 pid 没有被复用（状态文件新增 `startTime` 字段）。
- **手动冻结会被进程规则立刻撤销**：进程级手动冻结现在先检查当前进程规则，规则排除的进程直接提示
  「请先调整规则」，不再出现「提示已冻结但 2 秒后又被 reconcile 解冻」。
- **配置读取仍有竞态**：`AppOptionsStore.load()` 也加上 `ConfigWriter.LOCK`，所有读配置入口都和写队列串行。
- **进程启动时间读取缓存**：`ProcessRecord` 缓存 `/proc/<pid>/stat` 启动时间，状态服务不再每 2 秒对每个进程重复读 `/proc`。
- **轮询耗电**：首页 / 长按面板的状态轮询改为跟随 Activity `RESUMED` 生命周期，面板打开时首页暂停轮询。
- **时钟跳变会误丢手动操作**：开机标记改用 `/proc/stat` 的 `btime`，不再受 NTP 校时 / 用户改时间影响。
- `FrozenRW.frozen()` / `thaw()` 现在返回是否写入成功，手动冻结失败时能给出明确原因（进程已退出 / 没有 cgroup v2）。
- `RWUtils.writeFrozen` 检查 `PrintWriter` 的错误状态，不再出现「写失败却当成成功」。
- 状态文件里的进程列表为 `null` 时界面做兜底；手动操作结果 Toast 只显示自己那一批的结果；
  首页白名单角标在面板里改完后立即刷新；顶部状态新增「模块未响应」（有配置但读不到运行状态）。
- 冻结时如果所有进程都已死亡，不再把应用错误标记为「已冻结」。

### 测试

- 新增纯 JVM 单元测试：进程规则（全部 / 只冻选中 / 排除选中）、选项默认值、`sanitize()` 对非法 `processMode` 的校正。

## v1.0.0 — 2026-10-28

二改首发版本。基于用户提供的 Cirno-main 源码（上游版本号 8.0）。

### 新增

- **内存回收子系统**（`app/src/main/java/nep/timeline/cirno/memory/`）
  - L0：写 cgroup v2 `memory.reclaim` 回收冻结应用 cgroup 的页缓存 / 匿名页；支持 `N swap` 换出到 zram。
  - L2：反射调用系统 `CachedAppOptimizer.compactApp` 做进程内存压缩（动态适配方法签名）。
  - PSI 触发：解析 `/proc/pressure/memory`，some / full 取大者分级，支持冷却、提前停止、高压多轮。
  - 候选筛选默认**只回收已冻结应用**，排除系统 / 白名单 / 可见 / 音频 / 录音 / 定位 / VPN。
  - cgroup 路径三级探测 + `/proc/<pid>/cgroup` 反查兜底。
  - 失败全面降级：没有 `memory.reclaim` 只走 L2，没有压缩只走 L0，都不可用则静默跳过。
- **前台内存压制（实验性，默认关）**
  - `ForegroundGuard`：给 top-app cgroup 写 `memory.high` 软上限，记录原值、可恢复、不覆盖 ROM 更严格的设置。
  - `foregroundColdCompact`：内存有压力时对可见应用调用 `compactApp`。
  - `foregroundReclaim`：直接回收 `foreground` cgroup（不含 top-app）。
- **设置界面**（`MemoryActivity` / `MemoryScreen`）
  - 从主界面左上角齿轮进入，所有新增主功能都有开关；触发阈值、采样/冷却、回收比例、最小收益、单应用上限、高压倍数、提前停止阈值、前台压制比例/间隔等参数也都有滑条。
  - 显示实时 PSI、最近日志、提供「立即回收一次」「刷新状态」。
  - 关闭应用时立即把配置写回 JSON（进程级单线程写队列 + 原子替换），system_server 2 秒内热重载。
  - 配置解析失败时锁定写入并提示，避免一次误触把损坏配置覆盖成默认值。
- **刷入即用的 Magisk / KernelSU / APatch 模块**（`module/`）
  - `customize.sh`：刷入时自动 `pm install` 配套 APK；recovery 刷入则打标记。
  - `service.sh`：开机后自动补装 APK、修复配置目录权限。
  - `post-fs-data.sh`：提前创建 `/data/misc/CirnoRecycle` 并设置 system:system 0770。
  - `action.sh`：模块「操作」按钮打印状态（PSI / 冻结进程数 / 日志）并打开设置页。
  - `uninstall.sh` + `service.d` 清理脚本：删除模块后自动卸载配套 App。
- **构建 / 发布**
  - `scripts/build-release.ps1` 一键：单元测试 → Release APK → 模块 zip。
  - `scripts/package-module.ps1` / `package-module.sh`：自动校验 zip 结构和必带文件。
  - `signing/`：公开测试签名密钥，保证本地与 CI 构建签名一致、可覆盖升级。
  - GitHub Actions：push/PR 跑测试和 Debug 构建；打 `v*` tag 自动构建 Release 并上传模块 zip。
- **单元测试**：PSI 解析（含缺 full 行 / 垃圾数据）、压力分级、配置校正。

### 修复（相对上游 / 上一版改动）

- **前台压制原值持久化**：`memory.high` 的 ROM 原值原来只存在进程内静态字段里，system_server 软重启后会把自己的写入值当成 ROM 原值，导致关开关也恢复不回 `max`。现在原值/写入值持久化到 `/data/misc/CirnoRecycle/foreground_guard.orig`，软重启后仍能正确恢复；检测到值被外部改动时只清理状态、不覆盖别人的设置。
- **设置写入丢失**：设置页的持久化原来挂在 composition scope 上，点完开关立刻返回会取消写入；多次并发写还可能旧快照覆盖新快照。现在统一走进程级单线程写队列（`ConfigWriter`），并用「临时文件 + mv」原子替换 JSON。
- **配置损坏保护**：配置文件存在但解析失败时，system_server 侧保留旧配置；App 侧检测到损坏会锁定写入并要求用户确认，避免默认值整份覆盖。
- **主界面**：应用列表枚举在 IO 线程完成，`LazyColumn items()` 按需组合可见项、图标 `remember` 缓存；「已激活」改为三态（已激活 / 等待模块加载 / 未激活需要 root）。
- `ConfigManager.readConfig()` 每次读完都写回配置，配合 `ConfigFileObserver` 会形成「写 → 事件 → 读 → 再写」的 2 秒死循环；改为只读。
- `Log.fileLog` 写文件失败时递归调用 `Log.e`，在 App 进程没有 root 权限时会无限 post；改为只写 logcat，连续失败 3 次后暂停文件日志 60 秒再重试。`current.log` 超过 2MB 时轮转为 `last.log`。
- 内存回收日志：空转 / 没有实际动作时不再打印「内存回收完成」，避免持续写日志。
- `AppRecord.frozen`、`AppState` 的可见/音频/录音/定位/VPN 状态加 `volatile`，避免内存线程读到过期状态、误回收刚解冻的应用。
- `ApplicationSettings.whiteApps` 默认用 `Set.of(...)`（不可变），首次运行时加白名单会抛 `UnsupportedOperationException`；改为 `LinkedHashSet`。
- 应用详情页的白名单开关关闭时不会删除条目；已修复。
- `AppConfigs.isWhiteApp` 增加空判断，配置异步加载期间不再 NPE。
- `GlobalVars.globalSettings` / `applicationSettings` / `configCorrupted` 增加 `volatile`，避免配置线程与回收线程之间的可见性问题。
- 前台冷页压缩现在受「L2 进程压缩」总开关约束（之前关掉 L2 仍会调用 `compactApp`）。
- 构建脚本：`package-module.ps1/.sh` 拒绝把未签名 APK 打进模块；Linux 打包脚本兼容 macOS（不再依赖 GNU `sed -i` / `sha256sum`）；`build-release.ps1` 清洗 PATH 并设置 `ANDROID_USER_HOME`，避免测试 JVM 被本机异常 PATH 弄崩。

### 说明

- 本项目是 Cirno 的二次修改版本，**不是原创**；上游与本文均遵循 **GPL-3.0**。
- 免费发布，不会以任何方式强制收费。

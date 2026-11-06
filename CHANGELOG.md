# Changelog

本文件只记录 **Cirno Recycle+（二改版）** 相对上游 Cirno 的改动。上游自己的更新记录请见
[Freezer-Team/Cirno](https://github.com/Freezer-Team/Cirno)。

## v1.4.0 — 2026-11-06

### 新增

- **长时间冻结后的子进程深度回收**
  - 应用完整冻结超过设定时间（默认 30 分钟）后，可回收 `:appbrand` / `:xweb` / `:sandbox` / `:video` / `:reader` 等容器型子进程；游戏子进程默认不动，避免丢未保存进度；
  - 只处理已完整冻结、非可见、非音频 / 定位 / 录像 / VPN 的应用；
  - 主进程、推送通道（`:push` / `:MSF` / `:mipush` 等）、系统应用、白名单应用永远不动；
  - 默认只写 dry-run 日志，用户打开「实际执行 kill」后才会真正回收；
  - 每个应用最多 2 轮，之后退避 6 小时，避免 `START_STICKY` 服务反复拉起。
- **MemAvailable 水位兜底**
  - PSI 不可用（或 PSI 低估压力）时，按 `MemAvailable / MemTotal` 触发回收；
  - 触发水位默认 10%，带 3% 迟滞，回升到「水位 + 迟滞」才停止；
  - 与 PSI 分级取较大值，PSI 正常时仍以 PSI 为主。
- **严格的第三方 / 推送通道过滤**
  - 系统应用判断增加 `/system`、`/system_ext`、`/product`、`/vendor`、`/odm`、`/apex` 安装路径兜底；
  - 专门承担系统推送的包整体跳过内存回收；
  - `compactApp` 不再压缩推送通道进程；
  - 深度回收只接受容器型子进程白名单，未知进程一律跳过。

### 独立模块

- 新增 **Cirno ColorOS 17 系统更新禁用** 独立模块（`Cirno-ColorOS17-OTA-Disabler-v1.4.0.zip`）：
  - 通过 `pm disable-user` / `pm suspend` 禁用 ColorOS 已知 OTA 包；
  - 不改 system 分区，卸载模块自动恢复；
  - 与主模块相互独立，可单独刷入。

### 版本

- 正式版 1.4.0，APK `versionCode=12`，主模块 `versionCode=14003`，OTA 模块 `versionCode=14000`。

## v1.4.0-beta.2 — 2026-11-02（测试版）

### 修复

- **长进程名显示不全**
  - 进程列表不再 `maxLines=1 + Ellipsis`，允许完整换行；主进程额外显示完整包名；
  - 进程名用等宽字体，并支持长按选择复制，方便排查。
- **列表 / 进程面板卡顿**
  - 应用图标改为 IO 线程加载 + LruCache，主线程不再 `loadIcon()/toBitmap()`；
  - 应用列表先按 runtime 建一次索引，排序和状态徽章改为 O(1) 查找，移除比较器里的重复线性搜索；
  - `getInstalledApplications` 不再请求 meta-data；
  - 进程面板的 `PackageManager` 进程枚举移到 IO 线程。
- **滚动位置丢失**
  - 用 `SaveableStateHolder` 保留软件列表 / 设置 / 关于 / 应用详情页的滚动位置。

### 保留

- 配置导出 / 导入（SHA-256、导入前备份、失败回滚）
- Wallhaven / Picsum 在线图库
- 背景 SHA-256 校验与同路径换图刷新

### 版本

- 测试版本 1.4.0-beta.2，APK `versionCode=11`，模块 `versionCode=14002`。
- 标记为 **Pre-release（测试版）**；稳定版仍为 `v1.3.1`。

## v1.4.0-beta.1 — 2026-11-01（测试版）

这一版集中修掉 millet、运行时状态、背景换图和配置安全相关的问题，并补上配置备份 / 在线图库。

### 修复

- **millet 兼容加固**
  - 区分 `MILLET_PRESENT`（存在 millet 文件 / 属性 / 服务）与 `MILLET_ACTIVE`（内核模块 / Greeze 服务 / 属性确实在运行）；只有 `CONFLICT` 才自动禁用；
  - 备份失败直接中止，不修改属性；
  - 属性写入后逐项 `getprop` 校验，失败时回滚；
  - 恢复失败保留备份和禁用标记，不再删掉唯一能恢复原值的文件；
  - 恢复时按已知路径清理覆盖文件，不再只依赖可能丢失的 `millet_overlay.list`。
- **`AppService.get()` 首次调用返回 null**
  - `ConcurrentHashMap.put` 返回旧值，改为 `computeIfAbsent`；
  - `getByUid()` 不再把 `null` 缓存进 UID 列表。
- **自定义背景换图不刷新**
  - 背景文件保存路径固定，之前只把路径当 Compose key；现在同时记录 SHA-256，换图后哈希变化会触发重新解码；
  - 加载背景前校验 SHA-256，不匹配时拒绝显示并提示重新选择。
- **millet 操作结果**
  - 脚本用退出码区分成功 / 失败 / 部分成功；App 会显示「部分成功」而不是一律报失败。

### 新增

- **配置导出 / 导入**
  - 导出系统冻结 / 内存配置、界面偏好为单个 JSON；
  - 包内带 SHA-256；导入前校验，并自动把当前配置备份为 `*.bak-<时间戳>`；
  - 导入后重新解析校验，失败时尝试回滚。
- **真正的在线图库**
  - 设置页「在线图库」打开全屏选择器；
  - Wallhaven 支持关键词搜索、分页、作者 / 分辨率展示；Picsum 作为无 key 备用源；
  - 缩略图内存缓存，点击下载原图并应用。
- **本地 SHA-256 校验**
  - 背景图片导入时记录哈希，每次加载前校验；
  - 配置导出包带哈希，导入前校验；
  - 新增纯 JVM 单元测试。

### 版本

- 测试版本 1.4.0-beta.1，APK `versionCode=10`，模块 `versionCode=14001`。
- GitHub Release 标记为 **Pre-release（测试版）**；稳定版仍为 `v1.3.1`。

## v1.3.1 — 2026-10-31

小米 / HyperOS 的 millet（Greeze）兼容模式：刷入时自动检测、冲突时自动禁用，App 内可一键禁用 / 恢复。

### 新增

- **刷入自动检测 millet**
  - 识别小米 / Redmi / POCO / HyperOS；
  - 检测 `millet_core.ko`、已加载的 millet 内核模块、Greeze 服务 / millet 属性；
  - 检测到冲突时在安装日志打印检测结果，并自动启用兼容模式；
  - 如果已经安装了其他 millet 相关模块，自动处理会跳过，避免重复。
- **内置一键禁用 / 恢复**
  - 设置页「小米 millet 兼容」卡片显示：是否检测到、当前是否已禁用、内核模块是否加载、是否有备份；
  - 一键禁用：备份原始属性 → 关闭 `persist.sys.gz.enable` / `persist.sys.millet.handshake` / `persist.sys.powmillet.enable` → 在模块目录写空的 `millet_core.ko` 覆盖文件 → 写 `millet_disabled` 标记；
  - 恢复：按备份还原属性、删除覆盖文件和标记；
  - 都只改 persist 属性和模块目录，不写 system 分区。
- **开机继续处理**
  - `post-fs-data.sh` / `service.sh` 再应用一次属性，尝试卸载已加载的 `millet_core`，停掉 millet 相关 init 服务；
  - `millet_ctl.sh` 统一处理检测 / 禁用 / 恢复 / 开机应用，安装脚本、App、开机脚本共用一份逻辑；
  - 新增 `service.d` 守卫：模块被停用 / 删除时自动恢复 millet 属性。
- **App 状态与操作**
  - `MilletControl` 通过 root 调 `millet_ctl.sh status/disable/enable`；
  - 操作结果用 Toast + 卡片状态提示，需要重启才完全生效。

### 版本

- 开发版本 1.3.1，APK `versionCode=8`，模块 `versionCode=13001`。
- 已发布：GitHub Release `v1.3.1`。

## v1.3.0 — 开发版（未单独发布）

这一版主要改界面：底栏三页、顶部搜索、自定义背景和页面返回动画，并补上小米 millet 冲突提示。

### 新增

- **底栏三页**
  - 主界面改为「软件列表 / 设置 / 关于」三个底栏入口；
  - 顶部常驻搜索框（应用名 / 包名），模块名称、激活状态、版本号收进底栏；
  - 原来的整页设置（`MemoryActivity`）改为底栏「设置」页，左上空出来的位置不再放齿轮。
- **应用详情页内嵌 + 返回动画**
  - 点击应用不再启动 `ApplicationActivity`，改为 `MainActivity` 内部页面；
  - 三页切换和详情页进入 / 返回统一使用 `AnimatedContent` 滑动 + 淡入淡出；
  - 返回键 / 系统返回手势先回详情页，再回软件列表，不会直接退出。
- **自定义背景**
  - 支持本地图片（系统图片选择器）、http/https 图片链接、6 张在线示例图、4 套内置渐变；
  - 图片按最长边 2560 缩放后复制到应用私有目录，不依赖 SAF 临时授权，也不申请存储权限；
  - 不透明度 / 背景压暗两个滑条，拖动时实时预览，松手才落盘；
  - 背景绘制在 `Scaffold` 下方，主题在开启背景时把 `background` / `surface` / `surfaceContainer*` 调成半透明，列表、卡片、底栏、弹窗都会透出背景。
- **关于页**
  - 版本 / 作者 / 激活状态 / GitHub / 下载 / 反馈 / 上游链接；
  - 小米 millet 兼容提示、Google 服务说明、白名单说明、开源致谢。
- **小米兼容提示**
  - 设置页和关于页检测到小米 / Redmi / POCO 时提示 `millet_disable` 或关闭系统冻结，避免系统 millet 与本模块互相抢进程导致功耗升高。

### 修复 / 改进

- 应用列表搜索框从右上角按钮展开改为顶栏常驻，空查询不再需要手动开关。
- 底栏激活状态和首页共用同一份 `RuntimeState` 轮询；打开详情页后停止主页面轮询，避免同时开多个 root shell。
- 背景图片读取、缩放、网络下载全部放 IO 线程；在线缩略图按需加载，失败显示占位块。
- Manifest 打开 `enableOnBackInvokedCallback`，Android 13+ 的系统预测性返回手势可以接管。

### 版本

- 开发版本 1.3.0，APK `versionCode=7`，模块 `versionCode=13000`。
- **尚未发布**：`update.json` 仍指向 v1.2.3，避免把开发版推给已安装用户。
- 本地验证：`assembleRelease` 通过；23 个单元测试全部通过。

## v1.2.3 — 2026-10-31

一个小版本，把白名单语义改成「绝对保护」，并修复配置热重载和 Google 服务允许冻结。

### 修复

- **白名单改为绝对保护**
  - 以前白名单只保证主进程不冻；如果进程规则是「只冻选中 / 排除选中」，子进程仍可能被自动或手动冻结。
    悬浮窗 / 服务进程跑在 `:xxx` 子进程里时，表现就是「正常几秒后悬浮窗不动」。
  - v1.2.3 起，只要应用在白名单里：
    - 自动冻结直接跳过，不再看 `processMode`；
    - 所有子进程也不允许自动或手动冻结；
    - `reconcile` 会把所有已冻结进程（包括手动冻结）解冻；
    - Binder / 广播 / 服务唤醒只负责解冻，不再重新安排冻结。
  - UI 同步：白名单开启时进程规则置灰，进程列表不再允许勾选 / 手动冻结。
- **配置热重载 mtime 兜底**
  - `ConfigFileObserver` 在某些 ROM 或 root shell 写文件时可能漏事件，导致白名单 / Google 允许冻结改完不生效。
  - `RuntimeStateService` 每 2 秒检查 `GlobalSettings.json` / `ApplicationSettings.json` 的 mtime，变化就重新加载。
- **Google 服务允许冻结后真正生效**
  - 白名单和 Google 允许冻结都依赖配置重载；mtime 兜底后 `allowFreezeGoogle` 能及时被 system_server 读到。
  - 用户显式允许冻结 Google 服务时，跳过定位 / 录音 / VPN 保护（仍然不允许冻结前台应用），
    避免 GMS 因为常驻定位监听而永远冻不上。

### 版本

- 版本 1.2.2 → 1.2.3，APK `versionCode=6`，模块 `versionCode=12003`。

## v1.2.2 — 2026-10-31

修复 v1.2.1 的闪退，并补上「不使用 FCM 时也能自己选择冻结 Google 全家桶」的开关。

### 修复

- **修复一打开应用列表就闪退（v1.2.1 回归）**
  - 根因：`CommonConstants.whiteApps` 用 `Set.of(...)` 初始化，里面把
    `app.revanced.android.gms` 写了两遍。Java 的 `Set.of` 遇到重复元素会直接抛
    `IllegalArgumentException: duplicate element`，导致 `CommonConstants` 类初始化失败
    （`ExceptionInInitializerError`）。
  - App 进程加载应用列表 / 判断白名单时触发这个类，就会闪退；system_server 侧第一次
    判断 `AppRecord.isProtected()` 时同样会崩。
  - 改为 `LinkedHashSet` + `Collections.unmodifiableSet` 构造白名单：重复项自动去重，
    以后即使再手滑写重复也不会在类初始化阶段崩溃；顺带给 `isWhitelistApps(null)` 补了空判断。
  - 新增 `CommonConstantsTest` 回归测试，直接触发类加载并覆盖 Google 集合 /
    null 包名。
- **Hook 回调兜底**
  - `ServiceStartHook`、`BroadcastIntentHook`、`BroadcastSkipHook` 的回调整体包了
    `try/catch (Throwable)`：任何反射 / 配置异常都只跳过本次处理，不再把异常抛回
    system_server 导致系统进程崩溃。

### 新增：Google 全家桶可单独选择冻结

- Google 服务（GMS / GSF / Play 商店 / Google 配置更新 / ext.services / ext.shared /
  Google App / Pixel 天气 / SafetyCore / MicroG 等）默认仍然受保护，保证 FCM 推送可用；
- 这些应用现在**始终显示在首页应用列表**，名字前带 `(Google 保护)` 标记；
- 打开某个 Google 应用后，新增开关「允许冻结 Google 服务」：
  - 默认关闭；
  - 打开后解除保护并同时打开「参与自动冻结」，之后按正常冻结流程处理（不需要再单独点一次）；
  - 不使用 FCM / 不用谷歌推送的用户可以逐个放行；使用 FCM 的用户保持默认即可；
  - 关闭这个开关时会恢复保护并主动解冻一次，避免应用卡在冻结状态；
- 新增 `AppOptions.allowFreezeGoogle`（默认 `false`，旧配置缺省也是 `false`）。

### 测试

- 单元测试增加到 **23 个**，全部通过：`CommonConstantsTest` 2 个、
  `AppOptionsLogicTest` 8 个、`UpdateLogicTest` 3 个、`MemoryLogicTest` 6 个、
  `PushUtilsTest` 3 个、`ExampleUnitTest` 1 个；
- `assembleDebug` / `assembleRelease` 通过。

### 版本

- 版本 1.2.1 → 1.2.2，APK `versionCode=5`，模块 `versionCode=12002`。

## v1.2.1 — 2026-10-31

修复「安装模块后 FCM / 厂商推送收不到」的问题。推送链路本身没问题，是冻结逻辑把
「GMS → 推送广播 → 目标应用」这条路挡住了：目标应用被冻结时广播被跳过 / 投递 Binder 排队，
应用刚被推送拉起又被 3 秒后的冻结计时器冻回去。

### 修复

- **Google 推送基础设施默认保护**
  - `com.google.android.gms`（FCM 长连接）、`com.google.android.gsf`、`com.android.vending`、
    `com.google.android.configupdater`、`com.google.android.ext.services`、
    `com.google.android.ext.shared` 以及 MicroG 相关包加入内置保护名单；
  - 这些包无论是不是系统应用都不会被冻结 / 回收。之前如果 GMS 是作为普通 APK 装的（国内很常见），
    会被当成普通后台应用冻结，FCM 长连接直接被切断。
- **推送广播真正先解冻**
  - `BroadcastIntentHook` 不再用硬编码参数下标找 `Intent`，改成遍历参数按类型找；
    部分 OEM（ColorOS 16/17 等）改了 `broadcastIntentLocked` 参数布局，旧实现会找不到 Intent，
    解冻完全失效；
  - 额外兜底 hook `ActivityManagerService.broadcastIntentWithFeature` / `broadcastIntent` /
    `broadcastIntentWithCallback` 等 binder 入口，防止 ROM 把内部方法换类 / 改名；
  - 改为按包名解冻（不再依赖 userId 参数），并覆盖 FCM、UnifiedPush、魅族 / 华为 / 小米 /
    vivo / OPPO 厂商推送 action（原代码里 UnifiedPush / 魅族推送因为条件写反，实际上不会被解冻）。
- **推送广播不再被跳过**
  - `BroadcastSkipHook` 适配 AOSP 14+/16 的 `shouldSkipMessage(BroadcastRecord, Object)` 签名
    （原实现只匹配 `(BroadcastRecord, BroadcastFilter)`，在 AOSP 16 上其实没装上）；
  - 推送类广播**永不跳过**，并在决定投递前再解冻一次（双保险）；
  - 非推送广播仍然跳过冻结进程，保持原来的省电行为。
- **推送投递窗口（15 秒）**
  - 收到推送后按包名记录一个 15 秒的免冻结窗口：即使进程是刚被推送拉起的、还没来得及创建
    AppRecord，`ProcessAddHook` 3 秒后的冻结也会被挡住，应用有足够时间处理广播、启动 service、
    拉取消息正文；窗口结束后恢复冻结。
- **服务启动 / 绑定唤醒**
  - 新增 `ServiceStartHook`：FCM 在部分 GMS 版本上通过 `startService` / `bindService` 投递，
    冻结应用会先被解冻 15 秒，服务能正常启动。
- **「冻结时保留网络连接」在 Android 16 生效**
  - 原实现只在 Android 15 及以下跳过 `socketDestroy`；Android 16 走
    `reportOneUidFrozenStateChanged`，系统的冻结网络策略仍会断网。现在 `keepNetwork` 打开时
    不再上报 uid frozen，真正保留网络。
- **新增单应用开关「收到推送时解冻」**（`AppOptions.wakeOnPush`，默认开）
  - 应用面板里可以关闭；关闭后推送广播仍会投递，但不会额外解冻 15 秒。
- 版本 1.2.0 → 1.2.1，APK `versionCode=4`，模块 `versionCode=12001`。

### 测试

- `AppOptionsLogicTest` 增加 `wakeOnPush` 默认值 / 旧 JSON 兼容 / 序列化往返断言；
- 全部 21 个单元测试通过；`assembleDebug` / `assembleRelease` 通过。

## v1.2.0 — 2026-10-31

在 v1.1.0 的基础上补齐「日常使用顺手」的部分：应用内更新提示、深色模式 / 自定义主题色 / 莫奈取色、
首页搜索与后台优先排序、完整进程列表，以及白名单应用的部分冻结。

### 新增

- **应用内更新提示**
  - 启动时读取仓库根目录的 `update.json`（`raw.githubusercontent.com` 失败时自动尝试 jsDelivr 镜像），
    比较版本名（例如 `1.2.0`）判断是否有新版（APK 的 `versionCode` 是小整数，不能和模块的
    `versionCode=12000` 直接比较）；12 小时内最多检查一次，失败也静默处理，不打扰使用；
  - 发现新版本弹窗显示版本号和更新日志（截取 CHANGELOG 前 1200 字），「去下载」优先打开 APK 直链，
    没有 APK 时回退到 Release 页面；
  - 设置页「通用设置」里可以关闭自动检查，或点「立即检查」手动查看结果。
- **深色模式**
  - 主题模式：跟随系统 / 强制浅色 / 强制深色；状态栏 / 导航栏图标颜色跟着切换，不会出现深色底配深色图标；
  - 新增 `values-night` 窗口底色，系统深色模式下启动不再闪白。
- **主题色 / 莫奈取色**
  - 6 个预设主题色（雪蓝 / 冰青 / 淡紫 / 樱粉 / 暖橙 / 草绿）+ 自定义 `#RRGGBB`，改动立即生效；
  - Android 12+ 支持「莫奈取色」（`dynamicLight/DarkColorScheme`），从系统壁纸取色；
  - 自定义配色由种子色实时生成（不引入第三方取色库），覆盖 primary / secondary / tertiary / surface 系列。
- **首页搜索**
  - 右上角新增搜索按钮，按应用名或包名实时过滤；应用名在 IO 线程预加载，输入时不再调用 `loadLabel`。
- **后台应用优先排序**
  - 首页把「有存活进程且不在前台」的应用排到最前面，并按进程数从多到少排序，其余应用按白名单 / 名称排序；
  - 设置里可以关闭这个行为，回到「白名单优先 + 名称」的旧排序。
- **完整进程列表**
  - 应用面板的进程列表现在合并 `PackageManager` 里声明的进程（四大组件的 `android:process`）和当前运行中的进程：
    没启动的子进程也会列出来，可以提前勾选进程规则；运行中的进程显示 pid / 冻结状态，未运行的显示「未运行」；
  - 设置里可以关闭「显示未运行进程」，只看当前运行的。
- **白名单应用的部分冻结**
  - 白名单应用仍然整应用不参与自动冻结、不参与内存回收，但：
    - 可以手动冻结它的子进程（主进程不允许冻，避免白名单跳过 Binder 临时解冻后应用无响应）；
    - 把进程规则设为「只冻选中 / 排除选中」后，子进程会按规则自动部分冻结；
  - 白名单应用的部分冻结**不会**向系统上报「整个 uid 冻结」，也不会断网 / 移除闹钟；
  - 手动冻结的进程会标记为「已冻结(手动)」，状态文件新增 `manuallyFrozen` 字段。

### 变更

- 应用设置面板的「白名单」说明改为「整应用不冻结、不参与内存回收；仍然可以冻结它的子进程」。
- 设置页标题改为「设置」，新增「外观 / 主题」和「通用设置」两张卡；页脚版本号改为读取 `BuildConfig`。
- `AppRecord.isSystem()` 拆分为 `isProtected()`（系统应用 / 输入法 / 内置保护应用）和 `isWhiteListed()`：
  冻结逻辑只对 `isProtected()` 完全跳过，白名单应用可以做进程级部分冻结。
- 版本号 1.1.0 → 1.2.0，APK `versionCode=3`，模块 `versionCode=12000`；模块 `module.prop` 新增 `updateJson`，
  Magisk / KernelSU 的模块更新也可以读到 `update.json`。

### 修复

- **白名单应用完全无法部分冻结**：原来 `isSystem()` 把白名单也算进去，自动冻结 / 手动冻结 / 手动解冻全部跳过；
  现在白名单只跳过「整应用冻结」，进程级冻结可用，同时保留 `temporaryUnfreezeIfNeed` 的 Binder 唤醒解冻，
  避免冻住子进程后应用卡死。
- **白名单应用的自动冻结残留**：`reconcile` 对「白名单 + 全部进程」只解冻自动冻结的进程，保留用户手动冻结的；
  新增 `ProcessRecord.manuallyFrozen` 区分两种冻结来源。
- **改完配置不切后台就不生效**（例如刚从白名单移除 / 刚打开自动冻结 / 刚改进程规则）：
  `reconcile` 检测到「应用已在后台且按规则有该冻的进程」时补发一次冻结调度（每应用 30 秒最多一次），
  不用等下一次「进入后台」事件。
- **进程列表只有运行中的进程**：现在从 `PackageManager` 枚举全部已声明进程（含未运行）；
  运行中的进程还会补进来，防止极端情况下状态文件缺项。
- **应用面板状态刷新**：进程行按「运行中优先 / 主进程优先 / 名称」排序，手动冻结显示「已冻结(手动)」。
- 更新检查的版本比较支持 `v` 前缀、缺段补零、后缀忽略（`1.2.0-beta` == `1.2.0`），并新增单元测试。
- 进程名规范化处理 `android:process=":push"` 这种私有进程写法（补全成 `包名:push`），并新增单元测试。

### 测试

- 新增 `UpdateLogicTest`：版本名比较（v 前缀 / 位数不同 / 后缀）、私有进程名规范化；
- 原有进程规则 / PSI / 配置校正单元测试全部通过（共 18 个）。

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

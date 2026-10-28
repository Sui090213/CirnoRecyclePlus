<div align="center" class="percent">
   <img width="160" src="cirno.png" alt="logo"></br>
   <img width="95" src="cirno.svg" alt="title">
</div>

# Cirno Recycle+

> 基于 [Freezer-Team/Cirno](https://github.com/Freezer-Team/Cirno)（原 [Nep-Timeline/Cirno](https://github.com/Nep-Timeline/Cirno)）**二次修改**
> 作者：**flyingr** · 版本：**1.0.0** · 协议：**GPL-3.0**

**先说明：这是二改，不是原作者的作品。** 原项目「Cirno」的版权归 Nep-Timeline / Freezer-Team 所有，本仓库只是在其基础上加了内存回收和前台内存压制功能，并保持同样的 GPL-3.0 开源协议。

---

## 一句话介绍

Cirno 负责「把后台应用冻住省 CPU」，Cirno Recycle+ 在它基础上多做两件事：**把冻住的应用占的内存收回来**，以及**在你允许的范围里压一压前台应用的内存增长**。所有新加的功能都能在 App 里用开关控制。

## 它解决什么问题

- 应用被 `cgroup.freeze` 冻结之后**不再运行，但 RSS / PSS 还占着**，系统内存该紧张还是紧张。
- 冻结的进程**收不到 `onTrimMemory`、也不会自己 GC**，靠应用自己释放内存这条路走不通。
- 前台应用内存一路涨，系统只能去杀后台（包括你刚冻结的应用），**冻了个寂寞**。

所以这个项目改成从内核侧解决：直接通过 cgroup v2 回收页、通过系统的进程压缩接口把冷页标记成可回收页，冻结进程不需要解冻也能生效。

## 相对原版 Cirno 新增了什么

| # | 功能 | 说明 | 有 UI 开关 |
|---|---|---|---|
| 1 | **L0 `memory.reclaim`** | 对后台应用 cgroup 写 `memory.reclaim`，直接回收页缓存 / 匿名页（Linux 5.19+） | ✅ |
| 2 | **PSI 智能触发** | 读 `/proc/pressure/memory`，`some` / `full` 取大者分级，带冷却、提前停止、高压多轮 | ✅ |
| 3 | **只回收已冻结应用** | 默认只动冻结了的进程，没冻结的（可能马上切回前台）不碰 | ✅ |
| 4 | **swap / zram 换出** | 向 `memory.reclaim` 写 `N swap`，把匿名页换到 zram；没 swap 自动回退普通回收 | ✅ |
| 5 | **L2 `compactApp`** | 复用系统 `CachedAppOptimizer` 做进程内存压缩（`MADV_COLD` / `MADV_PAGEOUT`） | ✅ |
| 6 | **前台内存压制（实验性）** | 给 top-app cgroup 写 `memory.high` 软上限，前台应用内存失控时先限流而不是整机 OOM；ROM 原值会持久化保存、关开关时恢复 | ✅ |
| 7 | **前台冷页压缩（实验性）** | 内存有压力时对可见应用调用 `compactApp`，压制前台进程常驻内存 | ✅ |
| 8 | **前台组回收（实验性）** | 直接回收 `foreground` cgroup（可见但没在交互的应用），不含当前 top-app | ✅ |
| 9 | **仅息屏时回收** | 亮屏玩手机时不回收，优先保证手感 | ✅ |
| 10 | **立即回收一次** | 无视 PSI 阈值和冷却，手动强制跑一轮，用来验证效果 | ✅ |
| 11 | **配置热重载** | 改完开关写回 JSON，system_server 2 秒内生效，不用重启 | ✅ |
| 12 | **刷入自动装 App** | 刷模块 zip 时自动安装配套 APK；recovery 刷入则开机后自动补装 | — |

> 关于「GitHub 上能压制前台进程内存的模块」：我确实去找了。结论是**没有找到现成的、公开的、专门做 top-app `memory.high` 的 Magisk / LSPosed 模块**（GitHub 搜 `memory.high` 基本都是无关项目）。所以第 6、7、8 项是照着 AOSP 文档和几个内存优化项目的思路自己写的实现，不是直接搬的代码。没有引入 `process_madvise` JNI、没有改 lmkd 参数、没有改 zram 配置——这些要么需要 root shell / `CAP_SYS_NICE`，要么容易把设备调崩，不适合放进 LSPosed 模块里，所以没做。

## 安装

**前提**

- Android 12+（API 31+），内核 5.10+（cgroup v2）
- Magisk / KernelSU / APatch 任选其一
- **LSPosed**（Zygisk 版，API 101+）——没有 LSPosed 这个模块不会生效

**步骤**

1. 从 Release 页面下载 `Cirno-Recycle-Plus-v1.0.0.zip`。
2. 在 Magisk / KernelSU / APatch 里刷入（**在已开机的系统里刷**，不要用 recovery）：
   - 刷入时会**自动安装配套 APK**（`com.flyingr.cirnorecycle`），并创建配置目录 `/data/misc/CirnoRecycle`；
   - 如果安装时 PackageManager 还没就绪、导致 APK 安装失败，模块会在**下次开机后自动补装一次**。
3. 重启手机。
4. 打开 **LSPosed** → **模块** → 启用 **Cirno Recycle+**。
   - 作用域已经在 APK 里静态声明为「系统框架（system）」，不需要手动勾选。
5. 打开 **Cirno Recycle+** App → 左上角齿轮 → 按需开开关。

> 升级时直接刷入新 zip 覆盖即可，配置会保留。

## 设置项说明

配置存在 `/data/misc/CirnoRecycle/GlobalSettings.json` 的 `memory` 字段里，App 里改完立即写回，system_server 热重载。全部字段如下（也能手动改 JSON，改完 2 秒内生效）：

| 字段 | 默认值 | 界面开关 | 说明 |
|---|---|---|---|
| `enabled` | `true` | 启用内存回收 | 总开关，关了所有回收 / 前台压制都停 |
| `dryRun` | `false` | 只打印计划 | 只写日志不真回收，先用来确认候选应用 |
| `enableReclaim` | `true` | L0 · memory.reclaim | 是否写 cgroup `memory.reclaim` |
| `useSwapReclaim` | `false` | 允许换出到 swap/zram | 写 `N swap`，内核不支持会自动回退 |
| `enableCompact` | `true` | L2 · compactApp | 是否调系统进程压缩 |
| `frozenOnly` | `true` | 只回收已冻结应用 | 关闭后未冻结的后台应用也会被回收 |
| `screenOffOnly` | `false` | 仅息屏时回收 | 息屏才触发 |
| `sampleIntervalMs` | `5000` | 采样间隔滑条 | 2–30 秒 |
| `psiSomeThreshold` | `5.0` | 触发阈值滑条 | PSI some/full avg10 百分比，1–30 |
| `psiHighMultiplier` | `2.0` | 高压判定倍数滑条 | 1–6 |
| `psiStopThreshold` | `1.0` | 提前停止阈值滑条 | 0–10，回收中途压力降到这以下就停 |
| `cooldownMs` | `15000` | 冷却时间滑条 | 两次回收周期最小间隔 |
| `maxRoundsPerCycle` | `2` | 高压连续回收轮数滑条 | 1–4 |
| `maxAppsPerRound` | `4` | 每轮最多回收应用数滑条 | 1–8 |
| `minCgroupBytes` | `32MB` | 最小回收收益滑条 | 8–256MB |
| `maxBytesPerApp` | `128MB` | 单应用单次上限滑条 | 32–512MB |
| `reclaimRatio` | `0.10` | 单应用回收比例滑条 | 5%–50% |
| `foregroundGuard` | `false` | top-app memory.high 软上限 | 前台压制，实验性 |
| `foregroundHighRatio` | `0.80` | 软上限占物理内存滑条 | 50%–95% |
| `foregroundColdCompact` | `false` | 前台冷页压缩 | 对可见应用做 compact，实验性（需要 L2 开启） |
| `foregroundColdIntervalMs` | `30000` | 前台冷页压缩间隔滑条 | 10–120 秒 |
| `foregroundReclaim` | `false` | 前台组直接回收 | 回收 foreground cgroup，实验性 |
| `requestSeq` | `0` | 立即回收一次按钮 | App 里点按钮写时间戳，system_server 收到后强制跑一轮 |

**建议**：先保持默认（只回收冻结应用 + L0/L2），在 App 里看几天日志；确认稳定后再动阈值，或者开 `dryRun` 看候选是否符合预期。前台那几项是实验性的，卡顿就关掉。

## 日志在哪

- App 内：设置页「运行状态」卡片，直接看 PSI 和最近日志。
- 文件：`/data/misc/CirnoRecycle/log/current.log`
- logcat：`adb logcat -s CirnoRecycle`

```bash
adb shell 'tail -f /data/misc/CirnoRecycle/log/current.log'
```

日志里正常能看到：

```
内存回收模块已启动, cgroup v2 = true, memory.reclaim = true, PSI = true, top-app memory.high = false
内存压力变化: PSI(memory) some avg10=12.30% full avg10=3.75% -> 中等
内存回收 com.example.app: 210.4MB -> 154.8MB (请求 21.0MB, 实际 55.6MB)
内存回收完成: 压力=中等, 应用数=2, 共回收 72.1MB, 压缩进程数=3
前台冷页压缩 com.example.app: 2 个进程 (压力=中等)
```

（`内存回收完成` 只在本轮真的有动作时才打印；前台冷页压缩需要手动打开对应开关。）

## 工作原理（简版）

```
Memory HandlerThread（后台优先级）每 sampleIntervalMs 跑一次
        │
        ▼
读 /proc/pressure/memory ── 低于阈值 ──► 空转（前台压制开关仍然维持 memory.high）
        │ 达到阈值
        ▼
从 Cirno 的 AppRecord 里挑候选（系统/白名单/可见/音频/录音/定位/VPN 全部排除；默认只挑已冻结）
        │
        ▼
按 cgroup memory.current 从大到小
        ├── L0：写 memory.reclaim（可选 swap）→ 读回收前后差值
        └── L2：compactApp → MADV_COLD / MADV_PAGEOUT
        │
        ▼
重新读 PSI，压力降下来就提前停；高压时按 maxRoundsPerCycle 再来一遍
```

更详细的设计、内核接口、失败降级逻辑见 [MEMORY_MODULE.md](MEMORY_MODULE.md)。

## 自己构建

本仓库构建需要 **JDK 25 + Android SDK 36**（compileSdk/targetSdk 36，Java source/target 25）。

```bash
# 只构建 APK
./gradlew assembleRelease

# 一键构建 APK + 跑单元测试 + 打包模块 zip（Windows）
powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1

# 一键构建（Linux / macOS / CI；macOS 自带 shasum，脚本已做兼容）
bash scripts/package-module.sh
```

产物：

- APK：`app/build/outputs/apk/release/app-release.apk`（仓库自带公开测试签名，见 [signing/README.md](signing/README.md)）
- 模块 zip：`dist/Cirno-Recycle-Plus-v1.0.0.zip`
- 校验值：`dist/CHECKSUMS.txt`

## 目录结构

```
app/                        Android 应用（LSPosed 模块主体）
  src/main/java/.../memory/ 内存回收 / 前台压制实现
  src/main/java/.../screen/MemoryScreen.kt  设置界面
  src/test/java/.../memory/ 纯 JVM 单元测试
module/                     刷入用的 Magisk/KernelSU/APatch 模块骨架
  customize.sh              刷入时自动安装 APK
  service.sh                开机兜底安装 / 修配置目录权限
  action.sh                 模块「操作」按钮：打印状态 + 打开设置
scripts/                    一键构建 / 打包脚本
signing/                    公开测试签名密钥
dist/                       打包产物（git 忽略）
```

## 常见问题

**Q：刷完重启了，但没有任何日志 / 内存回收没效果？**
A：先确认 LSPosed 里已经启用本模块并重启过；再看 App 设置页的日志和 PSI 是否正常。没有 LSPosed 时模块代码根本不会加载。

**Q：日志里提示「当前内核没有 top-app/foreground 的 memory.high 接口」？**
A：正常。不同 ROM 的 cgroup 布局不一样，前台压制是「探测到才启用」，探测不到就自动跳过，不影响冻结和内存回收。

**Q：L0 日志显示「无收益」（`memory.reclaim` 写入失败）？**
A：常见于内核版本低于 5.19、该 cgroup 目录没有 `memory.reclaim`、或者 cgroup 里确实已经没东西可回收。会自动降级走 L2 进程压缩。

**Q：会不会把后台应用杀掉？**
A：不会。本项目只回收内存页 / 压缩进程，不杀进程、不发 SIGKILL。杀进程是系统 LMKD 的事。

**Q：和原版 Cirno 冲突吗？**
A：包名不同（`com.flyingr.cirnorecycle`），可以共存；但两个冻结器同时开会互相抢 cgroup 状态，**建议只留一个**。

**Q：刷入时提示 APK 安装失败 / 重启后手机上找不到 App？**
A：先看 `/data/adb/modules/cirno_recycle_plus/install.log`。如果模块目录里还有 `apk/CirnoRecyclePlus.apk`，可以直接用 root 文件管理器手动安装；如果安装成功后 `apk/` 已被清理，**重新刷一次模块 zip** 即可重新安装。

**Q：刷入模块时提示「没有检测到 LSPosed」？**
A：这只是提示。LSPosed 必须在刷入前就装好（Zygisk 版 + 已启用），否则模块代码不会加载。装好 LSPosed 后重新刷入模块并重启即可。

**Q：能卸载吗？**
A：在 root 管理器里删除模块并重启即可。支持 `/data/adb/service.d` 的 root 方案（Magisk、较新的 KernelSU/APatch）会通过清理脚本自动卸载配套 App；如果你的环境没有执行该脚本，重启后 App 可能仍在，手动卸载 `com.flyingr.cirnorecycle` 即可。配置目录默认保留，彻底清理请手动 `rm -rf /data/misc/CirnoRecycle`。

## 二改声明 & 版权

- 本项目是 **[Freezer-Team/Cirno](https://github.com/Freezer-Team/Cirno)**（原 Nep-Timeline/Cirno）的二次修改版本，**不是原创项目**。
- 上游协议为 **GPL-3.0**，本项目同样以 **GPL-3.0** 发布，完整协议见 [LICENSE](LICENSE)。分发二进制时请保留本仓库源码与协议声明。
- 本次二改的主要修改点：新增内存回收子系统（`app/src/main/java/nep/timeline/cirno/memory/`）、设置界面（`MemoryScreen` / `MemoryActivity`）、刷入自动安装 APK 的 Magisk 模块（`module/`）、构建 / 打包脚本（`scripts/`），以及若干稳定性修复（配置热重载死循环、日志递归、白名单集合不可变等）。详细改动见 [CHANGELOG.md](CHANGELOG.md)。
- 本项目**免费**，不会以任何方式强制收费；二次分发请同样保持开源。

## 致谢

- [Nep-Timeline](https://github.com/Nep-Timeline) / [Freezer-Team](https://github.com/Freezer-Team) —— Cirno 原作者
- [libxposed](https://github.com/libxposed) —— LSPosed 模块 API
- [topjohnwu/libsu](https://github.com/topjohnwu/libsu) —— root 权限读写
- AOSP 文档：[Memory Limiter](https://source.android.com/docs/core/perf/memory-limiter)、[cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)、[LMKD](https://source.android.com/docs/core/perf/lmkd)
- 设计思路参考（未复制代码）：[A1Memory](https://github.com/OneB1ank/A1Memory)（GPL-3.0）、[Frosty](https://github.com/Drsexo/Frosty)（GPL-3.0）、[MemKeep](https://github.com/qvq9999/MemKeep)（MIT）、[memadvise](https://github.com/Zouuup/memadvise)（MIT）、[Adkimsm/Cirno](https://github.com/Adkimsm/Cirno)（GPL-3.0）

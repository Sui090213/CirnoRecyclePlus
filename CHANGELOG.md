# Changelog

本文件只记录 **Cirno Recycle+（二改版）** 相对上游 Cirno 的改动。上游自己的更新记录请见
[Freezer-Team/Cirno](https://github.com/Freezer-Team/Cirno)。

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
  - 从主界面左上角齿轮进入，所有新增功能都有开关 / 滑条。
  - 显示实时 PSI、最近日志、提供「立即回收一次」「刷新状态」。
  - 关闭应用时立即把配置写回 JSON，system_server 2 秒内热重载。
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

- `ConfigManager.readConfig()` 每次读完都写回配置，配合 `ConfigFileObserver` 会形成「写 → 事件 → 读 → 再写」的 2 秒死循环；改为只读。
- 读取配置失败（写文件未完成被读到）时原逻辑会把用户配置重置为默认值并覆盖；改为保留旧配置、等下一次事件重试。
- `Log.fileLog` 写文件失败时递归调用 `Log.e`，在 App 进程没有 root 权限时会无限 post；改为只写 logcat，连续失败 3 次后关闭文件日志。
- `ApplicationSettings.whiteApps` 默认用 `Set.of(...)`（不可变），首次运行时加白名单会抛 `UnsupportedOperationException`；改为 `LinkedHashSet`。
- 应用详情页的白名单开关关闭时不会删除条目；已修复。
- 主界面在 Compose 组合阶段同步读 root 配置，可能卡主线程；改为 `LaunchedEffect` + IO 线程。
- `GlobalVars.globalSettings` / `applicationSettings` 增加 `volatile`，避免配置线程与回收线程之间的可见性问题。

### 说明

- 本项目是 Cirno 的二次修改版本，**不是原创**；上游与本文均遵循 **GPL-3.0**。
- 免费发布，不会以任何方式强制收费。

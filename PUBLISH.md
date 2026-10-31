# 发布到 GitHub 的步骤

仓库已经初始化好了（`git init -b main`，首个提交已完成）。下面是把 **Cirno Recycle+** 开源到
GitHub 并发布第一个 Release 的完整流程。所有命令都在仓库根目录执行。

## 0. 先确认本地仓库状态

```powershell
cd D:\deepseek\projects\CirnoRecyclePlus
D:\deepseek\tools\git\cmd\git.exe status
D:\deepseek\tools\git\cmd\git.exe log --oneline
```

## 1. 在 GitHub 创建空仓库

在 GitHub 网页上新建一个空仓库，例如 `CirnoRecyclePlus`：

- **不要**勾选 “Add a README / .gitignore / license”，本地已经有这些文件了；
- 仓库可以是 Public，协议选 GPL-3.0（本地已有 `LICENSE`）。

## 2. 关联远程并推送

```powershell
$git = 'D:\deepseek\tools\git\cmd\git.exe'
& $git remote add origin https://github.com/<你的用户名>/CirnoRecyclePlus.git
& $git push -u origin main
```

如果开了两步验证，密码位置要填 **Personal Access Token**（GitHub → Settings → Developer settings
→ Personal access tokens → Fine-grained tokens，勾选该仓库的 `Contents: Read and write`）。

## 3. 打 tag 并发布 v1.2.1

```powershell
& $git tag -a v1.2.1 -m "Cirno Recycle+ v1.2.1"
& $git push origin main
& $git push origin v1.2.1
```

推送 tag 后，`.github/workflows/release.yml` 会自动：

1. 用 JDK 25 + Android SDK 36 构建 Release APK（仓库自带公开签名密钥，自动签名）；
2. 跑 `scripts/package-module.sh` 打包成 `dist/Cirno-Recycle-Plus-v1.2.1.zip`；
3. 创建 GitHub Release，上传模块 zip 和 `CHECKSUMS.txt`。

> 如果不想让 Actions 自动建 Release（例如想手动上传 APK / 源码包），可以先不推 tag，
> 在网页上手动建 Release 并上传；`release.yml` 只在推 `v*` tag 时触发。

第一次跑 Actions 大约需要 8–15 分钟（要下载 Gradle 依赖）。在仓库的 **Actions** 页面可以看进度。

## 4. 模块更新提示 / 应用内更新提示（`update.json`）

仓库根目录的 `update.json` 同时供两处使用：

- Magisk / KernelSU 读它显示模块更新（`module/module.prop` 里的 `updateJson=`）；
- App 启动时读它检查新版（`UpdateChecker.kt`，GitHub raw 失败时自动尝试 jsDelivr 镜像）。

当前内容（版本升级时同步修改 `version` / `versionCode` / 三个链接）：

```json
{
  "version": "v1.2.1",
  "versionCode": 12001,
  "zipUrl": "https://github.com/<你的用户名>/CirnoRecyclePlus/releases/download/v1.2.1/Cirno-Recycle-Plus-v1.2.1.zip",
  "apkUrl": "https://github.com/<你的用户名>/CirnoRecyclePlus/releases/download/v1.2.1/Cirno-Recycle-Plus-v1.2.1.apk",
  "releaseUrl": "https://github.com/<你的用户名>/CirnoRecyclePlus/releases/tag/v1.2.1",
  "changelog": "https://raw.githubusercontent.com/<你的用户名>/CirnoRecyclePlus/main/CHANGELOG.md"
}
```

> 注意：App 内更新提示比较的是**版本名**（`version` 字段，例如 `1.2.0`）：只要 `update.json`
> 的版本名比当前 App 的 `versionName` 新就会弹提示。`versionCode` 只给 Magisk / KernelSU
> 判断模块更新用（要和 `module/module.prop` 一致），两处编号不要混用。
> 版本升级时，`app/build.gradle.kts` 的 `versionName`、`module/module.prop` 的 `version`、
> `update.json` 的 `version` 三处要一起改；`versionCode` / 模块 `versionCode` 也同步加一。
> 如果 Release 里没有上传独立 APK，把 `apkUrl` 删掉即可，App 会回退到 Release 页面。

## 5. 本地构建 / 验证（不依赖 GitHub）

```powershell
# 一键：单元测试 -> Release APK -> 模块 zip
powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1

# 只打包模块 zip（APK 已经构建好时）
powershell -ExecutionPolicy Bypass -File scripts\package-module.ps1
```

产物在 `dist\`：

- `Cirno-Recycle-Plus-v1.2.1.zip`：可直接刷入的模块（里面带 `apk/CirnoRecyclePlus.apk`）
- `Cirno-Recycle-Plus-v1.2.1.apk`：独立 APK（可从 `app/build/outputs/apk/release/app-release.apk` 复制）
- `Cirno-Recycle-Plus-v1.2.1-source.zip`：源码包
- `CHECKSUMS.txt`：SHA256

## 6. 发布前自检清单

- [ ] `./gradlew testDebugUnitTest` 通过（PSI 解析 / 配置校正 / 压力分级 / 版本比较 / 进程名规范化）
- [ ] `./gradlew assembleRelease` 通过，APK 已签名（`apksigner verify`）
- [ ] 模块 zip 结构正确：根目录就是 `module.prop`，没有多包一层文件夹
- [ ] 模块 zip 里带 `apk/CirnoRecyclePlus.apk`
- [ ] `update.json` 的 `version` 与 APK `versionName` / 模块 `version` 一致，链接指向本次 Release
- [ ] 真机测试：刷入 → 重启 → LSPosed 启用 → App 设置页能读写配置，深色模式 / 主题色 / 更新检查正常
- [ ] 应用面板能列出含未运行的进程，白名单应用能手动冻结子进程
- [ ] 日志里没有反复刷屏的异常
- [ ] `README.md` 里的版本号、作者、二改声明与实际一致
- [ ] `CHANGELOG.md` 已更新

## 7. 关于签名密钥

`signing/cirno-release.jks` 是**公开的测试密钥**，口令写在 `signing/README.md` 里。
这样做的好处是任何人从源码构建的 APK 都能覆盖安装；缺点是任何人都能签同名 APK。
如果你打算长期维护，建议换成自己的私钥，并同步修改 `app/build.gradle.kts` 里的口令。

## 8. 如果 Actions 构建失败

常见原因：

| 现象 | 处理 |
|---|---|
| `SDK location not found` | 确认 workflow 里 `android-actions/setup-android` 和 `sdkmanager --install "platforms;android-36"` 都执行了 |
| `Unsupported class file major version` | JDK 版本不对，workflow 里必须是 **25** |
| 依赖下载失败 | 重跑 Actions；或检查仓库是否限制 Actions 访问外网 |
| 签名相关报错 | 确认仓库里有 `signing/cirno-release.jks`，且 `app/build.gradle.kts` 里的口令没改 |

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

## 3. 打 tag 并发布 v1.1.0

```powershell
& $git tag -a v1.1.0 -m "Cirno Recycle+ v1.1.0"
& $git push origin v1.1.0
```

推送 tag 后，`.github/workflows/release.yml` 会自动：

1. 用 JDK 25 + Android SDK 36 构建 Release APK（仓库自带公开签名密钥，自动签名）；
2. 跑 `scripts/package-module.sh` 打包成 `dist/Cirno-Recycle-Plus-v1.1.0.zip`；
3. 创建 GitHub Release，上传模块 zip 和 `CHECKSUMS.txt`。

第一次跑 Actions 大约需要 8–15 分钟（要下载 Gradle 依赖）。在仓库的 **Actions** 页面可以看进度。

## 4. （可选）开启 Magisk / KernelSU 的模块更新提示

GitHub Release 页面拿到模块 zip 的固定下载地址：

```
https://github.com/<你的用户名>/CirnoRecyclePlus/releases/latest/download/Cirno-Recycle-Plus-v1.1.0.zip
```

在仓库根目录新建 `update.json`：

```json
{
  "version": "v1.1.0",
  "versionCode": 11000,
  "zipUrl": "https://github.com/<你的用户名>/CirnoRecyclePlus/releases/latest/download/Cirno-Recycle-Plus-v1.1.0.zip",
  "changelog": "https://raw.githubusercontent.com/<你的用户名>/CirnoRecyclePlus/main/CHANGELOG.md"
}
```

然后在 `module/module.prop` 里加一行：

```
updateJson=https://raw.githubusercontent.com/<你的用户名>/CirnoRecyclePlus/main/update.json
```

> 注意：版本号升级时，`update.json` 里的 `version` / `versionCode` / `zipUrl` 和
> `module/module.prop` 里的 `version` / `versionCode` 要一起改，否则更新提示不生效。

## 5. 本地构建 / 验证（不依赖 GitHub）

```powershell
# 一键：单元测试 -> Release APK -> 模块 zip
powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1

# 只打包模块 zip（APK 已经构建好时）
powershell -ExecutionPolicy Bypass -File scripts\package-module.ps1
```

产物在 `dist\`：

- `Cirno-Recycle-Plus-v1.1.0.zip`：可直接刷入的模块
- `CHECKSUMS.txt`：SHA256

## 6. 发布前自检清单

- [ ] `./gradlew testDebugUnitTest` 通过（PSI 解析 / 配置校正 / 压力分级）
- [ ] `./gradlew assembleRelease` 通过，APK 已签名（`apksigner verify`）
- [ ] 模块 zip 结构正确：根目录就是 `module.prop`，没有多包一层文件夹
- [ ] 模块 zip 里带 `apk/CirnoRecyclePlus.apk`
- [ ] 真机测试：刷入 → 重启 → LSPosed 启用 → App 设置页能读写配置
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

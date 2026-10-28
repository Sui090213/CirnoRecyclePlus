# 签名说明

本仓库使用一把**公开的测试签名密钥**给 Release APK 签名，目的是：

- 本地构建和 GitHub Actions 构建出来的 APK 签名一致，可以互相覆盖安装 / 升级；
- 任何人都能克隆仓库、重新构建并得到可以覆盖安装的 APK。

## 文件

| 文件 | 说明 |
|---|---|
| `cirno-release.jks` | PKCS12 格式密钥库 |
| 别名（alias） | `cirno` |
| 密钥库口令 | `flyingr123` |
| 密钥口令 | `flyingr123` |
| 有效期 | 约 32 年（2026 起） |

`app/build.gradle.kts` 里会检测 `signing/cirno-release.jks` 是否存在：

- 存在 → Release APK 自动用该密钥签名；
- 不存在 → 构建 unsigned APK（此时刷入的模块 ZIP 里会退回到 Debug 签名，见构建脚本说明）。

## 安全提示

- 这是**公开密钥**，任何人都能用它签名 APK。请只从本仓库 Release 页面下载模块 ZIP，或者自己从源码构建。
- 不要把这里的密钥用于上架应用商店或任何需要身份验证的场景。
- 如果你要维护自己的分叉版本，建议换成自己的密钥（修改 `signing/` 下的文件与 `app/build.gradle.kts` 里的口令即可），否则别人签的 APK 也能覆盖安装你的版本。

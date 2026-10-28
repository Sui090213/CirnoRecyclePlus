param(
    [string]$JdkHome = '',
    [string]$AndroidSdk = '',
    [switch]$SkipUnitTests
)

# =============================================================================
# Cirno Recycle+ 一键构建脚本 (Windows)
#
# 做三件事:
#   1. 用 Gradle 构建 Release APK (仓库自带公开签名密钥, 会自动签名);
#   2. 跑纯 JVM 单元测试 (PSI 解析 / 配置校正);
#   3. 打包成可直接刷入的 Magisk/KernelSU/APatch 模块 zip.
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1 -JdkHome D:\jdk-25 -AndroidSdk D:\android-sdk
# =============================================================================

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------------------
# 环境兜底: 让脚本在没有额外配置的 Windows 上也能直接跑
# ---------------------------------------------------------------------------
# 1) 清洗 PATH 里带引号的坏条目: Gradle 会把 PATH 拼进测试 JVM 的
#    -Djava.library.path, 一个多余的引号就能让单元测试 JVM 直接以
#    ClassNotFoundException 退出 (本机实测踩过).
$env:Path = (($env:Path -split ';') | Where-Object { $_ -and ($_ -notmatch '"') }) -join ';'

# 2) 把 .android (debug keystore / 首选项) 指到项目内, 避免用户目录不可写时 AGP 报
#    AccessDeniedException; 注意不要再设置 ANDROID_PREFS_ROOT, AGP 9 会报冲突.
$env:ANDROID_USER_HOME = Join-Path $repoRoot 'build\android-home'
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

# ---------------------------------------------------------------------------
# 工具链定位: 参数 > 环境变量 > 本项目工作区里的默认位置
# ---------------------------------------------------------------------------
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome -and (Test-Path 'D:\deepseek\tools\jdk-25')) { $JdkHome = 'D:\deepseek\tools\jdk-25' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk -and (Test-Path 'D:\deepseek\tools\android-sdk')) { $AndroidSdk = 'D:\deepseek\tools\android-sdk' }

if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\java.exe'))) {
    throw '找不到 JDK 25。请设置 JAVA_HOME 或传 -JdkHome。'
}
if (-not $AndroidSdk -or -not (Test-Path (Join-Path $AndroidSdk 'platforms'))) {
    throw '找不到 Android SDK。请设置 ANDROID_HOME 或传 -AndroidSdk。'
}

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH

# Gradle / AGP 在 Windows 上默认用系统临时目录, 某些机器的 %TEMP% 权限有问题,
# 这里统一指到项目内, 避免 zipflinger 写临时文件时 AccessDenied.
$tmp = Join-Path $repoRoot 'build\tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TMP = $tmp
$env:TEMP = $tmp
$env:TMPDIR = $tmp

Write-Host "JDK: $JdkHome"
Write-Host "Android SDK: $AndroidSdk"
Write-Host "临时目录: $tmp"

$gradlew = Join-Path $repoRoot 'gradlew.bat'

if (-not $SkipUnitTests) {
    Write-Host ''
    Write-Host '=== 运行单元测试 ==='
    & $gradlew testDebugUnitTest --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "单元测试失败 (exit $LASTEXITCODE)" }
}

Write-Host ''
Write-Host '=== 构建 Release APK ==='
& $gradlew assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Release 构建失败 (exit $LASTEXITCODE)" }

Write-Host ''
Write-Host '=== 打包模块 zip ==='
& (Join-Path $PSScriptRoot 'package-module.ps1')
if ($LASTEXITCODE -ne 0) { throw "模块打包失败 (exit $LASTEXITCODE)" }

Write-Host ''
Write-Host '全部完成。产物在 dist\ 目录。'

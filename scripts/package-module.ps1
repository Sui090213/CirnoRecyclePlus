param(
    [string]$Apk,
    [string]$OutputDir,
    [switch]$KeepStage
)

# =============================================================================
# Cirno Recycle+ 模块打包脚本
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File scripts\package-module.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\package-module.ps1 -Apk path\to\app-release.apk
#
# 输出:
#   dist\Cirno-Recycle-Plus-<version>.zip          可直接刷入的 Magisk/KernelSU/APatch 模块
#   dist\CHECKSUMS.txt                             SHA256 校验值
# =============================================================================

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $repoRoot 'module'

if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dist'
}

if (-not $Apk) {
    $signed = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
    $unsigned = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
    $debug = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $signed) { $Apk = $signed }
    elseif (Test-Path $debug) { $Apk = $debug }
    elseif (Test-Path $unsigned) { $Apk = $unsigned }
}

if (-not $Apk -or -not (Test-Path $Apk)) {
    throw "找不到 APK。请先构建：.\gradlew.bat assembleRelease（或 assembleDebug），或用 -Apk 指定路径。"
}

# ---------------------------------------------------------------------------
# 拒绝把未签名 APK 打进模块
#
# 未签名的 APK 在设备上执行 pm install 一定会失败, 用户刷完模块会发现手机上没有 App.
# 这里提前拦下来, 而不是静默打一个坏包.
# ---------------------------------------------------------------------------
if ($Apk -match 'unsigned\.apk$') {
    throw "拒绝打包未签名 APK: $Apk。请先配置 signing/cirno-release.jks 并执行 assembleRelease。"
}

$apksigner = $null
if ($env:ANDROID_HOME) {
    $buildToolsRoot = Join-Path $env:ANDROID_HOME 'build-tools'
    # 优先用固定版本 (和项目文档一致), 找不到再回退到版本号最高的 build-tools.
    $preferred = Join-Path $buildToolsRoot '36.0.0\apksigner.bat'
    if (Test-Path $preferred) {
        $apksigner = $preferred
    }
    elseif (Test-Path $buildToolsRoot) {
        $buildToolsDirs = Get-ChildItem $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object { try { [version]($_.Name -replace '[^0-9.]', '') } catch { [version]'0.0' } } -Descending
        foreach ($dir in $buildToolsDirs) {
            $candidate = Join-Path $dir.FullName 'apksigner.bat'
            if (Test-Path $candidate) {
                $apksigner = $candidate
                break
            }
        }
    }
}

if ($apksigner) {
    # 注意: JDK 25 的 apksigner 会向 stderr 打 "restricted method" 警告.
    # PowerShell 5.1 在 $ErrorActionPreference='Stop' 下会把 native 的 stderr 当成终止性
    # NativeCommandError, 所以这里临时改成 Continue, 只根据 $LASTEXITCODE 判断结果.
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $verifyOutput = & $apksigner verify --print-certs $Apk 2>&1
        $verifyExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    if ($verifyExitCode -ne 0) {
        throw "APK 签名校验失败, 拒绝打包:`n$($verifyOutput -join "`n")"
    }
    Write-Host "APK 签名校验通过"
}
else {
    Write-Warning "没找到 apksigner, 跳过 APK 签名校验 (建议设置 ANDROID_HOME 指向 Android SDK)"
}

# ---------------------------------------------------------------------------
# 读取模块版本
# ---------------------------------------------------------------------------
$propFile = Join-Path $moduleDir 'module.prop'
if (-not (Test-Path $propFile)) {
    throw "找不到 module/module.prop"
}

$version = '1.4.1'
foreach ($line in Get-Content $propFile) {
    if ($line -match '^\s*version\s*=\s*(.+)$') {
        $version = $Matches[1].Trim().TrimStart('v', 'V')
    }
}

$zipName = "Cirno-Recycle-Plus-v$version.zip"
$zipPath = Join-Path $OutputDir $zipName

# ---------------------------------------------------------------------------
# 准备暂存目录
# ---------------------------------------------------------------------------
$stage = Join-Path $repoRoot 'build\module-stage'
if (Test-Path $stage) {
    Remove-Item $stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stage | Out-Null
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

Copy-Item -Path (Join-Path $moduleDir '*') -Destination $stage -Recurse -Force

$apkStageDir = Join-Path $stage 'apk'
New-Item -ItemType Directory -Force -Path $apkStageDir | Out-Null
Copy-Item -Path $Apk -Destination (Join-Path $apkStageDir 'CirnoRecyclePlus.apk') -Force

# ---------------------------------------------------------------------------
# 文本文件统一成 LF, 去掉 BOM
#
# shell 脚本 / module.prop 带 CRLF 或 BOM 会在部分设备上导致安装脚本解析失败.
# ---------------------------------------------------------------------------
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$textExtensions = @('.sh', '.prop', '.md', '.rule', '.txt', '.json', '.yml', '.yaml')
Get-ChildItem $stage -Recurse -File | ForEach-Object {
    $isText = $textExtensions -contains $_.Extension.ToLowerInvariant() -or
    $_.Name -eq 'update-binary' -or $_.Name -eq 'updater-script'
    if (-not $isText) { return }

    $text = [System.IO.File]::ReadAllText($_.FullName)
    $normalized = $text -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($_.FullName, $normalized, $utf8NoBom)
}

# ---------------------------------------------------------------------------
# 打包成 zip (手工写 ZipArchive, 保证 entry 用 / 分隔且不包含暂存目录前缀)
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}

$stageFull = (Resolve-Path $stage).Path.TrimEnd('\')
$stamp = Get-Date '2026-01-01T00:00:00'

$zipStream = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
try {
    $archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $files = Get-ChildItem $stage -Recurse -File | Sort-Object FullName
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($stageFull.Length + 1).Replace('\', '/')
            $entry = $archive.CreateEntry($relative, [System.IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = $stamp
            $entryStream = $entry.Open()
            try {
                $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
                $entryStream.Write($bytes, 0, $bytes.Length)
            }
            finally {
                $entryStream.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}
finally {
    $zipStream.Dispose()
}

# ---------------------------------------------------------------------------
# 校验 zip 结构: 必须根目录就是模块文件, 不能多包一层文件夹
# ---------------------------------------------------------------------------
$checkArchive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($checkArchive.Entries | ForEach-Object { $_.FullName })
}
finally {
    $checkArchive.Dispose()
}

foreach ($required in @('module.prop', 'customize.sh', 'service.sh', 'post-fs-data.sh',
        'action.sh', 'uninstall.sh', 'apk/CirnoRecyclePlus.apk',
        'META-INF/com/google/android/update-binary',
        'META-INF/com/google/android/updater-script')) {
    if ($names -notcontains $required) {
        throw "打包校验失败: zip 里缺少 $required"
    }
}

# ---------------------------------------------------------------------------
# 输出校验值
# ---------------------------------------------------------------------------
$hash = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$sizeMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 2)
$checksumFile = Join-Path $OutputDir 'CHECKSUMS.txt'
"$hash  $zipName" | Set-Content -Path $checksumFile -Encoding ASCII

Write-Host ''
Write-Host '============================================='
Write-Host " 模块打包完成: $zipPath"
Write-Host " 大小: $sizeMb MB"
Write-Host " SHA256: $hash"
Write-Host " APK 来源: $Apk"
Write-Host '============================================='

if (-not $KeepStage) {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}

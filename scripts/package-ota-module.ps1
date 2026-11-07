param(
    [string]$OutputDir
)

# =============================================================================
# Package the standalone ColorOS 17 OTA disabler module.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\package-ota-module.ps1
#
# Output:
#   dist\Cirno-ColorOS17-OTA-Disabler-v<version>.zip
#   dist\CHECKSUMS-ota.txt
# =============================================================================

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $repoRoot 'module-coloros17-ota'

if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dist'
}

$propFile = Join-Path $moduleDir 'module.prop'
if (-not (Test-Path $propFile)) {
    throw "Cannot find module-coloros17-ota/module.prop"
}

$version = '1.4.1'
foreach ($line in Get-Content $propFile) {
    if ($line -match '^\s*version\s*=\s*(.+)$') {
        $version = $Matches[1].Trim().TrimStart('v', 'V')
    }
}

$zipName = "Cirno-ColorOS17-OTA-Disabler-v$version.zip"
$zipPath = Join-Path $OutputDir $zipName

$stage = Join-Path $repoRoot 'build\ota-module-stage'
if (Test-Path $stage) {
    Remove-Item $stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stage | Out-Null
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

Copy-Item -Path (Join-Path $moduleDir '*') -Destination $stage -Recurse -Force

# Normalize text files to LF without BOM.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$textExtensions = @('.sh', '.prop', '.md', '.txt', '.json')
Get-ChildItem $stage -Recurse -File | ForEach-Object {
    $isText = $textExtensions -contains $_.Extension.ToLowerInvariant() -or
    $_.Name -eq 'update-binary' -or $_.Name -eq 'updater-script'
    if (-not $isText) { return }

    $text = [System.IO.File]::ReadAllText($_.FullName)
    $normalized = $text -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($_.FullName, $normalized, $utf8NoBom)
}

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

$checkArchive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($checkArchive.Entries | ForEach-Object { $_.FullName })
}
finally {
    $checkArchive.Dispose()
}

foreach ($required in @('module.prop', 'customize.sh', 'service.sh', 'post-fs-data.sh',
        'action.sh', 'uninstall.sh', 'ota_block.sh',
        'META-INF/com/google/android/update-binary',
        'META-INF/com/google/android/updater-script')) {
    if ($names -notcontains $required) {
        throw "Packaging check failed: missing $required"
    }
}

$hash = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumFile = Join-Path $OutputDir 'CHECKSUMS-ota.txt'
"$hash  $zipName" | Set-Content -Path $checksumFile -Encoding ASCII

Write-Host ''
Write-Host '============================================='
Write-Host " OTA module packaged: $zipPath"
Write-Host " SHA256: $hash"
Write-Host '============================================='

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

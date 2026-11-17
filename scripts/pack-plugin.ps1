# 只重打 JS 插件包，跳过 Gradle 构建。
# 与 package-dist.ps1 里的插件打包逻辑一致：manifest.json 在根，.js 保留相对层级。
#
# 用法：powershell -ExecutionPolicy Bypass -File scripts/pack-plugin.ps1 mihealth

param(
    [string]$PluginDir = "mihealth"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root "js-plugins\$PluginDir"
$manifestPath = Join-Path $src "manifest.json"

if (-not (Test-Path $manifestPath)) {
    throw "找不到 manifest.json：$manifestPath"
}

$manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$outDir = Join-Path $root "dist\lifeboard-0.3.6\plugins"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "$($manifest.id).jsplugin"

if (Test-Path $outFile) { Remove-Item $outFile -Force }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($outFile, 'Create')
try {
    # manifest.json 必须在根
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $manifestPath, 'manifest.json') | Out-Null

    # 其余文件保留相对路径（支持 ./util.js 这类 import）
    Get-ChildItem $src -Recurse -File |
        Where-Object { $_.Name -ne 'manifest.json' } |
        ForEach-Object {
            $rel = $_.FullName.Substring($src.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $_.FullName, $rel) | Out-Null
        }
}
finally {
    $zip.Dispose()
}

$size = (Get-Item $outFile).Length
Write-Host "已打包 $($manifest.id) $($manifest.version) -> $outFile ($size 字节)"

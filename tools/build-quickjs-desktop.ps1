﻿﻿﻿﻿﻿# 构建桌面（Windows x86_64）版 QuickJS 动态库
#
# 背景：Android 侧由 AGP 的外部 native 构建（NDK + arm64-v8a）产出 .so；
# 桌面侧没有等价机制，需要一个独立可复现的本地构建入口。
#
# 产物：js-engine/src/desktopMain/resources/win32-x86-64/lifeboard-js.dll
#   放进 resources 而不是 build 目录，是因为需要随仓库一起提交
#   （理由见 P_plan.md 0.4：产物入库，版本锁定 v0.9.0，避免每次构建都要本机有 MSVC）。
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File tools/build-quickjs-desktop.ps1
#
# 前置条件：Visual Studio 2022 BuildTools 勾选
#   - Microsoft.VisualStudio.Component.VC.Tools.x86.x64
#   - Microsoft.VisualStudio.Component.VC.CMake.Project
#   - Windows 10/11 SDK

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $repoRoot "js-engine\src\main\cpp\desktop"
$buildDir = Join-Path $repoRoot "js-engine\build\desktop-native"
$outDir = Join-Path $repoRoot "js-engine\src\desktopMain\resources\win32-x86-64"

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw "找不到 vswhere.exe，请确认已安装 Visual Studio 2022 BuildTools"
}

$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) {
    throw "未找到含 MSVC x64 工具集的 Visual Studio 安装。请安装组件 Microsoft.VisualStudio.Component.VC.Tools.x86.x64"
}
Write-Host "Visual Studio: $vsPath"

$vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $vcvars)) {
    throw "找不到 vcvars64.bat：$vcvars"
}

$cmake = Join-Path $vsPath "Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
$ninja = Join-Path $vsPath "Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
if (-not (Test-Path $cmake)) { throw "找不到 cmake.exe：$cmake" }
if (-not (Test-Path $ninja)) { throw "找不到 ninja.exe：$ninja" }

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 必须在 vcvars64 环境里跑 cmake：它负责注入 INCLUDE / LIB / PATH
$configureCmd = "call `"$vcvars`" >nul && `"$cmake`" -S `"$srcDir`" -B `"$buildDir`" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM=`"$ninja`""

Write-Host "开始编译 QuickJS 桌面动态库..."
& cmd.exe /d /c $configureCmd
if ($LASTEXITCODE -ne 0) {
    throw "CMake 配置失败（退出码 $LASTEXITCODE）"
}

$buildCmd = "call `"$vcvars`" >nul && `"$cmake`" --build `"$buildDir`" --config Release"
& cmd.exe /d /c $buildCmd
if ($LASTEXITCODE -ne 0) {
    throw "编译失败（退出码 $LASTEXITCODE）"
}

$dll = Join-Path $buildDir "lifeboard-js.dll"
if (-not (Test-Path $dll)) {
    throw "编译未产出 lifeboard-js.dll，请检查 CMake 输出"
}

Copy-Item $dll (Join-Path $outDir "lifeboard-js.dll") -Force
$size = (Get-Item (Join-Path $outDir "lifeboard-js.dll")).Length
Write-Host "完成：$outDir\lifeboard-js.dll ($([math]::Round($size/1KB)) KB)"

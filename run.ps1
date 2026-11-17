# ============================================================
#  LifeBoard 桌面端一键启动
#
#  用途：每次改了代码，双击这个脚本就能跑起最新的桌面端。
#        脚本会自动编译，有改动才重编，没改动直接用缓存，
#        不需要你自己记 Gradle 命令。
#
#  用法：
#    双击 run.ps1                       直接启动
#    右键 -> 使用 PowerShell 运行        同上
#    命令行 .\run.ps1 -Clean            先清缓存再编（改动诡异时用）
#    命令行 .\run.ps1 -BuildOnly        只编译，不启动
#    命令行 .\run.ps1 -NoBuild          跳过编译，直接跑上次的产物
# ============================================================

param(
    [switch]$Clean,
    [switch]$BuildOnly,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'

# 统一 UTF-8，避免中文输出乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = $env:JAVA_TOOL_OPTIONS

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlew = Join-Path $root 'gradlew.bat'

function Write-Step($text) {
    Write-Host ''
    Write-Host ">> $text" -ForegroundColor Cyan
}

function Write-Ok($text) {
    Write-Host "   $text" -ForegroundColor Green
}

function Write-Warn($text) {
    Write-Host "   $text" -ForegroundColor Yellow
}

Write-Host ''
Write-Host '==========================================' -ForegroundColor White
Write-Host '  LifeBoard  桌面端启动' -ForegroundColor White
Write-Host '==========================================' -ForegroundColor White

# ---------- 环境检查 ----------
if (-not (Test-Path $gradlew)) {
    Write-Host ''
    Write-Host "找不到 gradlew.bat，脚本必须放在项目根目录：" -ForegroundColor Red
    Write-Host "  $root" -ForegroundColor Red
    exit 1
}

# 优先用项目自带的 JDK 设置；没有就依赖系统 JAVA_HOME
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Java','C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -match 'jdk' } | Select-Object -First 1
    if ($jdk) {
        $env:JAVA_HOME = $jdk.FullName
        Write-Warn "未设置 JAVA_HOME，自动使用：$($jdk.Name)"
    } else {
        Write-Warn '未检测到 JAVA_HOME，交给 Gradle 自行解析'
    }
}

# ---------- 编译 ----------
if (-not $NoBuild) {
    if ($Clean) {
        Write-Step '清理桌面端构建缓存'
        & $gradlew ':desktop:clean' --console=plain
        if ($LASTEXITCODE -ne 0) {
            Write-Host '   清理失败' -ForegroundColor Red
            exit $LASTEXITCODE
        }
        Write-Ok '已清理'
    }

    Write-Step '编译桌面端（首次较慢，之后有增量缓存）'
    Write-Host '   编译输出会直接显示在下面，报错请找第一条 e: 开头的行' -ForegroundColor DarkGray
    Write-Host ''

    & $gradlew ':desktop:compileKotlinDesktop' --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '编译失败。上面的 e: 开头那几行就是原因。' -ForegroundColor Red
        Write-Host '如果是缓存导致的诡异错误，试试：.\run.ps1 -Clean' -ForegroundColor Yellow
        exit $LASTEXITCODE
    }
    Write-Ok '编译通过'
}

if ($BuildOnly) {
    Write-Host ''
    Write-Host '仅编译模式，到此为止。' -ForegroundColor Green
    Write-Host ''
    exit 0
}

# ---------- 启动 ----------
Write-Step '启动应用'
Write-Host '   窗口即将打开；关闭窗口即退出。' -ForegroundColor DarkGray
Write-Host ''

& $gradlew ':desktop:run' --console=plain

$code = $LASTEXITCODE
Write-Host ''
if ($code -eq 0) {
    Write-Host '应用已退出（正常关闭）。' -ForegroundColor Green
} else {
    Write-Host "应用异常退出，退出码 $code" -ForegroundColor Red
    Write-Host '上面若出现 Exception / Caused by，那是崩溃原因。' -ForegroundColor Yellow
}
Write-Host ''

exit $code

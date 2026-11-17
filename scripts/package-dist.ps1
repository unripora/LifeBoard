# 打包脚本：生成 dist/lifeboard-<version>/ 分发目录
#
# 用法（在仓库根目录执行）：
#   powershell -ExecutionPolicy Bypass -File scripts/package-dist.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/package-dist.ps1 -SkipBuild
#
# -SkipBuild 复用已有构建产物（改完只重新打包时用），否则会先跑 assemble。
#
# 产物结构见文末说明。

param(
    # 跳过 Gradle 构建，直接用现有 APK 重新组装 dist（调试打包流程时省时间）
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

# 仓库根目录 = 本脚本所在目录的上一级
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    # ---------- 1. 读版本号 ----------
    #
    # versionName 手工维护在 app/build.gradle.kts 里。这里用正则去抓，
    # 而不是再写一份版本号到别处：两处各写一份必然会忘掉同步。
    $appGradle = Join-Path $repoRoot 'app/build.gradle.kts'
    $versionMatch = Select-String -Path $appGradle -Pattern 'versionName\s*=\s*"([^"]+)"' |
        Select-Object -First 1
    if (-not $versionMatch) {
        throw "没能从 $appGradle 里读出 versionName"
    }
    $version = $versionMatch.Matches[0].Groups[1].Value
    Write-Host "版本号：$version" -ForegroundColor Cyan

    # ---------- 2. 构建 ----------
    if (-not $SkipBuild) {
        Write-Host '构建 release + debug APK...' -ForegroundColor Cyan
        # assembleRelease 会顺带编出 js-engine 的 arm64-v8a 原生库
        & .\gradlew.bat :app:assembleRelease :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle 构建失败（退出码 $LASTEXITCODE）"
        }
    }

    $releaseApk = Join-Path $repoRoot 'app/build/outputs/apk/release/app-release.apk'
    $debugApk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
    foreach ($apk in @($releaseApk, $debugApk)) {
        if (-not (Test-Path $apk)) {
            throw "找不到构建产物：$apk（去掉 -SkipBuild 重新构建）"
        }
    }

    # ---------- 3. 准备 dist 目录 ----------
    $distRoot = Join-Path $repoRoot "dist/lifeboard-$version"
    $pluginsOut = Join-Path $distRoot 'plugins'

    # 只清本脚本会生成的内容，不动 dist 下其它东西
    if (Test-Path $distRoot) {
        Remove-Item $distRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $pluginsOut -Force | Out-Null

    # ---------- 4. 复制 APK ----------
    $releaseDest = Join-Path $distRoot "LifeBoard-$version-release.apk"
    $debugDest = Join-Path $distRoot "LifeBoard-$version-debug.apk"
    Copy-Item $releaseApk $releaseDest
    Copy-Item $debugApk $debugDest
    Write-Host "已复制主程序 APK" -ForegroundColor Green

    # ---------- 5. 打 JS 插件包 ----------
    #
    # .jsplugin 就是一个 ZIP，且 **manifest.json 必须在包根目录**——
    # 宿主的安装校验直接按 "manifest.json" 这个键去查，套一层目录会认不出来。
    # 包内脚本之间的 import 用的是相对路径（'./util.js'），
    # 所以必须保持各文件在源目录里的相对层级，不能压平。
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $jsPluginSrc = Join-Path $repoRoot 'js-plugins'
    $jsPluginDirs = Get-ChildItem -Path $jsPluginSrc -Directory -ErrorAction SilentlyContinue
    # 记下打出来的包名，供文末说明文件列出真实文件名
    $packagedPlugins = @()

    foreach ($dir in $jsPluginDirs) {
        $manifestPath = Join-Path $dir.FullName 'manifest.json'
        if (-not (Test-Path $manifestPath)) {
            Write-Host "跳过 $($dir.Name)：没有 manifest.json" -ForegroundColor Yellow
            continue
        }

        # 用 manifest 里的 id 命名，而不是目录名：用户看到的名字要和宿主里的一致。
        # 必须显式指定 UTF8：Windows PowerShell 5.1 的 Get-Content 默认按系统 ANSI
        # 读取，manifest 里的中文会被解成乱码，ConvertFrom-Json 直接解析失败。
        $manifest = Get-Content $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $pluginId = $manifest.id
        if (-not $pluginId) {
            Write-Host "跳过 $($dir.Name)：manifest 缺少 id" -ForegroundColor Yellow
            continue
        }

        $zipPath = Join-Path $pluginsOut "$pluginId.jsplugin"
        if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

        $zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
        try {
            # 只收 .js 与 manifest.json：宿主安装时也只保留这两类，
            # 其余（截图、笔记）打进去只是白占体积
            $files = Get-ChildItem -Path $dir.FullName -Recurse -File |
                Where-Object { $_.Extension -eq '.js' -or $_.Name -eq 'manifest.json' }

            foreach ($file in $files) {
                # 相对路径统一用 / 分隔：ZIP 规范要求，且宿主按 / 拼模块名
                $relative = $file.FullName.Substring($dir.FullName.Length + 1)
                $entryName = $relative -replace '\\', '/'
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $zip, $file.FullName, $entryName, 'Optimal'
                ) | Out-Null
            }
        } finally {
            $zip.Dispose()
        }

        $sizeKb = [math]::Round((Get-Item $zipPath).Length / 1KB, 1)
        Write-Host "已打包插件 $pluginId ($sizeKb KB)" -ForegroundColor Green
        $packagedPlugins += "$pluginId.jsplugin"
    }

    # ---------- 6. 生成说明文件 ----------
    $readmePath = Join-Path $distRoot '安装与体验说明.md'

    # 列出实际打出来的插件文件名，而不是写占位符——用户照着名字找包，写 <插件id> 等于没写
    $pluginListing = if ($packagedPlugins.Count -gt 0) {
        $lines = @()
        for ($i = 0; $i -lt $packagedPlugins.Count; $i++) {
            # 最后一项用 └── 收尾，其余用 ├──，这样树形线才是连通的
            $branch = if ($i -eq $packagedPlugins.Count - 1) { '└──' } else { '├──' }
            $lines += "│   $branch $($packagedPlugins[$i])"
        }
        $lines -join "`n"
    } else {
        '│   └── （本次没有插件包）'
    }

    $readme = @"
# LifeBoard $version 内测包说明

## 一、包里有什么

``````
lifeboard-$version/
├── LifeBoard-$version-release.apk   主程序（推荐装这个）
├── LifeBoard-$version-debug.apk     主程序调试版（出问题时抓日志用）
├── plugins/
$pluginListing
└── 安装与体验说明.md              本文件
``````

两个主程序包签名相同，可互相覆盖安装，不用先卸载。

---

## 二、安装步骤

1. 把 ``LifeBoard-$version-release.apk`` 传到手机，点击安装。
   系统会拦一次"未知来源应用"，允许即可。
2. 装完后先按下面「必要授权」逐项开权限，否则大部分功能是空的。

### 必要授权（按缺失后果排序）

| 权限 | 在哪开 | 不开会怎样 |
| --- | --- | --- |
| 使用情况访问 | 设置 → 应用 → 特殊权限 → 使用情况访问 | 屏幕时长采集全是 0 |
| 通知使用权 | 设置 → 通知与状态栏 → 通知使用权 | 飞书打卡、多邻国连胜识别不到 |
| 无障碍服务 | 设置 → 无障碍 → 已下载的服务 → LifeBoard | 应用内提示型行为采集不到 |
| 电池优化白名单 | 应用内提醒设置页会引导 | 强提醒可能被后台杀掉不响 |
| 自启动（澎湃OS） | 设置 → 应用管理 → LifeBoard → 自启动 | 重启后闹钟不重排 |
| 通知权限 | 首次启动会弹 | 提醒只响不显示 |

---

## 三、安装插件

插件是 ``.jsplugin`` 文件（本质是 ZIP），**不是 APK**，不要直接点开安装。

1. 进 LifeBoard 的「插件」页
2. 点「导入插件」，选择 ``plugins/`` 下的 ``.jsplugin`` 文件
3. 导入后插件处于**待信任**状态，需要点「信任」才会执行脚本
4. 信任后在「数据源」页就能选到它声明的指标

未信任的插件不会被执行——JS 沙箱虽然拿不到系统能力，但脚本能通过宿主提供的
HTTP 能力向外发数据，所以这条边界必须由用户自己确认。

### 小米健康插件需要账号

``com.lifeboard.plugin.mihealth.jsplugin`` 走小米云端接口，需要在插件详情页登录
小米账号（会走二次验证）。没有账号时采集失败属正常现象，失败原因可在
「数据源状态」卡里看到。

---

## 四、构建期验证过了什么

以下只能说明代码能编译、纯逻辑单测通过，**不代表真机行为正确**：

- ``:app:assembleRelease`` / ``:app:assembleDebug`` — 通过
- ``:js-engine:externalNativeBuildDebug`` — QuickJS 原生库编译通过（仅 arm64-v8a）
- ``:server:testDebugUnitTest`` — 全部通过

### 本版修复的插件引擎缺陷（未经真机验证）

- OkHttp 回调不再跨线程操作 QuickJS 上下文（原先与卸载/重载存在释放竞态）
- Promise 落定后在同线程立刻推进 job queue，修复异步调用偶发拿不到数据
- 插件函数调用键改为唯一序号，避免上一次挂起的调用被下一次复用
- 新增原生中断，插件里的死循环不再永久卡死运行时；关停加了超时兜底
- 扫描/安装/信任串行化，插件 ID 做全局唯一校验
- 采集失败不再误显示「同步完成」
- 小米插件：查询状态改为请求成功后才登记（网络失败不再显示"这天没数据"）；
  登出后凭据不会被在途登录写回

---

## 五、已知限制

- **本次未做真机测试**。上面修复的正确性只经过编译与单测，插件在真机上能否
  跑通（尤其小米登录链路）需要实际验证。
- release 包用的是**调试签名**，可安装但**不能用于正式发布**。
- release 包未开混淆，体积未优化。
- 原生库只打了 arm64-v8a，32 位 ARM 设备与 x86 模拟器装不了。

---

## 六、反馈格式

``````
页面 → 操作步骤 → 实际现象 → 期望结果
``````
"@
    Set-Content -Path $readmePath -Value $readme -Encoding UTF8

    # ---------- 7. 汇总 ----------
    Write-Host ''
    Write-Host "打包完成：$distRoot" -ForegroundColor Cyan
    Get-ChildItem -Path $distRoot -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($distRoot.Length + 1)
        $mb = [math]::Round($_.Length / 1MB, 2)
        Write-Host ("  {0,-52} {1,7} MB" -f $rel, $mb)
    }
} finally {
    Pop-Location
}

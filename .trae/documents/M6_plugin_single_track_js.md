# M6 重写：插件体系统一为 JS 单轨

## 一、背景与决策变更

M5 完成后，桌面端 `.jsplugin` 已打通安装 → 信任 → 登录 → 拉数全链路。原 M6 规划为「JAR 原生插件加载」（父优先 `URLClassLoader`），现在**整体作废**。

用户决策：**插件只走 JS，后期连 Android 端的 APK 轨也移除。**

直接后果：

1. 三条轨（APK / JS / JAR）收敛为一条（`.jsplugin`）
2. 桌面端与 Android 端插件能力天然对齐，不再需要「桌面专属原生插件方案」
3. `feature_js_plugin_engine` 里「双轨并行、不删除 APK」的旧决策需要同步推翻

### 关键事实（调研结论）

- 现有两个 APK 轨 Gradle 模块：`:mihealth-plugin`（897 行 Kotlin）、`:demo-plugin`
- JS 版与 APK 版小米健康 **pluginId 完全相同**：`com.lifeboard.plugin.mihealth`，15 个 metricKey 一一对应
- 因此 `sourceType = PLUGIN_com.lifeboard.plugin.mihealth:{metricKey}` 两轨逐字相同，**换轨无需数据迁移**
- 桌面端 `DesktopPluginRepository.install` 目前 100% 走 JS 安装器，APK 包只会以笼统的解析失败结束

## 二、`demo-plugin` 直接删除（不保留、不改写为 JS）

`demo-plugin` 的存在理由是「在没有真实插件时验证 APK 契约」。这个理由随 APK 轨一起消失；小米健康 JS 版已经承担了真实插件的验证职责。不保留 Kotlin 版，也不改写成 JS 版。

### 删除必须一并处理的牵连

调研发现 `demo-plugin` 不是孤立模块，它是 **Android 侧插件测试的唯一载体**：

| 牵连点 | 位置 | 处理 |
|---|---|---|
| Gradle 模块注册 | `settings.gradle.kts:37` | 移除 `include(":demo-plugin")` |
| APK 复制任务 | `app/build.gradle.kts:218-226` `copyDemoPluginApk` | 删除任务，并把它从 `249` / `252` 行的 `dependsOn` 中摘掉 |
| androidTest 资产 | `app/src/androidTest/.../PluginLoaderEndToEndTest.kt` | 依赖 `demo-plugin.ext`，随之失效 |
| androidTest 资产 | `app/src/androidTest/.../PluginHealthAndTrustTest.kt` | 同上，且断言里硬编码 `PLUGIN_com.lifeboard.plugin.demo:DEMO_STEPS` |
| 忽略规则 | `.gitignore:25` | 注释说明该产物来自 `:demo-plugin`，随之清理 |
| SDK 文档 | `plugin-api/PLUGIN_SDK.md:91` | 原文「`demo-plugin/` 可作为模板参考」，需改写或删除该句 |
| 模块注释 | `mihealth-plugin/build.gradle.kts:3,15` | 两处注释以 `demo-plugin` 作对比基准，需改写 |

### 这 6 个 androidTest 怎么办

它们验证的是 **APK 加载链路**（`DexClassLoader`、APK 证书信任、`PluginLoader.isSchedulable`、`markUntrusted`），本轮 APK 轨尚在，测试不应被无声删除。

本轮处理：**把这 6 个测试与 `:demo-plugin` 一起标记为「随 APK 轨一并移除」，在 M6b 执行**。本轮删模块时，这些测试会因为拿不到 assets 而失败，因此**必须同轮处理**，二选一：

- **方案 A（推荐）**：本轮只改文档与桌面端，`demo-plugin` 与 androidTest 一起留到 M6b 删除。保持 Android 主干始终可构建、可测试。
- **方案 B**：本轮删 `demo-plugin`，同时把这 6 个 androidTest 一并删除。代价是 APK 轨在 M6b 拆除之前失去了测试覆盖。

倾向于 A：项目既有约定是「Android 主干始终可构建、可安装、可升级」，半拆状态会破坏这一点。

## 三、本轮范围（M6a：桌面端收口 + 文档同步）

### 6a.1 桌面端安装入口给明确提示

`desktop/src/desktopMain/kotlin/com/lifeboard/desktop/plugin/DesktopPluginRepository.kt`：

- `install(source: File)` 入口按后缀预检，非 `.jsplugin` 直接返回 `Failed("桌面端仅支持 .jsplugin 插件包")`
- 理由：现在 APK 包会进入 zip 解析再报「不是合法的插件包」，用户无法区分「包坏了」和「格式不支持」

`SwingFilePicker`（`DesktopPlatformDependencies.kt:464-486`）保持「不传 MIME 时不过滤」的现有行为，**不加扩展名过滤**——M5 已验证过过滤会把文件藏起来。

### 6a.2 文档同步

- `docs/tasks/refactor_desktop_support/P_plan.md`：M6 章节重写为「插件轨收敛为 JS 单轨」，删除 JAR 全部子项；M7 不变
- `docs/tasks/refactor_desktop_support/index.md`：决策 7、插件延续行、对比表「原生插件」行改为 JS 单轨口径；补变更日志
- `docs/tasks/feature_js_plugin_engine/index.md` 与 `R1_research.md`：推翻「双轨并行、不删除 APK」旧决策
- `R1_research.md:334-344` 的 `apk.` / `js.` ID 前缀方案标注为「未实施，实际共用裸 id」
- `docs/MasterPlan.md`：追加 M6 方向变更
- `plugin-api/PLUGIN_SDK.md`：把「参考 `demo-plugin`」改写为「参考 `js-plugins/mihealth`」

## 四、后续范围（M6b：Android APK 轨拆除）

**前置条件：小米健康 JS 插件经真实账号验证，登录 / 验证码 / 二次验证 / 15 项指标全部可用。**

拆除清单：

- 删 `PluginLoader.kt`（`DexClassLoader`）、`PluginInstaller.kt`（`APK_SUFFIX`、证书提取）、`PluginInstallService.kt`（APK 流程）、`PluginArchiveInfo.kt`、`PluginVersionPolicy.kt`
- `PluginManager` 的 install / uninstall / uninstallPending 去掉后缀分派，只留 JS 路径
- `AndroidPluginHost` 去掉 APK 分派
- 删 `:mihealth-plugin`、`:demo-plugin` 两个模块与 `settings.gradle.kts` 注册，摘掉 `copyDemoPluginApk` / `copyMiHealthPluginApk` 两个复制任务
- 删依赖 `demo-plugin.ext` / mihealth APK assets 的 6 个 androidTest
- Android `PluginTrustStore` 的「包名 + 证书哈希」信任主键逻辑随轨清理
- 清理 `metric` / `source_health` 中 `PLUGIN_com.lifeboard.plugin.demo:*` 孤儿数据

## 五、验证

- `:desktop:compileKotlinDesktop`、`:app:compileDebugKotlin`、`:shared:desktopTest` 全绿
- 桌面端选中 `.apk` → 得到「仅支持 .jsplugin」明确提示，而非解析失败
- `:app:assembleDebug` 通过（确认未误伤 APK 打包与 assets 复制任务）
- `node js-plugins/_debug/verify-offline.mjs` 保持通过

## 六、明确不做

- 本轮不删任何 Kotlin APK 插件代码（含 `demo-plugin`），不改 `DexClassLoader` 链路
- 不做 JAR 轨任何工作
- 不做数据迁移（已确认不需要）

# M5 实施计划：QuickJS 桌面端插件宿主

> 状态：待用户批准
> 关联：[P_plan.md M5](../../docs/tasks/refactor_desktop_support/P_plan.md) · [编码规范](../../.trae/rules/编码规范.md)
> 目标 DoD：`.jsplugin` 在 Windows 桌面可安装、可信任、可登录、可拉数；小米健康 JS 插件能拉到真实数据

---

## 一、Context（为什么这么做）

### 1.1 探索得到的现状（与 P_plan 原假设有出入）

M5 的 5.1–5.4 在 M4 期间已实际完成，**不需要重做**：

| 子项 | 实际状态 | 证据 |
|---|---|---|
| 5.1 拆 desktopMain | ✅ 已完成 | `js-engine` 已有 `desktopMain` 源集 |
| 5.2 编译 DLL | ✅ 已完成 | `js-engine/src/desktopMain/resources/win32-x86-64/lifeboard-js.dll`（915,968 字节）+ `tools/build-quickjs-desktop.ps1` |
| 5.3 JNA 绑定 | ✅ 已完成 | `JsNativeBridge.desktop.kt` 覆盖 create/destroy/evaluate/setModules/evaluateModule/callFunction/registerHostFunctions/resolvePromise/drainJobs/dropCall/requestInterrupt/gc |
| 5.4 线程模型 | ✅ 已完成 | `JsPluginRuntime` 已用专用单线程 + `resolveAndDrain` |
| 5.5 桥接层 | 部分 | `JsHttpBridge`/`JsCryptoBridge`/`JsTypeConverter`/`JsPluginContextBridge` 均不依赖 Android，但**卡在 `org.json` 与 `java.io.File`** |
| 5.6 双端测试 | 部分 | `DesktopJsRuntimeTest` 6 项已存在；插件层无桌面测试 |

**M5 的真实剩余工作量全部集中在「桌面端插件宿主从 0 到 1」**。

### 1.2 四个必须正视的事实

1. **`PluginHost` 契约从未被真正调用**。全仓只有 3 处引用（接口声明、`AndroidPluginHost`、`UnsupportedPluginHost`），而 `PluginViewModel.kt:129` 直接持 `Application` 并调 `PluginManager`。契约是「已设计但未接线」的死代码。
2. **shared 层没有任何插件 UI**。Android 的 `PluginScreen`/`PluginViewModel`/`PluginDetailScreen` 全在 `:app`。
3. **`DatabasePanelScope.pluginMetrics()` 返回 `emptyMap()`**（`DatabasePanelScope.kt:61`）。桌面端插件指标目前根本进不了指标表，即使插件跑起来，面板也看不到数据。
4. **桌面端插件页是 `NotMigratedYet("插件")` 占位**（`desktop/.../Main.kt:252`）。

### 1.3 为什么只能做 JS 轨

- APK 轨依赖 `DexClassLoader`，JVM 上无解。
- JAR 轨属于 M6，不在 M5 范围。
- M5 的 DoD 只要求 `.jsplugin`。

**结论：M5 桌面端只支持 `.jsplugin`。** 在 UI 上对 `.apk`/`.ext` 给出明确拒绝文案，而不是静默失败。

### 1.4 为什么坚持「一份运行时」

M4 已经踩过两套并行实现的坑：Android 的 `RulesViewModel` 自定义了一套 `RulesEditUiState`，与 shared 的 `RulesController` 字段对不上，导致 30+ 编译错误。插件逻辑远比规则复杂，如果桌面端另写一份轻量宿主，等于主动再制造一次同样的问题，且日后两处 bug 分别修的成本是持续的。

**所以：JS 插件运行时下沉到 `:shared` commonMain，Android 侧改为调用共享实现，不留第二份。**

---

## 二、关键设计决策

### 2.1 `PluginHost` 与 `PluginInvoker` 拆成两个接口（用户已确认选 B）

理由：`PluginHost` 的原注释明确界定职责是「装了什么、信不信、登没登录」这类**与平台无关的编排**。而登录、拉数、详情页区块属于另一类职责——要 `PluginContext`、要碰数据库、要管 60 秒超时。

| 接口 | 位置 | 职责 | 方法 |
|---|---|---|---|
| `PluginHost` | `shared/platform/PluginHost.kt` | 生命周期编排 | 现有 11 个：`refresh`/`pending`/`broken`/`install`/`confirmOverwrite`/`discardStaged`/`trust`/`revokeTrust`/`uninstall`/`uninstallPending`/`clearCredentials` |
| `PluginInvoker`（新） | `shared/plugin/PluginInvoker.kt` | 业务调用 | `login`/`logout`/`authState`/`detailUi`/`onAction`/`collectNow` |

拆开的好处：桌面端可以先只实现 `PluginHost`，`PluginInvoker` 先给一个返回安全默认值的实现（`login → Failure("插件运行时未就绪")`、`detailUi → emptyList()`），页面能正常打开，不会因为「没实现」整页崩。

`PlatformDependencies` 增加 `val pluginInvoker: PluginInvoker` 字段。

### 2.2 `org.json` 不替换，改为 expect/actual 收口

这是相对上一轮方案的重要修正。

`JsTypeConverter.kt` 有 500+ 行密集的 `JSONObject`/`JSONArray` 逐字段兜底逻辑，是插件的容错边界。整体重写等于把已跑通的容错推倒重来，收益只是「少一个 expect」，代价是小米插件要重新排查一轮。

做法：
```
shared/commonMain/plugin/json/PluginJson.kt        expect class PluginJsonObject / PluginJsonArray
shared/androidMain/plugin/json/PluginJson.android.kt   actual → 委托 org.json
shared/desktopMain/plugin/json/PluginJson.desktop.kt   actual → 委托 kotlinx.serialization
```
接口只需覆盖 `JsTypeConverter` 实际用到的子集：`optString`/`optInt`/`optLong`/`optBoolean`/`optJSONObject`/`optJSONArray`/`has`/`length`/`get`，`JSONArray` 的 `optJSONObject(i)`/`optInt(i)`/`length()`，以及 `JSONObject.quote`。

需要改动的文件只有 4 个：`JsPluginManifest.kt`、`JsTypeConverter.kt`、`JsSourcePlugin.kt`、`JsPluginContextBridge.kt`。

### 2.3 文件与 ZIP 抽成接口，不用 expect/actual

`:shared` 的 androidMain 和 desktopMain 都跑在 JVM 上，`java.io.File`/`java.util.zip.ZipFile` 两端都能用。所以**不需要 expect/actual**，直接在 commonMain 定义接口、两端各给一个实现更干净：

```kotlin
interface PluginFileSystem {
    fun pluginsDir(): String              // 插件包与解压根目录
    fun listArchives(suffix: String): List<String>
    fun readArchive(archiveName: String): Map<String, ByteArray>   // 解 ZIP + 路径穿越防护
    fun writeArchive(archiveName: String, bytes: ByteArray)        // 原子写入
    fun deleteArchive(archiveName: String): Boolean
    fun extractDir(archiveName: String): String
    fun deleteDir(path: String)
    fun pluginDataDir(pluginId: String): String
}
```

Android 实现用 `context.filesDir`，桌面用 `~/.lifeboard/plugins`。

### 2.4 信任存储改为依赖注入，不用 SharedPreferences

`PluginTrustStore` 现在用 `SharedPreferences`。改成接受一个 `SettingsStore`（M4-4.4 已有，Android 用 SharedPreferences、桌面用属性文件），键值依然是「packageName → 签名哈希集合」的序列化文本。

### 2.5 日志走已有的 `AppLogger.Sink`

`AppLogger` 现在硬编码 `android.util.Log`。M4-4.4 已经给桌面端做了文件 `Sink`，把 `AppLogger` 下沉到 commonMain、`platform` 日志输出即可。这一步是 M5 的前置，工作量小。

### 2.6 运行时容器 `JsPluginRuntime` 几乎零改动

它只依赖 `Executors`/`CoroutineScope`/`JsRuntime`，全在 JVM 上可用。搬进 commonMain 只需删掉 `internal`（跨模块访问）或改成 `public`。

### 2.7 桌面端不实现 `WorkManager`，采集走进程内协程

Android 的 `DailyAggregateWorker` 依赖 `WorkManager`。桌面端 M4-4.5 已有 `Scheduler`（进程内协程）。`collectNow` 在桌面端实现为「直接在协程里跑一遍采集链路」，不排队、不持久化——与 M7-7.1「应用运行时边界」的定位一致。

---

## 三、任务拆分（WBS）

| ID | 任务 | 关键文件 | 依赖 |
|---|---|---|---|
| **5.1** | `AppLogger` 下沉 commonMain + 平台输出 | `server/log/AppLogger.kt` → `shared/commonMain/log/` | — |
| **5.2** | `PluginJson` expect/actual 收口（4 文件改 import） | `shared/.../plugin/json/` | — |
| **5.3** | `PluginFileSystem` 接口 + 两端实现 | `shared/.../plugin/fs/`、`desktop/.../DesktopPluginFileSystem.kt` | — |
| **5.4** | 下沉 JS 轨纯逻辑到 commonMain：`JsPluginManifest`、`JsTypeConverter`、`JsPluginApi`、`JsPluginRuntime`、`JsSourcePlugin`、`JsPluginContextBridge`、`CompositeHostDispatcher`、`JsHttpBridge`、`JsCryptoBridge`、`JsPluginLoader` | 见 2.2/2.3/2.6 | 5.1–5.3 |
| **5.5** | `PluginTrustStore` 改 `SettingsStore` 注入；`HostPluginContext` 下沉（`Context` → `PluginFileSystem` + `CredentialStore` + `MetricStore`） | `shared/.../plugin/` | 5.3 |
| **5.6** | 新增 `PluginInvoker` 契约 + `PlatformDependencies` 加字段 | `shared/.../platform/PluginInvoker.kt`、`PlatformDependencies.kt` | — |
| **5.7** | 桌面 `DesktopPluginHost` + `DesktopPluginInvoker`：替换 `UnsupportedPluginHost` | `desktop/.../plugin/` | 5.4–5.6 |
| **5.8** | `DatabasePanelScope.pluginMetrics()` 真实实现（读 `plugin_raw_record` + 聚合） | `shared/.../panel/DatabasePanelScope.kt` | 5.7 |
| **5.9** | 共享 `PluginScreen` + `PluginViewModel` 搬进 commonMain，只认 `PluginHost`/`PluginInvoker` | `shared/.../ui/plugin/` | 5.6 |
| **5.10** | 桌面 `Main.kt` 注册插件页（替换 `NotMigratedYet`） | `desktop/.../Main.kt:252` | 5.7、5.9 |
| **5.11** | Android `PluginViewModel` 改走契约；`AndroidPluginHost` 补 `pluginInvoker` | `app/.../ui/plugin/PluginViewModel.kt`、`AndroidPluginHost.kt` | 5.9 |
| **5.12** | 双端测试：共享测试数据 + `verify-offline.mjs` | `shared/.../plugin/*Test.kt` | 5.4–5.8 |
| **5.13** | Android 回归验证（重点：小米健康插件） | 手工 | 5.11 |

**执行顺序固定**：5.1→5.2→5.3→5.4→5.5→5.6→5.7→5.8→5.9→5.10→5.12→5.11→5.13。

**5.11/5.13 必须排在最后**。原因：先在桌面端按契约跑通并验证，万一 Android 侧切契约出问题，桌面成果不跟着回滚。

---

## 四、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| `JsTypeConverter` 换 JSON 实现导致字段解析行为差异 | 小米插件登录/拉数失败 | 5.2 做完后立刻跑一遍现有 Android 测试；`PluginJson` 的 `actual` 用 `org.json` 语义对齐 `optString` 默认值 |
| 下沉改动面大，Android 编译长时间红 | 阻塞其它工作 | 每个子项下沉后**立即**跑 `:app:compileDebugKotlin`；不做「攒一批再验证」 |
| 桌面端 ZIP 解包与 Android 行为不一致 | 包能装不能跑 | `PluginFileSystem` 的路径穿越防护与 16MB/512 条目上限写成共享常量，两端实现必须复用 |
| 桌面 `collectNow` 无 `WorkManager` 兜底，长任务卡 UI | 点「同步」界面假死 | 桌面实现强制 `Dispatchers.IO` + 现有 `TaskCenter` 进度提示 |
| 小米健康真实拉数需人工凭据 | 自动化测不到 | 单列为 5.13 手工验证项，不进 CI |

---

## 五、验证方式

### 5.1 每步必跑（不可攒批）
```
gradlew.bat :shared:compileKotlinDesktop :app:compileDebugKotlin --console=plain
```

### 5.2 阶段验证
| 阶段 | 命令 | 预期 |
|---|---|---|
| 5.2 完成 | `:app:testDebugUnitTest` | 现有插件相关测试不回归 |
| 5.4 完成 | `:shared:allTests :desktop:desktopTest` | `DesktopJsRuntimeTest` 6 项 + 新增共享测试全绿 |
| 5.7 完成 | `:desktop:compileKotlinDesktop` | 通过 |
| 5.10 完成 | `:desktop:run` | 插件页不再是「未迁移」占位，能打开 |
| 5.12 完成 | `:shared:allTests` + `node verify-offline.mjs` | 全绿 |
| 5.13 | `:app:assembleDebug` + 手装 | 小米健康插件登录拉数正常 |

### 5.3 DoD 对照
- [ ] `.jsplugin` 在桌面可安装（选文件 → 待信任列表出现）
- [ ] 可信任（看内容哈希 → 点信任 → 进入已装列表）
- [ ] 可登录（登录表单弹出 → 提交 → 显示已登录）
- [ ] 可拉数（点同步 → `metric` 表写入 → 面板卡片出数）
- [ ] Android 侧小米健康插件行为不变

---

## 六、约束（执行时遵守）

- 所有输出中文
- `gradlew` 命令需 `dangerouslyDisableSandbox: true`
- **严禁并发启动多个 Gradle 构建**（损坏 Kotlin 增量缓存）
- 只用 PowerShell，无 Bash
- 不用 `find`/`grep`/`cat`/`sed`/`awk`，改用 Glob/Grep/Read/Edit
- 每完成子项立即回写 P_plan.md / index.md / E_execution.md / MasterPlan.md

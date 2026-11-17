# 清除 APK 插件轨道残留

## Context

用户希望删除 APK 插件方案，只保留 JS 插件。

调研后发现一个重要事实：**APK 插件的功能本体早已不存在**。`PluginManager.kt:45-48` 明确记录「APK 轨（`.apk` + `DexClassLoader`）已于 M6b 移除」，`PluginLoader.kt`、`PluginInstaller.kt`、`PluginArchiveInfo.kt`、`PluginVersionPolicy.kt`、`:demo-plugin` 模块、6 个 APK androidTest 全部已删。

所以本轮不是「拆除一条轨道」，而是**清掉拆完之后没打扫干净的残渣**。残渣分三类：

1. **过时注释**（约 20 处）——代码早就不这么做了，注释还在描述 `DexClassLoader`、`.ext` 插件。这是最有害的一类：后来者读注释会以为存在一套不存在的机制。
2. **死字段 `formatLabel`**——只用于区分「APK 插件」/「JS 插件」，单轨后恒为同一个值。`PluginManager.kt:504` 自己写着「单轨之后不再需要 `formatLabel`」，但字段一直没删，横跨 shared / desktop / app 三端。
3. **死函数 `checkUpdateSignature`**——原属 APK 安装期校验，现在**零生产调用点**，只有测试在调它。
4. **`plugin-api/PLUGIN_SDK.md` 整篇过时**——它仍在教人写 `.ext` 插件、配 `DexClassLoader` 父加载器、用 `compileOnly(project(":plugin-api"))` 打 APK。这份文档现在没有任何正确读者。

**必须保留**（已实际确认，不是猜测）：

- `plugin-api` 全部 13 个契约文件——`SourcePlugin` 被 `JsSourcePlugin.kt:42` **实际实现**，`SourceRef` / `MetricSpec` / `FetchResult` 均被 JS 轨使用。它是插件协议的唯一定义，删了 JS 插件无法工作。
- `PluginPackageInfo.isContentAddressed` 与 `PluginTrust.evaluate` 里的分支——防「JS 更新后永久装不上又删不掉」死锁的护栏。
- Xposed 那套（`app/.../xposed/`、`compileOnly(libs.legacy.xposed.api)`）——**和 APK 插件是两件不同的事**，它 hook 系统通知用于采集第三方 App 数据，与插件格式无关。`app/build.gradle.kts:49` 的 `ByteBufferDexClassLoader` 也是 LSPosed 加载模块用的，不是插件加载器。
- `QUERY_ALL_PACKAGES` 权限——用于采集规则展示已安装应用列表（`AndroidAppCatalog.kt:32`），与插件无关。

## 实施步骤

### 第一步：删除死字段 `formatLabel`

`PluginPackageInfo.kt:66-67` 删接口字段。然后按编译错误逐个清理实现点：

| 端 | 文件 | 改动 |
|---|---|---|
| shared | `plugin/PluginPackageInfo.kt` | 删接口字段与 KDoc |
| shared | `platform/PluginHost.kt:40,51` | 删宿主侧透传字段 |
| shared | `ui/plugin/PluginViewModel.kt:68,76` | 删状态字段与赋值 |
| shared | `plugin/js/JsPluginManifest.kt` | 删 override |
| app | `server/platform/AndroidPluginHost.kt:69,83,97,174` | 删 override 与 `FORMAT_LABEL` 常量 |
| app | `server/plugin/PluginManager.kt:153,164,175,184` | 删映射 |
| app | `server/plugin/PluginPackageInfo.kt` | 删 typealias |
| desktop | `plugin/DesktopPluginHost.kt:26,58,73,145` | 删 override |
| desktop | `plugin/DesktopPluginRepository.kt:351,362,379` | 删映射 |

同时删除 `PluginManager.kt:490-510` 那段「单轨之后不再需要 formatLabel」的说明性 KDoc——字段删了，注释也就没有存在意义。

测试同步：`PluginTrustTest.kt:42,62`（`FakePackage` 与 `asContentAddressed` 两个 override）、`JsPluginLoaderTest.kt:102`、`JsPluginInstallServiceTest.kt:289`。

### 第二步：删除死函数 `checkUpdateSignature`

`PluginTrust.kt:71-90` 整个函数删除。它已无生产调用点（Grep 确认仅 `PluginTrustTest.kt:175-197` 引用）。

连带删 `PluginTrustTest.kt` 里 4 个 `updateSignature_*` 用例。其中 `updateSignature_matchesLoadStageVerdict` 有个值得保留的意图——「安装期放行的包加载期不能反过来说签名不对」。但安装期校验已不存在，这个一致性约束失去对象，直接删。

### 第三步：清理过时注释

按「注释在描述已不存在的机制」这个标准逐条改，不做无意义的措辞润色。重点：

| 文件 | 行 | 现状问题 |
|---|---|---|
| `app/.../plugin/AndroidPluginDetailStore.kt` | 14 | 说「插件跑在独立 DexClassLoader 里」 |
| `app/.../app/LifeBoardApp.kt` | 24, 42, 76 | 说「实例化插件要跑 DexClassLoader」、小米健康改成 `.ext` 插件 |
| `app/.../source/SourceProviderRegistry.kt` | 41 | 说小米健康「已迁成 `.ext` 插件」 |
| `app/.../mihealth/MiHealthMigration.kt` | 13 | 说「M5 起由 `.ext` 插件负责」 |
| `shared/.../plugin/PluginPackageInfo.kt` | 8-21 | 整段「两种形态」的框架说明 |
| `shared/.../plugin/fs/PluginFileSystem.kt` | 90 | 说后缀「与 APK 轨的 `.ext` 区分」 |
| `shared/.../androidMain/plugin/fs/AndroidPluginFileSystem.kt` | 25 | 说插件目录「与 APK 轨分开」 |
| `shared/.../platform/PluginHost.kt` | 73 | 说 `PluginManager` 「依赖 DexClassLoader」 |
| `shared/.../jvmMain/plugin/js/JsPluginLoader.kt` | 31, 82 | 对比 APK 加载器 |
| `shared/.../jvmMain/plugin/js/JsPluginApi.kt` | 8, 50 | 说「APK 插件用 PluginApi.VERSION」 |
| `desktop/.../plugin/DesktopPluginRepository.kt` | 46, 400-401 | 说「那头还挂着 APK 轨的字段，共用会绑到 DexClassLoader」——`formatLabel` 删完后这句更该改 |
| `desktop/.../plugin/DesktopPluginHost.kt` | 28-30 | 说「APK 轨依赖 DexClassLoader，JVM 无等价物」 |
| `app/.../plugin/LoadedPlugin.kt` | 18 | 说「APK 轨移除后只剩 JS」——可精简 |
| `app/.../plugin/js/JsPluginLoader.kt` | 43 | 说职责「从 PluginLoader 挪来」 |
| `app/.../test/PluginIdSafetyTest.kt` | 10 | 说 pluginId「来自 `.ext` 的 manifest」 |
| `PluginManager.kt` | 419, 504 | APK 轨叙述 |

`plugin-api/PluginContext.kt:22` 说「同时供 Android（APK 插件）与桌面（JAR 插件）使用」——JAR 插件也不存在，一并改。

`PluginManager.kt:419` 关于「只接受 `.jsplugin`，非 JS 包给明确提示」的部分**要保留**：拒绝逻辑仍然有效且有价值（用户拖个 .apk 进来要看到人话），只把「APK 轨移除后不再有格式分派」这种历史叙述改掉。

`.gitignore:25-26` 的 `.ext` 忽略规则也删——轨已不存在两年，工作区不会再有残留。

### 第四步：重写 `plugin-api/PLUGIN_SDK.md`

现有内容（95 行）教的是 APK 插件开发：`DexClassLoader` 父加载器、`.ext` 是改后缀 APK、`META-INF/lifeboard-plugin.properties` 定位入口类。

重写为 **JS 插件作者的契约参考**，保留其中仍然正确的部分：

- 保留「契约要点」三节（指标声明与产出、登录态与健康度、凭据）——`MetricSpec`/`AuthState`/`PluginHealth`/`CredentialSpec` 这些模型 JS 侧仍在用
- 保留「不需要做到的、以及必须诚实说明的」中的**没有沙箱**一条（JS 插件同样同进程无隔离），删掉进程隔离相关的 APK 特有表述
- 删掉 compileOnly / dex / .ext 目录约定 / 入口类 properties 全部内容
- 示例指向 `js-plugins/mihealth/`（已在文末，保留）

若觉得重写风险大，替代方案是直接删除该文件，把契约说明合并进 `js-plugins/宿主API变更记录.md`。我倾向重写，因为 JS 插件作者确实需要一份契约参考。

## 验证

```powershell
# 1. 四模块编译（formatLabel 跨三端，必须全过）
.\gradlew :core:compileKotlinDesktop :shared:compileKotlinDesktop :desktop:compileKotlinDesktop :app:compileDebugKotlin --console=plain -q

# 2. 桌面三模块测试
.\gradlew :core:desktopTest :shared:desktopTest :desktop:desktopTest --console=plain

# 3. 确认 formatLabel / checkUpdateSignature 零残留
#    用 Grep 搜这两个词，应只在文档历史记录中出现

# 4. 确认改动没碰 Xposed
#    用 git diff 确认 app/.../xposed/ 与 app/build.gradle.kts 未被修改
```

`app:testDebugUnitTest` 仍会被三个历史坏损测试（`FormEncodeTest` / `JsHttpBridgeTest` / `JsHttpSessionTest`）阻塞，这与本次改动无关，不在本轮修。

## 不做的事

| 项 | 为什么 |
|---|---|
| 删 `plugin-api` 模块 | JS 插件实际依赖它，`JsSourcePlugin` 实现了 `SourcePlugin` |
| 删 `isContentAddressed` | 防止 JS 更新后死锁，是有效护栏 |
| 动 Xposed 相关任何代码 | 与插件格式无关，是第三方 App 数据采集通道 |
| 删 `QUERY_ALL_PACKAGES` | 用于采集规则展示应用列表 |
| 修那三个历史坏损测试 | 需要按新 API 重写测试体，与本轮目标无关 |

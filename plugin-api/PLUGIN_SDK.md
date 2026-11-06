# 插件构建约定

本文写给两类读者：写宿主加载逻辑的人，和写 `.ext` 插件的人。核心只有一句——**插件不打包 `:plugin-api`，靠宿主提供**。

## 为什么 `:plugin-api` 必须是 compileOnly

`:plugin-api` 里全是接口、数据类、sealed class。插件和宿主必须在**同一份实现**上相遇：

- 如果插件把 `:plugin-api` 打进自己的 dex，那它会有自己的一份 `SourcePlugin`、`MetricSpec`。
  加载时 `instance is SourcePlugin` 判的是宿主那份，插件的类不满足，`instanceof` 直接返回 false，
  加载器只能报「入口类未实现 SourcePlugin」——而插件作者看着自己的代码明明是实现了的。
- 更隐蔽的是 `sealed class` 与 `data class`：即便绕过类型检查，插件构造的 `PluginHealth.Broken`
  也是另一个类，宿主的 `when` 分支走不到，表现为「状态永远对不上」。

因此宿主在 `PluginLoader` 里把 `DexClassLoader` 的父加载器设成 `context.classLoader`，
插件里所有 `com.lifeboard.plugin.api.*` 的符号在运行期都解析到宿主已加载的那份。

## 插件的 build.gradle 写法

```kotlin
dependencies {
    // 必须是 compileOnly：只在编译期提供符号，不打进 dex
    compileOnly(project(":plugin-api"))

    // 插件自己的依赖（网络、序列化等）按需 implementation，
    // 会被打进插件 dex，随 .ext 一起分发
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

用 `implementation` 或 `api` 包 `:plugin-api` 都会导致上面说的类型不匹配，**没有运行期补救办法**。

## 插件包的目录约定

`.ext` 是改了后缀的标准 APK。除了正常的 `AndroidManifest.xml` 和 `classes.dex`，
加载器还要求：

```
META-INF/lifeboard-plugin.properties
```

内容至少包含入口类：

```properties
entryClass=dev.foo.demo.DemoPlugin
```

这个文件是加载器定位入口的唯一依据。读不到就判 `BROKEN` 并给出明确原因，
**不会扫描全包类名去猜**——猜错会把一个无关的类实例化，错误信息也更难懂。

## 不需要做到的、以及必须诚实说明的

- **没有沙箱**：插件与宿主同进程、同 UID、同权限，可以读宿主数据目录，可以反射访问宿主内部。
  签名校验只解决「包有没有被替换」，不解决「作者是否作恶」。产品文案不得写「隔离」「安全沙箱」。
- **没有进程隔离**：插件 OOM 或 native crash 会连同宿主一起被杀。加载器的 `try/catch`
  只能兜住受控异常（构造抛错、类型不符、契约版本不一致），兜不住致命崩溃。
- **契约版本单版本策略**：`apiVersion` 与宿主不一致直接拒绝加载，不做多代并存。
  这意味着宿主升契约版本时，旧插件必须跟着升。

## 契约要点

### 指标声明与产出

`metrics: List<MetricSpec>` 声明插件会产出哪些指标，每个指标带唯一 `metricKey` 与固定 `bucket`。
`fetch()` 返回的 `PluginMetric` **只有 `metricKey` 和 `value`，不带量纲**——量纲一律以声明为准。

这样设计是因为量纲决定数据写进 `metric` 表的哪一列，一旦声明与产出可以不一致，
就会出现同一份数据被两种口径解读。宿主遇到未声明的 `metricKey` 会丢弃并记告警，不会猜测补一个量纲。

`metricKey` 与插件 `id` 都不得为空、不得含 `:`（`:` 是 `PLUGIN_{id}:{metricKey}` 的分隔符）。
三者的冲突校验由 `PluginMetrics.validate()` 提供，插件可以在自己的测试里先跑一遍。

### 登录态与健康度

- `AuthState` 有 `LoggedOut` / `LoggedIn(accountName)` / `Expired(message)` 三态。
  `Expired` 不是 `LoggedOut`：凭据过期但可自动续期时报它，宿主会显示「将自动续期」而不是催用户重新登录。
- `PluginHealth` 有 `Healthy` / `Degraded(message)` / `Broken(message)`。
  宿主把 `PluginHealth` 与 `AuthState` 组合成最终健康度写进 `source_health` 表。
- 这两个方法都可能发网络请求，**不要求快**，但必须自己兜住异常：抛异常会被宿主当作 `Broken`，
  原因只显示「健康检查异常」，对用户没有信息量。

### 凭据

`CredentialStore` 的每个方法都没有 `pluginId` 参数——命名空间在构造 `PluginContext` 时就绑定好了。
插件只能读写自己名下的槽位，这条约束由接口形状保证，不靠调用方自觉。

`CredentialSpec.slots` 只用于 UI 展示「清除凭据会影响哪些项」，宿主不校验插件是否只用了声明的槽位。

## 示例

`demo-plugin/` 是一个最小可用的插件模块，可作为模板参考。
它不联网、不需登录，只产出固定值，用来验证「加载 → 信任 → 采集 → 入库」这条链。

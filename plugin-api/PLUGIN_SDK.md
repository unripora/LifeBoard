# 插件契约说明

本文写给**写插件的人**与**改宿主的人**。

前提：宿主只跑 JS 插件。历史上的 Android APK 插件轨（`.ext` + `DexClassLoader`）已于 M6b 移除，
本文不再涉及那一套。参考实现是 `js-plugins/mihealth/`——真实的登录、验证码、多指标采集链路都在里面。

## `:plugin-api` 是什么

`:plugin-api` 是宿主与插件的契约模块，只有接口、数据类、sealed class，没有依赖、没有实现。

JS 插件在运行期并不加载它（JS 没有 Kotlin 类型）。它对 JS 插件的意义是**权威定义**：
`ctx.*` 注入的函数、manifest 里的字段、`fetch` 的返回结构，都由这里的类型描述和约束。
改这里就是改契约，JS 插件要跟着改。

宿主侧 `JsSourcePlugin` 实现了 `SourcePlugin` 接口，把 JS 的返回值翻译成这些类型。
所以契约改动会在宿主这一侧表现为编译错误，不会静默漏过。

## 契约要点

### 指标声明与产出

`metrics: List<MetricSpec>` 声明插件会产出哪些指标，每个指标带唯一 `metricKey` 与固定 `bucket`。
`fetch()` 返回的 `PluginMetric` **只有 `metricKey` 和 `value`，不带量纲**——量纲一律以声明为准。

这样设计是因为量纲决定数据写进 `metric` 表的哪一列，一旦声明与产出可以不一致，
就会出现同一份数据被两种口径解读。宿主遇到未声明的 `metricKey` 会丢弃并记告警，不会猜测补一个量纲。

`metricKey` 与插件 `id` 都不得为空、不得含 `:`（`:` 是 `PLUGIN_{id}:{metricKey}` 的分隔符）。
冲突校验由 `PluginMetrics.validate()` 提供，插件可以在自己的测试里先跑一遍。

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

### 契约版本

两套版本号，不要混用：

| 版本号 | 管什么 | 位置 |
|---|---|---|
| `PluginApi.VERSION` | 编译期契约：插件实现了哪些方法、类型长什么样 | `plugin-api` |
| `JsPluginApi.CURRENT` | 运行期函数集：宿主注入了哪些 `ctx.*` 函数 | `shared/jvmMain` |

JS 插件读的是后者。宿主少一个注入函数、或某个函数的参数形态变了，插件就会在运行期
以 `undefined is not a function` 炸在业务代码中间，报错点离真正原因很远——所以用独立版本号在加载前拦住。

契约版本是**单版本策略**：`apiVersion` 与宿主不一致直接拒绝加载，不做多代并存。
宿主升契约版本时，旧插件必须跟着升。

## 必须诚实说明的

- **没有进程隔离**：JS 插件跑在宿主进程的 QuickJS 运行时里。脚本 OOM 会连同宿主一起受影响，
  加载器的 `try/catch` 只能兜住受控异常（脚本抛错、manifest 不合法、契约版本不一致），兜不住致命问题。
- **JS 沙箱提供的屏障是 API 面，不是权限**：脚本只能通过宿主显式注入的 `ctx.*` 触达外部能力，
  拿不到文件系统与网络 API。但这不等于安全隔离——宿主注入的每个函数都是脚本可用能力的上限，
  而注入面由宿主决定。产品文案不得写「安全沙箱」。
- **信任指纹是内容哈希，不是签名**：`.jsplugin` 的指纹来自包内文件的 SHA-256。
  它能证明「包没被换过」，不能证明「作者是谁」。首次安装时的信任由用户手动确认。

## 示例

```
js-plugins/
└── mihealth/
    ├── manifest.json   # 声明 id / metrics / credentialSpec / apiVersion
    └── main.js         # 实现 fetch / authState / health
```

读之前先看 `manifest.json` 的 `credentialSpec` 与 `main.js` 的 `fetch` 实现。

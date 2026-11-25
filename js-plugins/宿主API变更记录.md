# 宿主 API 变更记录

给插件作者看的。「宿主」指 LifeBoard App 本身，本文档记录它向插件开放的能力
在什么时候、以什么方式变了。

配套阅读：[插件编写指南](./插件编写指南.md)（怎么用）、[manifest.json 与版本](#两套版本号不要混)（本文第二节）。

---

## 两套版本号，不要混

插件的 manifest 里有 `apiVersion`，那是**JS 接口版本**，管的是 `ctx.*` 这一套函数。
它和 App 的版本号（`versionName`）、以及 APK 插件的契约版本是三个独立的编号。

| 版本号 | 在哪 | 管什么 | 谁递增 |
|---|---|---|---|
| `manifest.apiVersion` | 插件的 `manifest.json` | `ctx.*` 函数集 | 宿主，见下表 |
| App `versionName` | LifeBoard 的安装包 | 整个应用 | 宿主发布时 |
| `PluginApi.VERSION` | APK 插件的编译期常量 | `SourcePlugin` 的 Kotlin 方法签名 | 宿主，仅破坏性变更 |

**插件声明 `apiVersion` 高于本机 App 支持的版本时会被拒绝加载**，并提示用户升级 App。
低于或等于则正常加载——新能力是纯增量，老插件不需要跟着改。

> 不确定该填哪个版本：填你**实际用到的最新能力的版本**。只用 v1 的函数就填 `1`，
> 用到表单编码就填 `2`。填高了会让老版本 App 拒绝加载，填低了不会出问题。

---

## JS 接口版本（`manifest.apiVersion`）

### v4 — 详情页改为可组合节点树 + `ctx.report`

**破坏性**：`detailUi` 不再返回 9 种固定区块（`text`/`statusCard`/`buttonRow`/`chart`/
`customImage`/`messageList`/`inputBox`/`approval`/`conversation`），改为返回节点树。
旧 `type` 会被渲染成「此版本暂不支持」的占位，**不再有对话区**。请按
[插件编写指南](./插件编写指南.md) 的「详情页 UI：可组合节点树」改写。

| 变化 | 说明 |
|---|---|
| `detailUi` 返回 `UiNode` 或 `UiNode[]` | 容器（`column`/`row`/`collapsible`/`spacer`/`divider`）+ 内容 + 交互 + 媒体节点，可任意嵌套 |
| `{binding:'<source>:<key>:<field>'}` | 新增绑定点：只允许用在 `list.items` / `text.body` / `statusCard.value` / `input.enabled`。本期数据源仅 `session`（`messages`/`status`/`inputEnabled`） |
| `input.submitTo` | 输入直接投给数据源（会话就是这么收输入的），不进 `onAction` |
| `onAction` 的 `refreshUi` | 字段由 `blocks` 改为 `root`（单棵节点树） |
| `ctx.report(key, value, at?)` | **新增**：事件型指标的即时累加上报。同一天多次调用累加；key 须在 `manifest.metrics` 声明 |
| `ctx.stream.emit` | **函数保留、详情页不再显示**。原有落点（往最后一个消息列表追加）随固定区块一起删除；流式文本请用 `ctx.session.emit` 的 `delta` |

### v2 — 表单、超时重试、错误分类、二进制文件

纯新增，**不破坏 v1**。声明 `1` 的插件照常工作。

| 新增 | 说明 |
|---|---|
| `ctx.http.form(params)` | 表单编码，支持对象与 `[[key, value]]` 二元组数组 |
| `ctx.http.postForm(url, params, options, session)` | 表单 POST，自动带 `application/x-www-form-urlencoded` |
| `ctx.http.lastError()` | 上一次失败的稳定分类：`timeout` / `connect` / `dns` / `tls` / `other` |
| `options.timeoutMs` | 单次请求超时（1s～180s） |
| `options.retry` | 是否允许网络层重试；默认只对 GET/HEAD/OPTIONS 开启 |
| `ctx.files.readBase64(path)` | 读二进制文件 |
| `ctx.files.writeBase64(path, b64)` | 写二进制文件 |

**为什么加这些**：这四项是写插件时几乎必然撞上的空白，而每一项都有「抄错了也不报错、
只是在服务端表现为参数错误」的特征。收进宿主是为了让每个插件不用各抄一份。

### v1 — 初始版本

凭据、日志、基础 HTTP（含会话与 cookie）、crypto、codec、历史数据、发消息。

---

## 行为变更（不影响版本号，但会影响你的插件）

这类变更不新增函数、不改签名，但**同一个调用现在表现不同**，需要留意。

### `fetch` 的返回值：增加了失败语义

`fetch` 现在可以返回带 `status` 的对象，用来区分「没数据」和「没取到」：

```js
{ status: 'empty', reason: '当天没有运动记录' }
{ status: 'temporary', message: '请求超时', retryAfterSeconds: 60 }
{ status: 'authRequired', message: '登录已过期' }
{ status: 'failure', message: '接口结构变了' }
```

**裸数组仍然合法**，等同于 `{ status: 'success', metrics: [...] }`，现有插件不用改。

但两件事变了：

1. **`status` 写错现在是失败，不再当成成功**。写 `sucsess` 会得到一条明确的
   「无法识别的 status」错误，而不是静默变成「今天没数据」。
2. **`fetch` 抛异常不再等同于空列表**。抛错会被记为一次失败并在面板上显示原因，
   用户能看出「这次没取到」。

建议（不是必须）把「不确定有没有」的情况从返回空数组改成 `temporary`，
这是本次变更最想解决的问题——见[插件编写指南 · fetch](./插件编写指南.md#export-async-function-fetchdate-ctx)。

---

## APK 插件契约（`PluginApi.VERSION`）

与 JS 插件无关，只有写 Kotlin 插件的人需要关心。

### v5 — 详情页改为可组合节点树

`SourcePlugin.detailUi` 由 `List<UiBlock>` 改为返回 `PluginNode?`，`ActionResult.RefreshUi`
的字段由 `blocks` 改为 `root`。旧 `UiBlock` 系列类型**整体删除**，不保留兼容层。

对**编译型**插件是二进制破坏（旧插件编译出 `()Ljava/util/List;`，新宿主调
`()Lcom/lifeboard/plugin/api/PluginNode;`），必须重新编译。

> ⚠️ `PluginApi.MIN_VERSION` 已抬到 5，但**当前仓内没有任何代码读取它**——
> 也就是说它只是契约声明，不会真的拦下旧插件。别指望它挡人，靠发布新版插件包。

### v4 — `fetch` 返回 `FetchResult`

`SourcePlugin.fetch` 从 `List<PluginMetric>` 改为返回 `FetchResult`。

**这是破坏性变更**：已编译的旧插件在二进制层面就对不上，调用会抛 `NoSuchMethodError`。

### v3 — 详情页声明式 UI

新增 `detailUi` / `onAction` 与 `UiBlock`（v5 已删除，由 `PluginNode` 取代）、`ActionResult`。
两个方法都有默认实现，**非破坏性**：声明 2 的插件照常加载，只是详情页没有自己的区块。

### v2 — 多轮登录

`login` 增加 `inputs` 参数，`LoginResult` 增加 `NeedInput`（验证码、二次验证必须有地方表达）。
破坏性：旧插件实现的是单参数 `login`。

### v1 — 初始契约

---

## 给宿主维护者的两条规则

1. **新增能力不升版本号，也不升 `PluginApi.VERSION`**。只有删函数、改参数形态、
   改返回结构这类「老插件会崩」的变更才升。
2. **只有二进制层面的破坏才抬 `PluginApi.MIN_VERSION`**。为了加一个带默认实现的方法
   就提高最低版本，会让用户手上所有插件同时失效——换不来任何安全性。

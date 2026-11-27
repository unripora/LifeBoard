# 03 · 宿主 API（`ctx.*`）

> **单一事实源**：`shared/src/jvmMain/.../plugin/js/JsHostFunctions.kt`
> 类型声明：`js-plugins/types/ctx.d.ts`（与宿主函数集由测试双向锁定）
> 用法与示例见 [插件编写指南](../插件编写指南.md)

## 分组

| 组 | 数量 | 说明 |
|---|---|---|
| `ctx.credentials.*` | 5 | 凭据读写（加密存储） |
| `ctx.files.*` | 5 | 插件私有目录读写（明文） |
| `ctx.recentMetrics` / `ctx.report*` | 4 | 历史指标读取与上报 |
| `ctx.log.*` | 4 | 日志 |
| `ctx.pluginId` | 1 | 当前插件 id |
| `ctx.http.*` | 17 | HTTP、cookie、会话、自签 CA |
| `ctx.crypto.*` / `ctx.codec.*` | 12 | 摘要、随机数、base64、UTF-8 |
| `ctx.ws.*` | 6 | WebSocket 长连接 |
| `ctx.stream.emit` | 1 | 增量输出 |
| `ctx.session.emit` | 1 | 会话主动推送 |

具体函数名请查 `JsHostFunctions.kt`——**不要按本文档数量硬编码**，
新增能力会随时出现且不递增 `apiVersion`。

## 能力集合（`requires` 可匹配项）

宿主当前**已实现并验证**的能力名：

| 名称 | 含义 |
|---|---|
| `contract.errorCode` | 结构化错误码与错误信封 |
| `contract.loginIntent` | 登录意图（open/submit/restart/abandon） |
| `ctx.files.write` | 插件私有目录写入 |
| `ctx.credentials` | 凭据读写 |

**不在列表里的能力不要声明**。例如 `runtime.lifecycle` 目前只做到"解析声明"，
回收默认关闭，因此未列入——声明它会被拒载。

新增能力时：先在代码里实现并通过测试，再加入 `HostCapabilities.ALL`，
最后同步本表与 `ctx.d.ts`。**先加表后实现属于违约**。

## 调用约定

| 约定 | 说明 |
|---|---|
| 参数 | 位置参数；复杂结构传对象或 JSON 字符串 |
| 返回值 | 同步能力直接返回；异步能力返回 Promise |
| 错误 | `ctx.*` 失败抛异常；可用 `ctx.makeError(code, message)` 构造结构化错误 |
| 上下文 | `ctx` 只在方法调用期间有效；**不要**在模块顶层读取后缓存整个对象 |
| 并发 | 同一插件天然串行（单线程）；跨插件并发不受影响 |

## 线程与异步

- 插件跑在**专用单线程**上；`await` 宿主能力时该线程会让出，不会阻塞界面
- `ctx.ws.recv` 是**拉取模型**：后台循环靠宿主泵推进，不是事件回调
- 一次调用长期不返回会堵住同插件的其它调用（采集、健康检查排在后面）

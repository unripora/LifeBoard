# 05 · 错误码

> 权威来源：`plugin-api/.../PluginError.kt`

## 13 种错误码

| 码 | 何时用 | 宿主默认处置 | 可自动重试 |
|---|---|---|---|
| `AUTH_REQUIRED` | 从未登录 | 引导首次登录；不清除有效旧数据 | 否 |
| `AUTH_INVALID` | 账号/密码/验证码错 | 提示输入错误，**不重复提交** | 否 |
| `AUTH_EXPIRED` | 凭据过期 | 支持安全续期时至多续期一次，失败后引导登录 | 仅显式续期 |
| `UPSTREAM_ERROR` | 网络/超时/5xx | 记录故障，保留已有数据 | 仅幂等采集，有限退避 |
| `UPSTREAM_CHANGED` | 接口结构不符 | 提示更新插件 | 否 |
| `RATE_LIMITED` | 明确限流 | 按 `Retry-After` 安排下次 | 仅幂等采集 |
| `UNSUPPORTED` | 缺少所需能力 | 要求兼容插件或宿主 | 否 |
| `INVALID_ARGUMENT` | 参数/契约错误 | 显示参数错误，记录调用点 | 否 |
| `STORAGE_FULL` | 存储已满 | 停止新增写入，提示清理 | 否 |
| `RESOURCE_LIMIT` | 堆/载荷限额 | 区分种类；堆 OOM 淘汰实例 | 不重放原操作 |
| `INTERRUPTED` | 执行被中断 | 淘汰实例，终止相关会话 | 不重放原操作 |
| `UNAVAILABLE` | 关闭/更新/停用/预算不足 | 返回明确原因 | 仅尚未执行且安全的请求 |
| `INTERNAL_ERROR` | 未知内部错误 | 记录脱敏诊断并显示失败 | 否 |

**未知码一律回退 `INTERNAL_ERROR`**，保留脱敏诊断标识。
绝不能把未知码变成成功或空数据。

## `UPSTREAM_ERROR` vs `UPSTREAM_CHANGED`

分界是「**同样的输入重试，结果会不会变**」：

| 现象 | 归类 |
|---|---|
| 连接失败、超时、DNS 错误 | `UPSTREAM_ERROR` |
| HTTP 5xx / 网关 502、503 | `UPSTREAM_ERROR` |
| HTTP 429、明确限流头 | `RATE_LIMITED` |
| HTTP 401/403（凭据层面） | `AUTH_EXPIRED` / `AUTH_INVALID` |
| JSON 结构变了、字段没了、选择器取不到 | `UPSTREAM_CHANGED` |
| 响应是 HTML 而不是预期 JSON | `UPSTREAM_CHANGED` |
| 业务错误码 | **由插件判断**，不一律可重试 |

业务拒绝多数是确定性结果，重试只会重复消耗配额。不能确定是临时故障时，
保守返回 `UPSTREAM_ERROR` 并说明原因，不要笼统报 `UPSTREAM_CHANGED`
——后者会让用户去"更新插件"，方向完全错了。

## 构造错误

```js
throw ctx.makeError('UPSTREAM_CHANGED', '接口字段 steps 缺失');
```

或在结果里携带：

```js
return {
  status: 'failure',
  message: '接口格式可能已变化',
  error: { code: 'UPSTREAM_CHANGED', message: '字段 steps 缺失', operation: 'fetch' },
};
```

`message` 会展示给用户，**不得含凭据内容**；`reason` / `limitKind` 仅入日志。

## 取消不是错误

宿主主动取消（用户切页签、退出）**不会**转成任何错误码。
插件端无需处理 `CancellationException` 之外的特殊情况，也不该把它记成故障。

## 故障停用

`INTERRUPTED` / `RESOURCE_LIMIT` 连续出现 3 次会**停用插件**（见
[02-runtime §故障与停用](./02-runtime.md#六故障与停用)）。
认证失败、网络错误、普通关闭**不计入**连续故障。

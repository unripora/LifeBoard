# 04 · 导出函数契约

> 权威来源：`plugin-api/.../SourcePlugin.kt`、`js-plugins/types/plugin-contract.d.ts`

## 导出一览

| 导出 | 必需 | 说明 |
|---|---|---|
| `login(inputs?, options?)` | 是 | 多轮登录 |
| `logout()` | 是 | 清理凭据 |
| `authState()` | 是 | 登录态 |
| `fetch(date)` | 是 | 拉取指定日期数据 |
| `health()` | 是 | 健康度 |
| `detailUi()` | 否 | 详情页节点树；`null` 表示无自定义内容 |
| `onAction(actionId, value?)` | 否 | 详情页交互 |
| `sessionStart(config)` | 否 | 启动会话；必须**显式** `return true` |
| `sessionEvent(event)` | 否 | 投递会话事件 |
| `sessionStop()` | 否 | 结束会话，可能被重复调用 |
| `onUnload(options?)` | 否 | 卸载前尽力清理，见 [02-runtime](./02-runtime.md) |

## 共同约定

1. **不要抛异常**：异常会被宿主兜底成一句通用错误，用户看不到原因。
   失败请返回契约里的失败分支。
2. 返回值走 JSON 序列化；方法/`Map`/闭包**无法**被序列化。
3. 每次调用只处理一件事：一次调用长期不返回会堵住同插件的其它调用。

## 登录：多轮 + 意图

```js
export async function login(inputs, options) {
    const intent = options && options.intent;   // 'open' | 'submit' | 'restart' | 'abandon' | undefined
    ...
    return { type: 'needInput', prompt: '...', fields: [...] };
}
```

返回形态：

| 形态 | 含义 |
|---|---|
| `{ type: 'success' }` | 登录完成，凭据已写入 |
| `{ type: 'needInput', prompt, fields, captchaImage? }` | 还差用户输入 |
| `{ type: 'failure', message }` | 登录失败，`message` 直接展示 |

意图语义：

| intent | 何时传入 | 插件应做什么 |
|---|---|---|
| `open` | 用户打开登录入口 | **只读**：能恢复就展示恢复后的阶段；不提交、不清空 |
| `submit` | 用户填完提交 | 推进当前步骤 |
| `restart` | 用户点"重新输入账号" | 清理中间态，返回首轮 |
| `abandon` | 用户点"放弃登录" | 清理中间态；应答只确认清理，**不是**登录成功 |
| `undefined` | 旧宿主 | 保持旧语义（空表单 = 重新开始） |

**关闭表单不会传 `abandon`**：插件应保留中间态，等用户回来继续。
需要用 `requires: ["contract.loginIntent"]` 声明依赖；旧宿主不传 `options`。

## 采集结果

```js
return { status: 'success', metrics: [{ metricKey: 'STEPS', value: 5200 }] };
```

| status | 何时用 | 宿主处置 |
|---|---|---|
| `success` | 拿到数据 | 正常落库 |
| `empty` | **确定**服务端没有该日记录 | 不落库，显示原因 |
| `temporary` | 断网、超时、5xx、限流 | 退避重试，不判插件坏掉 |
| `authRequired` | 凭据失效 | 提示重新登录，不重试 |
| `failure` | 接口改了、格式不符 | 记不健康，不重试 |

三条硬规则：

1. **"不确定有没有"不能报 `empty`**——超时与解析失败一律 `temporary` 或 `failure`
2. `status` 拼错等于 `failure`，不会静默变成"今天没数据"
3. `retryAfterSeconds` 优先抄服务端的 `Retry-After`

失败分支可选带 `error` 对象（见 [05-errors](./05-errors.md)）：

```js
{ status: 'failure', message: '...', error: { code: 'UPSTREAM_CHANGED', message: '字段缺失' } }
```

不填时宿主按旧语义推断：`authRequired`→`AUTH_REQUIRED`、
`temporary`→`UPSTREAM_ERROR`、`failure`→`INTERNAL_ERROR`。

## 会话

- `sessionStart` 必须显式 `return true`：没写返回值不算成功——
  否则界面会进入一个永远收不到消息的空对话
- `sessionStop` 可能被重复调用，必须幂等
- 后台收发靠插件自己的循环 + `ctx.ws` 拉取模型，不要在一次调用里长等

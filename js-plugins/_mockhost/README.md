# 假宿主（mock host）

在 Node 里跑插件源码，不启动 QuickJS。用于开发期自测：调 `fetch` / `detailUi` /
`login` 并断言返回结构，不必每次都装进 App。

```bash
# 快速冒烟：加载 mihealth 产物，调用 detailUi / authState
cd js-plugins
node _mockhost/smoke.mjs ../js-plugins/mihealth/main.js
```

```js
import { createMockCtx, loadPlugin } from './_mockhost/index.mjs';

const mock = createMockCtx({
  pluginId: 'com.lifeboard.plugin.demo',
  http: ({ method, url }) => {
    if (url.includes('/api/steps')) return { status: 200, body: '{"steps":5200}' };
    return { status: 404, body: '{}' };
  },
});
const mod = await loadPlugin('./demo/main.js', mock);

const result = await mod.fetch('2030-01-01');
console.log(result);            // 断言结构
console.log(mock.snapshot());   // 断言写入了什么
```

## ⚠️ 它不模拟什么

**通过假宿主不等于通过真机。** 以下差异必须在真机上补验：

| 真实宿主有 | 假宿主 |
|---|---|
| QuickJS 隔离与 64 MiB 堆上限 | 普通 Node 模块，无隔离 |
| 单段 1 秒同步预算、中断、连续 3 次停用 | **完全没有**；死循环会直接把 Node 卡死 |
| 专用单线程 + 5 ms 后台泵 | 普通异步，没有泵 |
| 真实网络、TLS、自签 CA 钉扎 | 由你传入的 `http` 回调决定 |
| 载荷限额（4 MiB 响应、4 KiB 机器字段） | **不生效** |
| 卸载钩子的"仅日志"限制 | `ctx.files` 在钩子里也能用 |
| `ctx.http.session` 的真实 CookieJar | 固定返回 `'mock-session'` |
| 凭据加密（DPAPI / Android Vault） | 明文 Map |

因此它适合验证：

- 返回结构是否符合契约（`status` / `metrics` / `needInput` 等）
- 分页、重试、状态机等**纯逻辑**
- 上报调用的参数是否正确

**不适合**验证：性能、内存、并发安全、超时行为、真实协议对接。

## 新增宿主能力时

1. 先在本文件实现内存版（否则插件在 Node 里会报 `is not a function`）
2. 若能力影响限额或生命周期语义，**不要**在假宿主里伪造——
   在 README 的差异表里注明"不模拟"
3. 保持 `index.mjs` 与 `JsHostFunctions` 分组一致，缺失项应显式报错而不是静默 undefined

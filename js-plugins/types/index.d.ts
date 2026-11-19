/**
 * 类型入口：让插件源码可以直接用裸 `ctx` 和契约类型，不需要 import。
 *
 * ## 为什么 `ctx` 是全局的
 *
 * 宿主把 `ctx` 作为函数参数传给插件的 `fetch(date, ctx)` 等方法，但插件内部的
 * 子模块（如 `client.ts`）通常直接引用裸 `ctx`，不层层透传。这与宿主行为一致——
 * 桥接对象在插件加载时就注册好了，是全局可用的。
 *
 * ## 为什么用 `declare global` 而不是直接写 `declare const`
 *
 * 契约类型（`UiBlock`、`FetchResult` 等）需要能被 `import type` 引入，
 * 而一个纯脚本式的 `.d.ts`（没有顶层 import/export）里的类型无法被导出。
 * 所以文件本身是模块，靠 `declare global` 把 `ctx` 挂到全局，
 * 其余类型用普通 `export` 暴露。
 *
 * ## 引用方式
 *
 * 在插件的 `tsconfig.json` 里把 `types/` 加进 `include`，或在文件顶部写：
 *
 * ```ts
 * /// <reference path="../types/index.d.ts" />
 * ```
 */

/// <reference path="./ctx.d.ts" />
/// <reference path="./plugin-contract.d.ts" />

export type {
    ActionResult,
    AuthState,
    ButtonSpec,
    ChartPoint,
    ChartSpec,
    FetchResult,
    FetchResultObject,
    InputField,
    LoginResult,
    PluginHealth,
    PluginMetric,
    Tone,
    UiBlock,
} from './plugin-contract.js';

export type {
    CodecApi,
    CredentialsApi,
    CryptoApi,
    FilesApi,
    FormParams,
    HeaderMap,
    HttpApi,
    HttpErrorKind,
    HttpOptions,
    HttpResponse,
    LogApi,
    MetricSample,
    PluginCtx,
    RawRecord,
} from './ctx.js';

declare global {
    /**
     * 宿主注入的插件上下文。
     *
     * 只在插件方法被调用期间有效，不要在模块顶层读取后缓存整个对象。
     */
    const ctx: PluginCtx;
}

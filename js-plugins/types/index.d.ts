/**
 * 类型入口：让插件源码可以直接用裸 `ctx` 和契约类型，不需要 import。
 *
 * ## 为什么 `ctx` 是全局的
 *
 * 宿主把 `ctx` 作为函数参数传给插件的 `fetch(date, ctx)` 等方法，但插件内部的
 * 子模块（如 `client.ts`）通常直接引用裸 `ctx`，不层层透传。这与宿主行为一致——
 * 桥接对象在插件加载时就注册好了，是全局可用的。
 *
 * ## 为什么本文件必须是「全局脚本」而不是模块
 *
 * 插件源码用 `/// <reference path="../types/index.d.ts" />` 引入类型。
 * **被 reference 的文件若是模块（含顶层 `import` / `export`），它的声明不会
 * 进入引用方的全局作用域**：`ctx` 与 `UiNode` 都会变成隐式 `any`，
 * `ctx.http.postFrom(...)` 这种错字也能通过类型检查 —— 一整套类型声明形同虚设。
 *
 * 所以本文件**不能有任何顶层 import/export**。契约类型的定义放在两个
 * 模块化声明文件里（它们需要 `export {}` 才能被 import），本文件只用
 * `/// <reference>` 把它们拉进来，再以 `type` 别名把名字铺到全局。
 *
 * 这样两件事同时成立：
 * - 插件里直接写 `UiNode` / `FetchResult` / `ctx` 就能用；
 * - `probe.ts` 里仍可以 `import type { UiNode } from '../index.js'` 取到具名类型。
 *
 * ## 历史教训
 *
 * 早先本文件用 `export type { … } from './plugin-contract.js'` 做纯 re-export，
 * 且两个被 re-export 的文件都没有 `export {}` 因而不是模块，两层问题叠加后
 * 所有类型静默退化成 `any`，`npm run typecheck` 长期「通过」却没检查任何东西。
 * 现在两侧都有守卫：这两个文件尾部有 `export {}`，本文件底部有全局断言。
 */

/// <reference path="./plugin-contract.d.ts" />
/// <reference path="./ctx.d.ts" />

// ---------------------------------------------------------------------------
// 契约类型（全局可用）
// ---------------------------------------------------------------------------

type ActionResult = import('./plugin-contract.js').ActionResult;
type AuthState = import('./plugin-contract.js').AuthState;
type Bound<T> = import('./plugin-contract.js').Bound<T>;
type ChartPoint = import('./plugin-contract.js').ChartPoint;
type ChartSpec = import('./plugin-contract.js').ChartSpec;
type ChatMessage = import('./plugin-contract.js').ChatMessage;
type Emphasis = import('./plugin-contract.js').Emphasis;
type FetchResult = import('./plugin-contract.js').FetchResult;
type FetchResultObject = import('./plugin-contract.js').FetchResultObject;
type InputField = import('./plugin-contract.js').InputField;
type LoginIntent = import('./plugin-contract.js').LoginIntent;
type LoginOptions = import('./plugin-contract.js').LoginOptions;
type LoginResult = import('./plugin-contract.js').LoginResult;
type PickerCandidate = import('./plugin-contract.js').PickerCandidate;
type PickerMode = import('./plugin-contract.js').PickerMode;
type PluginErrorCode = import('./plugin-contract.js').PluginErrorCode;
type PluginErrorObject = import('./plugin-contract.js').PluginErrorObject;
type PluginHealth = import('./plugin-contract.js').PluginHealth;
type PluginMetric = import('./plugin-contract.js').PluginMetric;
type Spacing = import('./plugin-contract.js').Spacing;
type Tone = import('./plugin-contract.js').Tone;
type UiNode = import('./plugin-contract.js').UiNode;

// ---------------------------------------------------------------------------
// ctx 能力类型（全局可用）
// ---------------------------------------------------------------------------

type CodecApi = import('./ctx.js').CodecApi;
type CredentialsApi = import('./ctx.js').CredentialsApi;
type CryptoApi = import('./ctx.js').CryptoApi;
type FilesApi = import('./ctx.js').FilesApi;
type FormParams = import('./ctx.js').FormParams;
type HeaderMap = import('./ctx.js').HeaderMap;
type HttpApi = import('./ctx.js').HttpApi;
type HttpErrorKind = import('./ctx.js').HttpErrorKind;
type HttpOptions = import('./ctx.js').HttpOptions;
type HttpResponse = import('./ctx.js').HttpResponse;
type LogApi = import('./ctx.js').LogApi;
type MetricSample = import('./ctx.js').MetricSample;
type PluginCtx = import('./ctx.js').PluginCtx;
type RawRecord = import('./ctx.js').RawRecord;

/**
 * 宿主注入的插件上下文。
 *
 * 只在插件方法被调用期间有效，不要在模块顶层读取后缓存整个对象。
 */
declare const ctx: PluginCtx;

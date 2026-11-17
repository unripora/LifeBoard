package com.lifeboard.jsengine

/** JS 脚本执行期抛出的异常（语法错误、运行时错误、未捕获的 throw）。 */
class JsException(message: String) : Exception(message)

/**
 * JS 异步函数尚未落定。
 *
 * 出现时机：插件调用 `await ctx.http.get(...)` 这类需要宿主参与的异步能力时，
 * JS 侧 Promise 在等 Kotlin 回调。
 *
 * @property callKey 该次调用的调用键。调用方带着它再次 [JsRuntime.callFunction] 时，
 *   引擎会复用同一个 Promise 继续等，**不会重新执行函数体**（避免重复发请求）。
 */
class PendingResultException(
    message: String,
    val callKey: String = "",
) : Exception(message)

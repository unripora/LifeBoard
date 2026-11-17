package com.lifeboard.jsengine

/**
 * 宿主函数分发器：JS 调用 `ctx.xxx(...)` 时在 Kotlin 侧落地。
 *
 * 实现方按 [name] 路由到具体能力（HTTP、凭据、文件、日志等），
 * 参数与返回值都是 JSON 文本，避免为每个能力单独设计 native 签名。
 *
 * 返回约定（Android JNI 与桌面 JNA 两条桥接层**完全一致**）：
 * - 普通 JSON 文本：作为同步返回值交给 JS（JS 侧拿到解析后的值）
 * - [pending]：表示需要异步完成，JS 侧得到一个挂起的 Promise，
 *   宿主稍后通过 `JsRuntime.resolvePromise(token, ...)` 落地
 * - [error]：JS 侧抛异常
 */
fun interface HostFunctionDispatcher {
    fun invoke(name: String, argsJson: String): String

    companion object {
        /** 返回此标记表示异步完成中；不带 token 时 JS 侧直接报错。 */
        const val PENDING = "<pending>"

        /**
         * 声明一次异步调用：JS 侧会拿到挂起的 Promise，
         * 宿主完成操作后用同一个 token 调 `resolvePromise`。
         *
         * token 必须全局唯一，通常用 UUID 或自增序号。
         */
        fun pending(token: String): String = "<pending:$token>"

        /** 拼装错误返回值。 */
        fun error(reason: String): String = "<error:$reason>"
    }
}

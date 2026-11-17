package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.jsengine.JsRuntime

/**
 * 把多个能力桥接合并成一个 dispatcher。
 *
 * ## 为什么需要
 *
 * `JsRuntime.registerHostFunctions` 一次只能挂一个 dispatcher（后注册的替换先注册的），
 * 而插件需要同时用到 `ctx.credentials`、`ctx.files`、`ctx.http` 等多组能力。
 * 合并成一个再注册，避免「注册了 HTTP 就丢了凭据」这类问题。
 *
 * 路由按注册顺序匹配：先命中的桥接处理，没命中返回一条明确的错误，
 * 便于插件作者发现拼错了函数名。
 */
class CompositeHostDispatcher(
    private val delegates: List<Pair<List<String>, HostFunctionDispatcher>>,
) : HostFunctionDispatcher {

    /** 所有已注册的 JS 函数名，用于一次性注册。 */
    val allFunctionNames: List<String> = delegates.flatMap { it.first }

    private val route: Map<String, HostFunctionDispatcher> =
        delegates.flatMap { (names, dispatcher) -> names.map { it to dispatcher } }.toMap()

    override fun invoke(name: String, argsJson: String): String {
        val target = route[name]
            ?: return HostFunctionDispatcher.error("未注册的宿主函数：$name")
        return target.invoke(name, argsJson)
    }

    /** 一次性挂到运行时。 */
    fun install(runtime: JsRuntime) {
        runtime.registerHostFunctions(allFunctionNames, this)
    }
}

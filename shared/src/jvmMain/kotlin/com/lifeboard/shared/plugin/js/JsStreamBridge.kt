package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.shared.plugin.json.PluginJsonArray

/**
 * 把 `ctx.stream.emit(text)` 暴露给插件。
 *
 * ## 只传文本，不传「结构」
 *
 * 增量是**给人看的中间结果**，不是契约数据：最终结果仍由调用返回值给出。
 * 如果允许推 JSON 区块，插件就会开始依赖「屏幕上此刻有什么」，
 * 而宿主随时可能重绘或替换整页，两者必然对不上。
 *
 * ## 同步返回，不等待消费
 *
 * `emit` 直接返回 `null`，不阻塞插件线程。界面跟不上时由
 * [JsSourcePlugin] 的缓冲丢弃最早片段——宁可少显示几个字，
 * 也不能让一次网络请求的回调被界面渲染拖住。
 */
class JsStreamBridge(
    /** 接收增量。由加载器指向具体的 [JsSourcePlugin] 实例。 */
    private val sink: (String) -> Unit,
) : HostFunctionDispatcher {

    fun functionNames(): List<String> = JsHostFunctions.STREAM

    override fun invoke(name: String, argsJson: String): String {
        if (name != "ctx.stream.emit") {
            return HostFunctionDispatcher.error("unknown stream function: $name")
        }
        val args = runCatching { PluginJsonArray(argsJson) }
            .getOrElse { return HostFunctionDispatcher.error("参数不是数组") }
        // 只收字符串：传对象多半是想「把整条消息推过去」，静默转字符串
        // 会把 JSON 片段拼进对话正文，宁可报错让作者改成文本
        val text = args.optStringOrNull(0)
            ?: return HostFunctionDispatcher.error("ctx.stream.emit 需要字符串参数")
        sink(text)
        return "null"
    }
}
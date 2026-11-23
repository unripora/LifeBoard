package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * 把 `ctx.session.emit(json)` 暴露给插件。
 *
 * ## 与 `ctx.stream.emit` 的分工
 *
 * `stream.emit` 只传纯文本，用于「这次调用的中间结果」；会话需要区分
 * 「用户说的」「对方说的」「状态变了」，纯文本表达不了，所以这里收一个
 * **受控的 JSON 对象**，由宿主翻译成 [SessionUiEvent]。
 *
 * 仍然不允许插件塞任意 UI：类型只有 message / delta / status / clear / closed，
 * 未知类型直接报错，让作者在开发期就知道拼错了，而不是线上静默丢消息。
 *
 * @param sink 接收事件；由加载器指向具体的 [JsSourcePlugin] 实例
 */
class JsSessionBridge(
    private val sink: (SessionUiEvent) -> Unit,
) : HostFunctionDispatcher {

    fun functionNames(): List<String> = JsHostFunctions.SESSION

    override fun invoke(name: String, argsJson: String): String {
        if (name != "ctx.session.emit") {
            return HostFunctionDispatcher.error("unknown session function: $name")
        }
        val args = runCatching { PluginJsonArray(argsJson) }
            .getOrElse { return HostFunctionDispatcher.error("参数不是数组") }
        val text = args.optStringOrNull(0)
            ?: return HostFunctionDispatcher.error("ctx.session.emit 需要 JSON 字符串参数")

        val event = try {
            parse(text)
        } catch (e: IllegalArgumentException) {
            return HostFunctionDispatcher.error(e.message ?: "会话事件格式非法")
        } catch (e: Exception) {
            return HostFunctionDispatcher.error("会话事件不是合法 JSON：${e.message}")
        }
        sink(event)
        return "null"
    }

    private fun parse(text: String): SessionUiEvent {
        val o = PluginJsonObject(text)
        return when (val type = o.optString("type")) {
            "message" -> SessionUiEvent.Message(
                role = parseRole(o.optString("role")),
                text = o.optString("text"),
            )
            "delta" -> SessionUiEvent.Delta(o.optString("text"))
            "status" -> SessionUiEvent.Status(
                text = o.optString("text"),
                inputEnabled = o.optBoolean("inputEnabled", true),
            )
            "clear" -> SessionUiEvent.Clear
            "closed" -> SessionUiEvent.Closed(o.optString("reason", "会话已结束"))
            else -> throw IllegalArgumentException("未知会话事件类型：$type")
        }
    }

    /** 角色缺省按「对方」处理：插件漏写时宁可在界面上偏向 assistant，也不要冒充用户 */
    private fun parseRole(raw: String): ChatRole = when (raw.lowercase()) {
        "user" -> ChatRole.USER
        "system" -> ChatRole.SYSTEM
        else -> ChatRole.ASSISTANT
    }
}

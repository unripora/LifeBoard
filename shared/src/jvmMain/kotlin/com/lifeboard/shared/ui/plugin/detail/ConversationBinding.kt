package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话进行中状态。
 *
 * 是**机器可读**的枚举，给插件绑定 `session:<key>:turnState` 用。
 * 早先插件只能去解析 [ConversationUiState.statusText] 这个**给人看的文案**
 * （「正在回复」「已连接」）来猜状态——文案一改实现就断，而文案本来就
 * 不该成为接口。
 */
enum class ConversationTurnState {
    /** 空闲，可以输入 */
    IDLE,

    /** 已发出，正在等对方回复 */
    WAITING,

    /** 会话被关闭 */
    ENDED,

    /** 未连接 */
    DISCONNECTED,
}

/**
 * 会话区块对外暴露的状态。
 *
 * 只读快照：由会话控制器在每次消息变化时重新提供，
 * 渲染层不做任何累积，避免「界面自己记一份、控制器再记一份」双份真相。
 */
data class ConversationUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** 连接状态文案，显示在区块标题右侧 */
    val statusText: String = "会话未连接",
    /** 输入框是否可用；仅连接成功且无在途请求时为 true */
    val inputEnabled: Boolean = false,
    /** 机器可读的进行中状态；与 [statusText] 描述同一件事，但前者是给人看的 */
    val turn: ConversationTurnState = ConversationTurnState.DISCONNECTED,
) {
    /** 绑定源暴露的字符串形态，见 [SessionBindingSource.FIELD_TURN_STATE] */
    val turnState: String
        get() = when (turn) {
            ConversationTurnState.IDLE -> "idle"
            ConversationTurnState.WAITING -> "waiting"
            ConversationTurnState.ENDED -> "ended"
            ConversationTurnState.DISCONNECTED -> "disconnected"
        }
}

/**
 * 会话区块与宿主的绑定点。
 *
 * [ConversationBlock] 只声明「这里要一块会话」，真正的消息从哪来、输入送到哪，
 * 由宿主在渲染前注入实现。这样同一个区块既能接真实连接，也能在测试里接假数据。
 *
 * ## 为什么不是插件直接持有
 *
 * 会话状态必须活在页面之外（离开页面连接仍在），而渲染层是页面的一部分。
 * 因此渲染层只读快照、只转发输入，状态所有权归控制器。
 */
interface ConversationBinding {
    /**
     * 读取当前快照。
     *
     * 返回 [StateFlow] 而不是普通值：消息由插件在任意时刻推入，
     * 页面必须能被动重组，不能等用户点了才刷新。
     */
    fun state(sessionId: String): StateFlow<ConversationUiState>

    /** 提交用户输入；[ConversationUiState.inputEnabled] 为 false 时不会被调用 */
    fun submit(sessionId: String, text: String) {}

    /** 未接入连接能力时的默认实现：可读、不可发送 */
    object Unavailable : ConversationBinding {
        private val empty = MutableStateFlow(ConversationUiState())

        override fun state(sessionId: String): StateFlow<ConversationUiState> = empty
    }
}

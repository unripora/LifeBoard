package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.ui.plugin.detail.ConversationBinding
import com.lifeboard.shared.ui.plugin.detail.ConversationUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话在插件侧的窄接口。
 *
 * 控制器不直接依赖 [com.lifeboard.plugin.api.SourcePlugin]：那需要同时持有每次调用
 * 新建的 `PluginContext`，而且真实的插件接口有十几个与会话无关的方法，
 * 测试里实现一遍纯属噪音。生产侧用适配器把「插件 + 上下文」包成这个接口。
 */
interface SessionEndpoint {

    suspend fun start(config: SessionConfig): Boolean

    suspend fun event(event: SessionEvent): SessionAction

    suspend fun stop()

    /** 注册主动推送监听；`null` 解除 */
    fun setListener(listener: ((SessionUiEvent) -> Unit)?)

    /** 纯文本增量；插件不用时保持空流 */
    val deltas: Flow<String>
        get() = emptyFlow()
}

/**
 * 进程级会话控制器。
 *
 * ## 为什么不放在页面里
 *
 * 会话要跨越「进入详情页 → 切到别的页签 → 再回来」继续存在。Compose 的
 * 状态会随页面销毁，所以消息、在途标记、监听注册全部留在这里，
 * 页面只拿 [state] 快照并转发输入。
 *
 * ## 线程模型
 *
 * 插件可以在自己的线程上推事件（`ctx.session.emit` 是同步宿主函数），
 * 因此状态更新统一走 [MutableStateFlow.update]，不要求在特定线程调用。
 * 输入提交与停机在传入的 [scope] 上执行。
 *
 * ## 首期单会话
 *
 * 一个控制器只承载一条会话；`sessionId` 仅用于校验调用方拿的是不是当前会话。
 */
class PluginSessionController(
    private val endpointProvider: () -> SessionEndpoint?,
    private val scope: CoroutineScope,
) : ConversationBinding {

    private val _state = MutableStateFlow(ConversationUiState())
    private val stateFlow: StateFlow<ConversationUiState> = _state.asStateFlow()

    /** 当前会话 id；空串表示没有活跃会话 */
    @Volatile
    private var activeSessionId: String = ""

    @Volatile
    private var active: Boolean = false

    private var deltaJob: Job? = null

    val isActive: Boolean get() = active

    /** 是否已经有一条等待补全的 assistant 消息（流式增量接在它后面） */
    private var pendingAssistant = false

    /**
     * 启动会话。
     *
     * @return false 表示插件不支持、未加载或启动失败；此时界面保持「未连接」
     */
    suspend fun start(sessionId: String, config: SessionConfig = SessionConfig()): Boolean {
        if (active) return true
        val endpoint = endpointProvider() ?: return false

        // 先把 active 置位再调插件：插件常在 sessionStart 里同步推送
        // 「连接中」「补一段历史消息」，此时若还没进入活跃态，增量会被丢掉
        activeSessionId = sessionId
        active = true
        pendingAssistant = false
        _state.value = ConversationUiState(statusText = "正在连接", inputEnabled = false)

        // 插件在启动阶段自己报过状态，就以它为准；没报过才用宿主的默认文案。
        // 用原子量：插件可能在它自己的线程上回调监听
        val statusReported = java.util.concurrent.atomic.AtomicBoolean(false)
        endpoint.setListener { event ->
            if (event is SessionUiEvent.Status) statusReported.set(true)
            apply(event)
        }
        val started = runCatching { endpoint.start(config) }.getOrDefault(false)
        if (!started) {
            active = false
            activeSessionId = ""
            endpoint.setListener(null)
            _state.value = ConversationUiState()
            return false
        }
        // 插件在启动过程中就把会话关了：保留它给的「已结束」状态，不要覆盖成已连接
        if (!active) return false

        if (!statusReported.get()) {
            _state.update { it.copy(statusText = "已连接", inputEnabled = true) }
        }
        deltaJob = scope.launch {
            endpoint.deltas.collect { text -> applyDelta(text) }
        }
        return true
    }

    /**
     * 在控制器自己的作用域里启动会话。
     *
     * 详情页的 scope 会随页面销毁而取消，而启动过程要发网络请求；
     * 挂到页面上会让「进入详情页后立刻返回」把启动一起取消。
     */
    fun startAsync(sessionId: String, config: SessionConfig = SessionConfig()) {
        scope.launch { start(sessionId, config) }
    }

    /** 后台结束会话，用于撤销信任/卸载这类「页面马上就关」的路径 */
    fun stopAsync() {
        scope.launch { stop() }
    }

    /** 结束会话；重复调用安全 */
    suspend fun stop() {
        if (!active) {
            endpointProvider()?.setListener(null)
            return
        }
        active = false
        activeSessionId = ""
        pendingAssistant = false
        deltaJob?.cancel()
        deltaJob = null
        val endpoint = endpointProvider()
        endpoint?.setListener(null)
        runCatching { endpoint?.stop() }
        _state.value = _state.value.copy(statusText = "会话未连接", inputEnabled = false)
    }

    /** 处理插件主动推送的事件；可在任意线程调用 */
    fun apply(event: SessionUiEvent) {
        when (event) {
            is SessionUiEvent.Message -> appendMessage(event.role, event.text)
            is SessionUiEvent.Delta -> applyDelta(event.text)
            is SessionUiEvent.Status -> _state.update {
                it.copy(statusText = event.text, inputEnabled = event.inputEnabled && active)
            }
            SessionUiEvent.Clear -> _state.update { it.copy(messages = emptyList()) }
            is SessionUiEvent.Closed -> {
                appendMessage(ChatRole.SYSTEM, event.reason)
                active = false
                activeSessionId = ""
                deltaJob?.cancel()
                deltaJob = null
                endpointProvider()?.setListener(null)
                _state.update { it.copy(statusText = "会话已结束", inputEnabled = false) }
            }
        }
    }

    /** 提交用户输入。输入不可用或会话不在时静默忽略，避免连点产生重复消息 */
    private fun submitInternal(text: String) {
        val content = text.trim()
        if (content.isEmpty() || !active || !_state.value.inputEnabled) return
        appendMessage(ChatRole.USER, content)
        // 发出后先禁用输入：服务端回复前允许继续输入会让「谁在等谁」变得模糊
        _state.update { it.copy(inputEnabled = false, statusText = "正在回复") }
        scope.launch {
            val endpoint = endpointProvider()
            val action = runCatching {
                endpoint?.event(SessionEvent(type = "input", payload = content))
            }.getOrElse { error ->
                appendMessage(ChatRole.SYSTEM, "发送失败：${error.message ?: "未知错误"}")
                SessionAction.Stop("发送失败")
            }
            when (action) {
                is SessionAction.Stop -> {
                    appendMessage(ChatRole.SYSTEM, action.reason)
                    active = false
                    activeSessionId = ""
                    endpoint?.setListener(null)
                    _state.update { it.copy(statusText = "会话已结束", inputEnabled = false) }
                }
                else -> _state.update {
                    it.copy(statusText = "已连接", inputEnabled = active)
                }
            }
        }
    }

    private fun appendMessage(role: ChatRole, text: String) {
        if (text.isEmpty()) return
        pendingAssistant = false
        _state.update { current ->
            val messages = current.messages + ChatMessage(role, text)
            current.copy(messages = messages.takeLast(MAX_MESSAGES))
        }
    }

    private fun applyDelta(text: String) {
        if (text.isEmpty() || !active) return
        _state.update { current ->
            val messages = current.messages.toMutableList()
            val last = messages.lastOrNull()
            if (last != null && last.role == ChatRole.ASSISTANT && pendingAssistant) {
                messages[messages.lastIndex] = last.copy(text = last.text + text)
            } else {
                messages += ChatMessage(ChatRole.ASSISTANT, text)
                pendingAssistant = true
            }
            current.copy(messages = messages.takeLast(MAX_MESSAGES))
        }
    }

    override fun state(sessionId: String): StateFlow<ConversationUiState> {
        // 首期单会话：即使调用方传了别的 id，也返回唯一的那条，
        // 避免区块刚创建、id 还没对齐时先闪一下空态
        return stateFlow
    }

    override fun submit(sessionId: String, text: String) {
        submitInternal(text)
    }

    private companion object {
        /** 只保留最近若干条，避免长时间挂机把内存吃满 */
        const val MAX_MESSAGES = 500
    }
}

/**
 * 进程级控制器注册表。
 *
 * 按插件区分实例：同一插件在任意页面拿到的都是同一条会话。
 * 进程退出即清空——按既定结论，消息不落库。
 */
object PluginSessionRegistry {

    /**
     * 进程级作用域。
     *
     * 会话必须活过详情页：用户切到别的页签再回来，消息与在途请求都还在。
     * 挂到页面 scope 上的话，返回键一按就全没了。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val controllers = mutableMapOf<String, PluginSessionController>()

    fun controllerFor(
        pluginId: String,
        endpointProvider: () -> SessionEndpoint?,
    ): PluginSessionController = synchronized(controllers) {
        controllers.getOrPut(pluginId) {
            PluginSessionController(endpointProvider = endpointProvider, scope = scope)
        }
    }

    fun remove(pluginId: String) {
        synchronized(controllers) { controllers.remove(pluginId) }
    }
}

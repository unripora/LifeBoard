package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.platform.SessionKeepAlive
import com.lifeboard.shared.ui.plugin.detail.ConversationBinding
import com.lifeboard.shared.ui.plugin.detail.ConversationTurnState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * 会话期间的保活租约。
     *
     * 没有它，会话只靠「每次调用现申请一次租约」活着，两次输入之间实例是空闲的，
     * 一旦超过阈值就被回收，监听注册与订阅一起丢。默认返回空表示不支持，
     * 此时行为退回改造前（每次调用独立申请）。
     */
    suspend fun acquireLease(): SessionKeepAlive? = null
}

/**
 * 进程级会话控制器，**一只控制器承载多条会话**（一个会话 = 一个 key）。
 *
 * ## 为什么不放在页面里
 *
 * 会话要跨越「进入详情页 → 切到别的页签 → 再回来」继续存在。Compose 的
 * 状态会随页面销毁，所以消息、在途标记、监听注册全部留在这里，
 * 页面只拿 [state] 快照并转发输入。
 *
 * ## 为什么是「单控制器 + 多 key」而不是「每 key 一个控制器」
 *
 * 真实的会话型协议（如 DSH）是「**认证一次、切到不同历史对话**」：
 * 握手、Cookie、证书都是会话级共享的。每 key 一个控制器会让 N 条会话
 * 各自握手一次、各开一条长连接，且 [stop] 的语义变成「停哪一个」。
 * 所以这里保持**一条连接**，只在控制器内部按 key 隔离消息表与状态，
 * 切 key 时通过 `SessionConfig.options["sessionKey"]` 通知插件「现在看的是哪一条」，
 * 由插件自行决定换订阅还是复用。生命周期与旧版完全一致。
 *
 * ## 线程模型
 *
 * 插件可以在自己的线程上推事件（`ctx.session.emit` 是同步宿主函数），
 * 因此状态更新统一走 [MutableStateFlow.update]，不要求在特定线程调用。
 * 输入提交与停机在传入的 [scope] 上执行。
 */
class PluginSessionController(
    private val endpointProvider: () -> SessionEndpoint?,
    private val scope: CoroutineScope,
) : ConversationBinding {

    /**
     * 每个 key 一份状态。
     *
     * 用 map 而不是单个 state：`state(key)` 必须返回**该 key 自己的**消息，
     * 否则用户在「会话 A」里说话会出现在「会话 B」的界面上——这正是本轮
     * 要修的第一个缺陷（签名接受 sessionId 却忽略它）。
     */
    private val states = mutableMapOf<String, MutableStateFlow<ConversationUiState>>()

    /** 每个 key 是否「已经有一条等待补全的 assistant 消息」 */
    private val pendingAssistant = mutableMapOf<String, Boolean>()

    /** 当前正在收发的 key；空串表示没有活跃会话 */
    @Volatile
    private var activeKey: String = ""

    @Volatile
    private var active: Boolean = false

    private var deltaJob: Job? = null

    /**
     * 会话期间的保活句柄；端点不支持或 eager 模式下为空。
     *
     * 由 [doStart] 申请、[stop]/[clearAll] 与关闭事件释放，必须与 `active`
     * 同生命周期——早释放会让会话在空闲时被回收，晚释放会把实例永久钉住。
     */
    private var sessionKeepAlive: SessionKeepAlive? = null

    private fun releaseKeepAlive() {
        val keepAlive = synchronized(states) {
            sessionKeepAlive.also { sessionKeepAlive = null }
        }
        runCatching { keepAlive?.release() }
    }

    /**
     * 串行化启动与停机。
     *
     * 详情页的 `load()` 和动作后的 `startConversationIfNeeded` 都会调
     * [startAsync]，两者可能交错：`active` 的检查与置位若不是原子的，
     * 就会出现两次 `setListener` 与两条 `deltaJob`，消息被重复追加。
     */
    private val lifecycle = Mutex()

    /**
     * 标记「启动流程已经发起」，避免重复启动。
     *
     * 不能用 [active] 担当这个角色：`start` 是挂起函数，中间会让出，
     * 期间 `active` 已经为 true，但握手还没完成。
     */
    @Volatile
    private var starting: Boolean = false

    /** 是否有一条正在建立或已建立的会话 */
    val isActive: Boolean get() = active || starting

    /** 当前活跃的 key；没有活跃会话时为空串 */
    val activeSessionKey: String get() = activeKey

    /**
     * 启动会话。
     *
     * @param sessionId 要接通（或切到）的会话 key
     * @return false 表示插件不支持、未加载或启动失败；此时界面保持「未连接」
     */
    suspend fun start(sessionId: String, config: SessionConfig = SessionConfig()): Boolean =
        lifecycle.withLock {
            // 已经连在同一条会话上：什么都不用做
            if (active && activeKey == sessionId) return@withLock true
            // 已连在别的会话上：这是「切换」，不是「再连一条」。
            // 连接与监听都保留，只换 key 并把新 key 告诉插件
            if (active) return@withLock switchTo(sessionId, config)
            if (starting) return@withLock false
            starting = true
            try {
                doStart(sessionId, config)
            } finally {
                starting = false
            }
        }

    private suspend fun doStart(sessionId: String, config: SessionConfig): Boolean {
        val endpoint = endpointProvider() ?: return false

        // 先把 active 置位再调插件：插件常在 sessionStart 里同步推送
        // 「连接中」「补一段历史消息」，此时若还没进入活跃态，增量会被丢掉
        activeKey = sessionId
        active = true
        pendingAssistant[sessionId] = false
        stateOf(sessionId).value = ConversationUiState(
            statusText = "正在连接",
            inputEnabled = false,
            turn = ConversationTurnState.DISCONNECTED,
        )

        // 插件在启动阶段自己报过状态，就以它为准；没报过才用宿主的默认文案。
        // 用原子量：插件可能在它自己的线程上回调监听
        val statusReported = java.util.concurrent.atomic.AtomicBoolean(false)
        endpoint.setListener { event ->
            if (event is SessionUiEvent.Status) statusReported.set(true)
            apply(event)
        }
        // 会话存续期间保活：只靠「每次调用现申请」的租约保护不了两次输入之间的空闲。
        // 拿不到（eager 模式或插件受限）时退回原行为，不影响功能。
        val keepAlive = runCatching { endpoint.acquireLease() }.getOrNull()
        synchronized(states) { sessionKeepAlive = keepAlive }
        val started = runCatching { endpoint.start(withSessionKey(config, sessionId)) }
            .getOrDefault(false)
        if (!started) {
            active = false
            activeKey = ""
            endpoint.setListener(null)
            releaseKeepAlive()
            stateOf(sessionId).value = ConversationUiState()
            return false
        }
        // 插件在启动过程中就把会话关了：保留它给的「已结束」状态，不要覆盖成已连接
        if (!active) return false

        if (!statusReported.get()) {
            stateOf(sessionId).update {
                it.copy(
                    statusText = "已连接",
                    inputEnabled = true,
                    turn = ConversationTurnState.IDLE,
                )
            }
        }
        deltaJob = scope.launch {
            endpoint.deltas.collect { text -> applyDelta(text) }
        }
        return true
    }

    /**
     * 切到另一条会话。
     *
     * 不重新握手、不重开连接：只是把「当前看哪一条」告诉插件。插件若实现了
     * `options["sessionKey"]`，会自己断开旧订阅、建立新订阅（认证状态复用）。
     * 未实现该约定的插件会忽略这个字段，行为与升级前一致。
     */
    private suspend fun switchTo(sessionId: String, config: SessionConfig): Boolean {
        val endpoint = endpointProvider() ?: return false
        val previous = activeKey
        activeKey = sessionId
        pendingAssistant[sessionId] = false

        // 新 key 还没有任何消息：先给一个「正在切换」，避免用户看到空屏以为坏了
        val current = stateOf(sessionId).value
        if (current.messages.isEmpty()) {
            stateOf(sessionId).value = ConversationUiState(
                statusText = "正在切换会话",
                inputEnabled = false,
                turn = ConversationTurnState.DISCONNECTED,
            )
        }

        val started = runCatching { endpoint.start(withSessionKey(config, sessionId)) }
            .getOrDefault(false)
        if (!started) {
            // 切换失败：退回到原来的 key，别让用户停在一个接不通的会话上
            activeKey = previous
            stateOf(sessionId).value = ConversationUiState(
                statusText = "切换失败",
                inputEnabled = false,
                turn = ConversationTurnState.DISCONNECTED,
            )
            return false
        }
        stateOf(sessionId).update {
            it.copy(statusText = "已连接", inputEnabled = active, turn = ConversationTurnState.IDLE)
        }
        return true
    }

    /** 把会话 key 写进配置，作为与插件之间的约定（`sessionConfig.options.sessionKey`） */
    private fun withSessionKey(config: SessionConfig, key: String): SessionConfig =
        config.copy(options = config.options + (SESSION_KEY_OPTION to key))

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

    /**
     * 结束会话；重复调用安全。
     *
     * 与 [start] 共用同一把锁：并发的「启动中 → 立刻停止」（用户点进详情页
     * 又马上撤销信任）若不加锁，停机会在握手完成前跑完，之后握手成功又把
     * `active` 置回 true，留下一个谁也停不掉的会话。
     *
     * 停止后各 key 的消息表**保留**：用户可能只是暂时离开，再进来要能看到
     * 之前的对话。真正清空由 [clearAll] 负责（撤销信任/卸载时调用）。
     */
    suspend fun stop() = lifecycle.withLock {
        if (!active) {
            endpointProvider()?.setListener(null)
            return@withLock
        }
        val key = activeKey
        active = false
        activeKey = ""
        pendingAssistant[key] = false
        deltaJob?.cancel()
        deltaJob = null
        val endpoint = endpointProvider()
        endpoint?.setListener(null)
        runCatching { endpoint?.stop() }
        releaseKeepAlive()
        stateOf(key).update {
            it.copy(
                statusText = "会话未连接",
                inputEnabled = false,
                turn = ConversationTurnState.DISCONNECTED,
            )
        }
    }

    /** 清掉所有 key 的消息与状态；注册表被移除时调用 */
    fun clearAll() {
        synchronized(states) {
            states.clear()
            pendingAssistant.clear()
        }
        active = false
        activeKey = ""
        releaseKeepAlive()
    }

    /** 处理插件主动推送的事件；可在任意线程调用。事件只影响当前活跃 key */
    fun apply(event: SessionUiEvent) {
        val key = activeKey
        when (event) {
            is SessionUiEvent.Message -> appendMessage(key, event.role, event.text)
            is SessionUiEvent.Delta -> applyDelta(key, event.text)
            is SessionUiEvent.Status -> stateOf(key).update {
                it.copy(
                    statusText = event.text,
                    inputEnabled = event.inputEnabled && active,
                    turn = if (event.inputEnabled && active) {
                        ConversationTurnState.IDLE
                    } else {
                        ConversationTurnState.WAITING
                    },
                )
            }
            SessionUiEvent.Clear -> stateOf(key).update { it.copy(messages = emptyList()) }
            is SessionUiEvent.Closed -> {
                appendMessage(key, ChatRole.SYSTEM, event.reason)
                active = false
                activeKey = ""
                deltaJob?.cancel()
                deltaJob = null
                endpointProvider()?.setListener(null)
                releaseKeepAlive()
                stateOf(key).update {
                    it.copy(
                        statusText = "会话已结束",
                        inputEnabled = false,
                        turn = ConversationTurnState.ENDED,
                    )
                }
            }
        }
    }

    /**
     * 提交用户输入。
     *
     * [sessionId] 只有与当前活跃 key 一致时才真正发出：界面可能同时显示
     * 两条对话（例如「当前会话」和「会话清单摘要」），在非活跃的那条里
     * 打字没有意义，静默忽略比发到别人那儿安全。
     */
    override fun submit(sessionId: String, text: String) {
        val key = sessionId.ifBlank { activeKey }
        if (key.isNotEmpty() && key != activeKey) return
        submitInternal(key, text)
    }

    /** 提交用户输入。输入不可用或会话不在时静默忽略，避免连点产生重复消息 */
    private fun submitInternal(key: String, text: String) {
        val content = text.trim()
        if (content.isEmpty() || !active || !stateOf(key).value.inputEnabled) return
        appendMessage(key, ChatRole.USER, content)
        // 发出后先禁用输入：服务端回复前允许继续输入会让「谁在等谁」变得模糊
        stateOf(key).update {
            it.copy(
                inputEnabled = false,
                statusText = "正在回复",
                turn = ConversationTurnState.WAITING,
            )
        }
        scope.launch {
            val endpoint = endpointProvider()
            // 有保活句柄时就在它保护下投递：中途被撤权/更新会得到 null，
            // 按「会话已断开」处理，而不是把一个已关闭的运行时的空结果当成「继续」。
            val keepAlive = synchronized(states) { sessionKeepAlive }
            val action = runCatching {
                if (keepAlive != null) {
                    val delivered = keepAlive.withPlugin {
                        endpoint?.event(SessionEvent(type = "input", payload = content))
                    }
                    if (delivered == null) {
                        markSessionEnded(key, "会话已断开，请重新连接")
                        return@launch
                    }
                    delivered
                } else {
                    endpoint?.event(SessionEvent(type = "input", payload = content))
                }
            }.getOrElse { error ->
                // 发送失败不等于会话结束：插件抛一次异常就断掉整条会话，
                // 用户会以为对方主动挂了。追加一条错误并恢复输入，让他能重试。
                appendMessage(key, ChatRole.SYSTEM, "发送失败：${error.message ?: "未知错误"}")
                stateOf(key).update {
                    it.copy(
                        statusText = "已连接",
                        inputEnabled = active,
                        turn = ConversationTurnState.IDLE,
                    )
                }
                return@launch
            }
            when (action) {
                is SessionAction.Stop -> {
                    appendMessage(key, ChatRole.SYSTEM, action.reason)
                    active = false
                    activeKey = ""
                    endpoint?.setListener(null)
                    releaseKeepAlive()
                    stateOf(key).update {
                        it.copy(
                            statusText = "会话已结束",
                            inputEnabled = false,
                            turn = ConversationTurnState.ENDED,
                        )
                    }
                }
                else -> stateOf(key).update {
                    it.copy(
                        statusText = "已连接",
                        inputEnabled = active,
                        turn = ConversationTurnState.IDLE,
                    )
                }
            }
        }
    }

    /** 会话因外部原因（撤权/更新/故障）被强制断开：统一收尾并给出原因 */
    private fun markSessionEnded(key: String, reason: String) {
        appendMessage(key, ChatRole.SYSTEM, reason)
        active = false
        activeKey = ""
        deltaJob?.cancel()
        deltaJob = null
        endpointProvider()?.setListener(null)
        releaseKeepAlive()
        stateOf(key).update {
            it.copy(
                statusText = reason,
                inputEnabled = false,
                turn = ConversationTurnState.ENDED,
            )
        }
    }

    private fun appendMessage(key: String, role: ChatRole, text: String) {        if (text.isEmpty()) return
        pendingAssistant[key] = false
        stateOf(key).update { current ->
            val messages = current.messages + ChatMessage(role, text)
            current.copy(messages = messages.takeLast(MAX_MESSAGES))
        }
    }

    private fun applyDelta(text: String) = applyDelta(activeKey, text)

    private fun applyDelta(key: String, text: String) {
        if (text.isEmpty() || !active) return
        stateOf(key).update { current ->
            val messages = current.messages.toMutableList()
            val last = messages.lastOrNull()
            val pending = pendingAssistant[key] == true
            if (last != null && last.role == ChatRole.ASSISTANT && pending) {
                // 增量接在同一条回复后面，并把「有人在流式输出」传达给渲染层
                messages[messages.lastIndex] = last.copy(text = last.text + text, pending = true)
            } else {
                messages += ChatMessage(ChatRole.ASSISTANT, text, pending = true)
                pendingAssistant[key] = true
            }
            current.copy(messages = messages.takeLast(MAX_MESSAGES))
        }
    }

    /**
     * 取某个 key 的状态。
     *
     * key 不存在时**返回该 key 自己的空态**，而不是「别的会话的状态」：
     * 早先的实现永远返回同一条，插件写了两个 key 也看不出区别。
     */
    override fun state(sessionId: String): StateFlow<ConversationUiState> = stateOf(sessionId)

    private fun stateOf(key: String): MutableStateFlow<ConversationUiState> =
        synchronized(states) {
            states.getOrPut(key) { MutableStateFlow(ConversationUiState()) }
        }

    private companion object {
        /** 只保留最近若干条，避免长时间挂机把内存吃满 */
        const val MAX_MESSAGES = 500

        /**
         * 与插件约定的配置项名：宿主把「现在看的是哪一条会话」告诉插件。
         *
         * 取 `sessionKey` 而不是 `key`：`options` 是字符串字典，一个通用词
         * 容易和别的约定撞名。
         */
        const val SESSION_KEY_OPTION = "sessionKey"
    }
}

/**
 * 进程级控制器注册表。
 *
 * 按插件区分实例：同一插件在任意页面拿到的都是同一条会话（一只控制器，
 * 内部按 key 分多条会话）。进程退出即清空——按既定结论，消息不落库。
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
        synchronized(controllers) {
            controllers.remove(pluginId)?.clearAll()
        }
    }
}

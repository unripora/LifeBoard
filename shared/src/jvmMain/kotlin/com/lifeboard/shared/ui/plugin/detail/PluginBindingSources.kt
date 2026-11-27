package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.PickerCandidate
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个绑定数据源：把宿主侧的活数据按「源名 + key + 字段」暴露给节点树。
 *
 * ## 为什么要一层接口
 *
 * 插件在节点树里写 `{binding:'session:main:messages'}`，宿主要能在渲染前把它
 * 换成真实消息。换的方式不能硬编码在渲染层——将来出现第二种活数据
 * （实时日志流、任务进度）时，只要再注册一个 [PluginBindingSource]，
 * 节点类型与渲染代码都不用动。
 *
 * ## 为什么返回值是 [BoundValue] 而不是泛型
 *
 * 不同源暴露的字段类型不同（消息列表 / 字符串 / 布尔），泛型只能在每个
 * 绑定点上强转，反而更不安全。类型校验放在求值后：类型不符就退回默认值，
 * 绝不强转。
 */
interface PluginBindingSource {

    /** 源名，如 `session`。插件侧写错就查不到，此时降级为不可用 */
    val source: String

    /** 该源支持的全部字段名。解析期用它校验，避免拼错字段名静默失效 */
    val fields: Set<String>

    /** 订阅某个 key 的某个字段 */
    fun observe(key: String, field: String): StateFlow<BoundValue>

    /**
     * 向数据源提交一次输入。
     *
     * @return true 表示已接收；false 表示该源不支持写入，调用方应退回 `onAction`
     */
    fun submit(key: String, text: String): Boolean = false
}

/**
 * 绑定求值结果。
 *
 * [Unavailable] 必须能与「真的空列表」区分开：前者意味着这个绑定点根本没接上，
 * 渲染层应当整块不画；后者是一个合法状态（会话列表为空）。
 */
sealed interface BoundValue {

    data class Messages(val value: List<ChatMessage>) : BoundValue

    /**
     * 一份可选项清单（给 `picker` 的 `candidates` 用）。
     *
     * 不复用 [Messages]：两者在**视觉上**是两种东西（气泡 vs 可点选的行），
     * 复用会让「把会话消息绑到候选清单上」这类误写静默生效。
     */
    data class Candidates(val value: List<PickerCandidate>) : BoundValue

    data class Text(val value: String) : BoundValue

    data class Flag(val value: Boolean) : BoundValue

    /** 源未知、字段未知、key 不存在——一律不渲染 */
    data object Unavailable : BoundValue
}

/**
 * 进程级绑定源注册表，按 pluginId 分域。
 *
 * 与 `PluginSessionRegistry` 同级：会话控制器是「谁在收发消息」，
 * 这里是「界面能从哪儿读到消息」。分开是因为前者是业务生命周期，
 * 后者只是渲染期的一次查表。
 *
 * ## 为什么必须有 detach
 *
 * 撤销信任与卸载后若不清理，绑定会继续指向一个已停止的控制器，
 * 列表会一直显示旧消息（而且不报错）。测试之间也会互相污染。
 */
object PluginBindingRegistry {

    private val sources = mutableMapOf<String, MutableMap<String, PluginBindingSource>>()

    fun attach(pluginId: String, source: PluginBindingSource) {
        sources.getOrPut(pluginId) { mutableMapOf() }[source.source] = source
    }

    fun sourceOf(pluginId: String, name: String): PluginBindingSource? =
        sources[pluginId]?.get(name)

    fun detach(pluginId: String) {
        sources.remove(pluginId)
    }
}

/**
 * 会话绑定源：把 [ConversationBinding] 适配成通用的 [PluginBindingSource]。
 *
 * 字段：
 * - `messages`：消息列表（[ChatNode.messages] 或 [ListNode.items][com.lifeboard.plugin.api.ListNode.items] 的绑定目标）
 * - `status`：连接状态文案（标题旁边的说明）
 * - `inputEnabled`：输入框是否可用（连上且无在途请求时才可发）
 * - `turnState`：进行中状态的机器可读值（`idle` / `waiting` / `ended`），
 *   用于替换「插件从 status 文案里猜状态」这种脆弱做法
 *
 * key 就是**会话标识**：`session:<key>:messages` 里不同的 key 读到的是不同的
 * 消息表，切会话 = 换 key。
 */
class SessionBindingSource(
    private val binding: ConversationBinding,
) : PluginBindingSource {

    override val source: String = SOURCE_NAME

    override val fields: Set<String> =
        setOf(FIELD_MESSAGES, FIELD_STATUS, FIELD_INPUT_ENABLED, FIELD_TURN_STATE)

    /**
     * 字段在这里分流，而不是把整个会话状态丢给渲染层自己拆：
     * 渲染层只关心「我要的那个值变了没有」，多字段会带来不必要的重组。
     *
     * ## 为什么不用 `map { }.stateIn(...)`
     *
     * `stateIn` 的**第一帧永远是 `initialValue`**：它是异步启动的，即使源是
     * 一个已经有值的 `StateFlow`，也要等下游协程被调度到才有真值。表现是
     * 详情页刚打开时闪一下「空对话 / 会话未连接」，日志、测试里读到的是
     * `Unavailable`。
     *
     * 会话状态本身就是一个 `StateFlow`，映射是**纯函数**，没必要经过
     * 协程。这里用 [mappedStateFlow] 做同步映射：订阅时立刻给出当前值，
     * 之后照常推送变化。既没有额外调度，也不依赖任何 Dispatcher。
     */
    override fun observe(key: String, field: String): StateFlow<BoundValue> {
        val state = binding.state(key)
        return when (field) {
            FIELD_MESSAGES -> state.mappedStateFlow { BoundValue.Messages(it.messages) }
            FIELD_STATUS -> state.mappedStateFlow { BoundValue.Text(it.statusText) }
            FIELD_INPUT_ENABLED -> state.mappedStateFlow { BoundValue.Flag(it.inputEnabled) }
            // 「能不能打字」和「对方是不是在处理」是两件事：前者管输入框，
            // 后者管「正在生成」的提示。用机器可读值而不是让插件解析文案
            FIELD_TURN_STATE -> state.mappedStateFlow { BoundValue.Text(it.turnState) }
            else -> MutableStateFlow(BoundValue.Unavailable).asStateFlow()
        }
    }

    override fun submit(key: String, text: String): Boolean {
        binding.submit(key, text)
        return true
    }

    companion object {
        const val SOURCE_NAME = "session"
        const val FIELD_MESSAGES = "messages"
        const val FIELD_STATUS = "status"
        const val FIELD_INPUT_ENABLED = "inputEnabled"
        const val FIELD_TURN_STATE = "turnState"
    }
}

/**
 * 会话清单绑定源：把「有哪些会话」交给节点树。
 *
 * ## 为什么单独一个源，不塞进 `session`
 *
 * `session:<key>:<field>` 的 key 是「哪一条会话」（有实例语义）；清单回答的是
 * 「有哪些会话」，没有 key。硬塞进去会出现 `session:*:items` 这种别扭写法，
 * 且「某个 key 不存在」与「清单整个没拿到」两种失败无法区分。
 *
 * ## 典型用法
 *
 * ```json
 * {"type":"picker","actionId":"switchSession",
 *  "candidates":{"binding":"sessionList:items"}}
 * ```
 *
 * 清单来自插件的推送（[SessionListStore]），宿主不猜任何协议：DSH 的
 * `/sessions`、别家的 `/threads` 都由各自插件翻译成
 * [PickerCandidate] 列表后推上来。
 */
class SessionListBindingSource(
    private val store: SessionListStore,
) : PluginBindingSource {

    override val source: String = SOURCE_NAME

    override val fields: Set<String> = setOf(FIELD_ITEMS)

    override fun observe(key: String, field: String): StateFlow<BoundValue> =
        when (field) {
            // 同步映射，理由见 SessionBindingSource.observe 的说明
            FIELD_ITEMS -> store.items.mappedStateFlow { BoundValue.Candidates(it) }
            else -> MutableStateFlow(BoundValue.Unavailable).asStateFlow()
        }

    companion object {
        const val SOURCE_NAME = "sessionList"
        const val FIELD_ITEMS = "items"
    }
}

/**
 * 把一个 `StateFlow` 用纯函数映射成另一个 `StateFlow`，**订阅时同步就有值**。
 *
 * ## 为什么不用 `map().stateIn()`
 *
 * `stateIn` 的初值是构造时给的 `initialValue`，真值要等下游协程被调度到才到。
 * 对绑定源来说这意味着「详情页第一帧永远读到 `Unavailable`」，于是出现
 * 一帧空白/空态；在没有 Main 调度器的环境（桌面测试）里更是直接抛异常。
 *
 * 而这里的映射是纯函数（快照 → 快照），根本不需要协程：订阅时先读 [this].value
 * 给出当前值，之后跟随源的变更推送。既没有额外调度，也没有一帧空窗。
 */
private fun <T, R> StateFlow<T>.mappedStateFlow(transform: (T) -> R): StateFlow<R> =
    object : StateFlow<R> {
        override val value: R get() = transform(this@mappedStateFlow.value)

        override val replayCache: List<R> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            this@mappedStateFlow.collect { collector.emit(transform(it)) }
            error("StateFlow.collect 不会正常返回")
        }
    }

/**
 * 会话清单的持有者。
 *
 * 插件在会话事件（或 `detailUi` 返回之后的一次 `onAction`）里把清单推上来，
 * 宿主只做一次转换与广播。放在进程级，与控制器同级：清单属于「这个插件
 * 连上了哪些会话」，不属于任何一个页面。
 */
class SessionListStore {

    private val _items = MutableStateFlow<List<PickerCandidate>>(emptyList())

    val items: StateFlow<List<PickerCandidate>> = _items.asStateFlow()

    fun publish(candidates: List<PickerCandidate>) {
        _items.value = candidates
    }

    fun clear() {
        _items.value = emptyList()
    }
}

/**
 * 进程级的会话清单表，按 pluginId 分域。
 *
 * 与 [PluginBindingRegistry] 分开：清单是**数据**，绑定源是**读取通道**。
 * 拆开后，`SessionListBindingSource` 是纯粹的适配器，测试里可以只测
 * 「推上去的清单能否被读出来」，不必拉起整个节点树。
 */
object SessionListRegistry {

    private val stores = mutableMapOf<String, SessionListStore>()

    fun storeFor(pluginId: String): SessionListStore = synchronized(stores) {
        stores.getOrPut(pluginId) { SessionListStore() }
    }

    fun remove(pluginId: String) {
        synchronized(stores) { stores.remove(pluginId) }
    }
}

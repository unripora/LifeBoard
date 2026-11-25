package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
 * 字段只有三个，正好覆盖「对话界面需要什么」：
 * - `messages`：消息列表（[com.lifeboard.plugin.api.ListNode.items] 的绑定目标）
 * - `status`：连接状态文案（标题旁边的说明）
 * - `inputEnabled`：输入框是否可用（连上且无在途请求时才可发）
 */
class SessionBindingSource(
    private val binding: ConversationBinding,
) : PluginBindingSource {

    override val source: String = SOURCE_NAME

    override val fields: Set<String> = setOf(FIELD_MESSAGES, FIELD_STATUS, FIELD_INPUT_ENABLED)

    /**
     * 字段在这里分流，而不是把整个会话状态丢给渲染层自己拆：
     * 渲染层只关心「我要的那个值变了没有」，多字段会带来不必要的重组。
     *
     * 用 `map` + `stateIn` 而不是自定义 StateFlow 子类：前者是标准
     * 操作符，语义明确；后者要自己处理订阅计数与重放，容易出错。
     */
    override fun observe(key: String, field: String): StateFlow<BoundValue> {
        val state = binding.state(key)
        val mapped = when (field) {
            FIELD_MESSAGES -> state.map { BoundValue.Messages(it.messages) }
            FIELD_STATUS -> state.map { BoundValue.Text(it.statusText) }
            FIELD_INPUT_ENABLED -> state.map { BoundValue.Flag(it.inputEnabled) }
            else -> flowOf(BoundValue.Unavailable)
        }
        return mapped.stateIn(
            scope = registryScope,
            // 首帧就有值：会话状态本身是 StateFlow，初值即当前值
            started = SharingStarted.Eagerly,
            initialValue = BoundValue.Unavailable,
        )
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

        /**
         * 绑定流的共享作用域。
         *
         * 绑定是「进程活多久它就该活多久」的：会话消息由插件在任意时刻推入，
         * 用页面作用域会让离开详情页再回来后丢掉中间的消息。
         * 这里用一个进程级 scope，随注册表一起被 [PluginBindingRegistry.detach] 清理。
         */
        private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

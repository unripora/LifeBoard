package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.platform.PluginInvoker
import com.lifeboard.shared.platform.SessionKeepAlive
import kotlinx.coroutines.flow.Flow

/**
 * 把 [PluginInvoker] 的会话方法包成 [SessionEndpoint]。
 *
 * 控制器只依赖这个窄接口；真正的插件实例按 pluginId 由各平台的 Invoker 找回，
 * 因此插件被热重启换掉后，下一次调用自然落到新实例上，控制器不需要感知。
 *
 * ## 保活租约的来源
 *
 * 租约只有 JS 装载器才有（按需加载时才有「可能被空闲回收」这回事），
 * 挂在 [PluginInvoker.sessionKeepAlive] 上按 pluginId 取。
 * 端点因此仍然只认 [PluginInvoker] 这一个通用契约，
 * 非 JS 插件或未开启按需加载时返回空，控制器自动退回旧行为。
 */
class PluginInvokerSessionEndpoint(
    private val invoker: PluginInvoker,
    private val pluginId: String,
) : SessionEndpoint {
    override suspend fun start(config: SessionConfig): Boolean =
        invoker.sessionStart(pluginId, config)

    override suspend fun event(event: SessionEvent): SessionAction =
        invoker.sessionEvent(pluginId, event)

    override suspend fun stop() = invoker.sessionStop(pluginId)

    override fun setListener(listener: ((SessionUiEvent) -> Unit)?) =
        invoker.setSessionUiListener(pluginId, listener)

    override val deltas: Flow<String>
        get() = invoker.sessionDeltas(pluginId)

    override suspend fun acquireLease(): SessionKeepAlive? = invoker.sessionKeepAlive(pluginId)
}

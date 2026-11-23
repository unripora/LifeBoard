package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.platform.PluginInvoker
import kotlinx.coroutines.flow.Flow

/**
 * 把 [PluginInvoker] 的会话方法包成 [SessionEndpoint]。
 *
 * 控制器只依赖这个窄接口；真正的插件实例按 pluginId 由各平台的 Invoker 找回，
 * 因此插件被热重启换掉后，下一次调用自然落到新实例上，控制器不需要感知。
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
}

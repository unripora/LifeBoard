package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.LoginOptions
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginError
import com.lifeboard.plugin.api.PluginErrorCode
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.shared.platform.StreamingSourcePlugin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 尚未启动的插件的**委托外壳**。
 *
 * ## 为什么需要它
 *
 * 延迟实例化的目标是「扫描不执行插件代码」，但调用方（列表、登录、采集、会话）
 * 都按 [SourcePlugin] 使用插件。若让它们感知「还没启动」，每处都要改，且容易漏。
 *
 * 这个类让延迟对调用方透明：元数据（id、显示名、凭据槽、指标）在扫描期就已
 * 从 manifest 得到，立即可用；真正调用业务方法时，才通过 [loader] 的
 * `withStarted` 申请租约并执行。
 *
 * ## 为什么走 withStarted 而不是启动后直接持有
 *
 * 直接持有实例会让租约失效——运行时可能在两次调用之间被空闲回收，而外壳
 * 还拿着旧引用。走 `withStarted` 每次重新申请，既保证调用期间不被回收，
 * 也保证拿到的是当前代际的实例。
 */
internal class DeferredSourcePlugin(
    override val id: String,
    override val displayName: String,
    override val credentialSpec: CredentialSpec,
    override val metrics: List<MetricSpec>,
    private val loader: JsPluginLoader,
) : SourcePlugin, StreamingSourcePlugin {

    override val apiVersion: Int = PluginApi.VERSION

    /** 会话监听器：可能在未启动时注册，启动后转交 */
    @Volatile
    private var pendingListener: ((SessionUiEvent) -> Unit)? = null

    private fun unavailable(message: String): FetchResult.Failure =
        FetchResult.Failure(
            message = message,
            error = PluginError(
                code = PluginErrorCode.UNAVAILABLE,
                message = message,
                operation = "startup",
            ),
        )

    /** 把监听器挂到真实实例上（若已启动） */
    private fun syncListener(target: SourcePlugin) {
        pendingListener?.let { target.setSessionUiListener(it) }
    }

    override suspend fun login(
        context: PluginContext,
        inputs: Map<String, String>,
        options: LoginOptions?,
    ): LoginResult =
        when (val r = loader.withStartedDetailed(id) { it.login(context, inputs, options) }) {
            is JsPluginLoader.StartedResult.Ok -> r.value
            // 把原因带给用户：停用和加载失败需要完全不同的处置
            is JsPluginLoader.StartedResult.Disabled -> LoginResult.Failure(r.reason)
            is JsPluginLoader.StartedResult.Unavailable ->
                LoginResult.Failure("插件暂时不可用：${r.reason}")
        }

    override suspend fun logout(context: PluginContext) {
        // 未启动就没什么可登出的：凭据由宿主侧清除，不必为它拉起运行时
        loader.withStarted(id) { it.logout(context) }
    }

    override suspend fun authState(context: PluginContext): AuthState =
        when (val r = loader.withStartedDetailed(id) { it.authState(context) }) {
            is JsPluginLoader.StartedResult.Ok -> r.value
            // 问不出来必须抛出去：上层据此显示「待确认」，不能伪装成「未登录」
            is JsPluginLoader.StartedResult.Disabled -> throw IllegalStateException(r.reason)
            is JsPluginLoader.StartedResult.Unavailable -> throw IllegalStateException(r.reason)
        }

    override suspend fun fetch(context: PluginContext, date: String): FetchResult =
        when (val r = loader.withStartedDetailed(id) { it.fetch(context, date) }) {
            is JsPluginLoader.StartedResult.Ok -> r.value
            is JsPluginLoader.StartedResult.Disabled -> unavailable(r.reason)
            is JsPluginLoader.StartedResult.Unavailable -> unavailable("插件暂时不可用：${r.reason}")
        }

    override suspend fun health(context: PluginContext): PluginHealth =
        when (val r = loader.withStartedDetailed(id) { it.health(context) }) {
            is JsPluginLoader.StartedResult.Ok -> r.value
            is JsPluginLoader.StartedResult.Disabled -> PluginHealth.Broken(r.reason)
            is JsPluginLoader.StartedResult.Unavailable -> PluginHealth.Broken("插件暂时不可用：${r.reason}")
        }

    override suspend fun detailUi(context: PluginContext): PluginNode? =
        loader.withStarted(id) { it.detailUi(context) }

    override suspend fun onAction(
        context: PluginContext,
        actionId: String,
        value: String?,
    ): ActionResult {
        val result = loader.withStarted(id) { it.onAction(context, actionId, value) }
        return result ?: ActionResult.NoOp
    }

    override suspend fun onSessionStart(
        context: PluginContext,
        config: SessionConfig,
    ): Boolean = loader.withStarted(id) { it.onSessionStart(context, config) } ?: false

    override suspend fun onSessionEvent(
        context: PluginContext,
        event: SessionEvent,
    ): SessionAction =
        loader.withStarted(id) { it.onSessionEvent(context, event) } ?: SessionAction.Continue

    override suspend fun onSessionStop(context: PluginContext) {
        loader.withStarted(id) { it.onSessionStop(context) }
    }

    override fun setSessionUiListener(listener: ((SessionUiEvent) -> Unit)?) {
        pendingListener = listener
        // 只挂到**真实实例**上：用 find() 会在插件未启动/已回收时拿回本外壳，
        // syncListener() 再调自己 → 无限递归 StackOverflowError。
        // 未启动时登记到加载器里，等真实实例首次拉起时由 withStarted 补挂。
        loader.rememberSessionListener(id, listener)
        realPlugin()?.let { target -> target.setSessionUiListener(listener) }
    }

    /** 真实实例；没启动或已回收时为空。绝不能回退到本外壳。 */
    private fun realPlugin(): SourcePlugin? = loader.realPlugin(id)

    /**
     * 增量输出流。
     *
     * 访问它意味着详情页准备发起调用，因此这里直接触发启动——否则订阅方
     * 会拿到一个永远不会有数据的空流。
     */
    override val streamDeltas: SharedFlow<String>
        get() = (loader.ensureStarted(id)?.plugin as? StreamingSourcePlugin)?.streamDeltas
            ?: emptyDeltas

    private companion object {
        /** 启动失败时返回的空流，避免调用方拿到 null */
        val emptyDeltas: SharedFlow<String> = MutableSharedFlow()
    }
}

package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.JsException
import com.lifeboard.jsengine.PendingResultException
import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.LoginOptions
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJson
import com.lifeboard.shared.plugin.json.PluginJsonObject
import com.lifeboard.shared.platform.StreamingSourcePlugin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 用 JS 模块实现的 [SourcePlugin]。
 *
 * 适配职责只有三件：
 * 1. 把契约方法转成对 JS 导出函数的调用（参数走 JSON）
 * 2. 把 JS 返回值交给 [JsTypeConverter] 还原成契约类型
 * 3. 把 JS 侧异常收敛成契约允许的表达（登录失败 / 不健康 / 空列表）
 *
 * 为什么异常要收敛而不是往外抛：契约明确要求插件「返回失败而不是抛异常」，
 * 宿主对抛出的异常只有一层通用兜底，用户看不到原因。JS 插件里抛错是常事
 * （网络失败、JSON 解析失败），这里统一翻译成可读信息，排障才不至于抓瞎。
 *
 * ## 线程约束（关键）
 *
 * QuickJS 的 `JSContext` 只能在创建它的线程上使用。所有调用都通过
 * [JsPluginRuntime.runOnRuntime] 投到插件专用单线程执行，**不能**在这里切 Dispatchers。
 * 早期版本加了 `withContext(Dispatchers.Default)`，结果是全部调用静默失败。
 */
class JsSourcePlugin(
    override val id: String,
    override val displayName: String,
    override val credentialSpec: CredentialSpec,
    override val metrics: List<MetricSpec>,
    private val runtime: JsPluginRuntime,
    private val entryModule: String,
    private val contextBridge: JsPluginContextBridge,
    /**
     * 把本次调用的 [PluginContext] 交给桥接层。
     *
     * 桥接在加载时就注册给了 JS，而 context 是每次调用才有的，
     * 所以每次进入插件方法都要先注入，`ctx.credentials` 等才能拿到正确实例。
     */
    private val bindContext: (PluginContext) -> Unit,
    /**
     * manifest 里声明的 JS 接口版本。
     *
     * 必须回传插件自己声明的版本而不是宿主的 [PluginApi.VERSION]：
     * 两者是两套编号（前者管 `ctx.*` 函数集，后者管 APK 契约的方法），
     * 混用会让上层拿到的版本号既不能判断兼容性也没有别的用处。
     */
    declaredApiVersion: Int = 0,
    /**
     * manifest 里声明的自签 CA 指纹。
     *
     * 非空时，桥接层会把 HTTP/WS 的 TLS 信任收敛到「只认这张证书」。
     */
    internal val pinnedCaSha256: String? = null,
    /**
     * 单次调用的「无进展」等待上限。
     *
     * 抽成参数只是为了测试不必真的等 60 秒；生产一律用默认值。
     */
    private val inactivityTimeoutMillis: Long = DEFAULT_INACTIVITY_TIMEOUT_MILLIS,
) : SourcePlugin, StreamingSourcePlugin {

    override val apiVersion: Int = PluginApi.VERSION

    /**
     * 插件声明使用的 `ctx.*` 接口版本，仅在加载期做过兼容判定。
     *
     * 单独暴露出来是为了让自检页能显示「这个包按哪一代接口写的」——
     * 排查「插件在旧版宿主上缺函数」时，这是唯一能直接看到的线索。
     */
    val jsApiVersion: Int =
        (JsPluginApi.evaluate(declaredApiVersion) as? JsPluginApi.Verdict.Compatible)
            ?.declared ?: declaredApiVersion

    /**
     * 串行化同一插件的调用。
     *
     * 两个作用：一是 QuickJS 单线程，调用必须顺序进入；二是
     * [bindContext] 是「全局当前上下文」，并发进入会让后一次调用覆盖前一次的 context。
     */
    private val mutex = Mutex()

    /**
     * 调用键序号。
     *
     * 每次进入插件方法都要拿一个新键。原因见 [invoke]：
     * 固定键会让「上一次还在等网络」与「本次新调用」撞在一起，
     * 后者复用到前者的 Promise，拿到的是上一次的结果。
     */
    private val invocationSeq = AtomicLong(0)

    /**
     * fetch 期间生效的采集日期。
     *
     * JS 侧 `ctx.reportRawRecords(key, records)` 不传日期时，默认归属本次采集日，
     * 与 `HostPluginContext.bindCollectDate` 的语义保持一致。
     */
    @Volatile
    private var collectDate: String = ""

    /**
     * 当前活跃会话使用的上下文；非会话期间为 null。
     *
     * 会话的后台循环在两次调用之间也会被常驻泵推进，那时需要在被推进前
     * 把上下文重新绑成会话的，否则循环里的 `ctx.*` 会读到上一个短时调用
     * 留下的临时实例（[PluginContext] 是每次调用构造的）。
     */
    @Volatile
    private var sessionContext: PluginContext? = null

    /**
     * 增量输出的中转缓冲。
     *
     * 用 `DROP_OLDEST` 而不是挂起：`ctx.stream.emit` 是同步宿主函数，调用发生在
     * 插件单线程上，不能在这里等界面消费——跟不上时宁可丢掉最早的片段，
     * 也不能把插件卡住。256 条足以覆盖一屏文字的分片。
     */
    private val streamBuffer = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val streamDeltas: SharedFlow<String> = streamBuffer.asSharedFlow()

    /**
     * 由 `ctx.stream.emit` 桥接调用，把一段文本推给订阅方（详情页）。
     *
     * 空串直接丢弃：它不改变界面内容，却会让订阅方以为收到一次更新。
     */
    internal fun emitStreamDelta(text: String) {
        if (text.isNotEmpty()) streamBuffer.tryEmit(text)
    }

    /**
     * 会话主动事件的接收方；由宿主在会话开始时注册。
     *
     * `@Volatile`：JS 插件在自己的线程上调用 `ctx.session.emit`，
     * 而注册/解除发生在宿主线程，必须保证可见性。
     */
    @Volatile
    private var sessionUiListener: ((SessionUiEvent) -> Unit)? = null

    override fun setSessionUiListener(listener: ((SessionUiEvent) -> Unit)?) {
        sessionUiListener = listener
    }

    /** 由 `ctx.session.emit` 桥接调用；没有监听方时直接丢弃 */
    internal fun emitSessionUiEvent(event: SessionUiEvent) {
        sessionUiListener?.invoke(event)
    }

    init {
        runtime.setPumpHook { sessionContext?.let(bindContext) }
    }

    override suspend fun login(
        context: PluginContext,
        inputs: Map<String, String>,
        options: LoginOptions?,
    ): LoginResult {
        val args = PluginJsonObject().apply {
            inputs.forEach { (k, v) -> put(k, v) }
        }
        // 意图作为第二个实参：只有声明 contract.loginIntent 的插件会解读它，
        // 旧插件 `login(inputs)` 只写一个参数也能正常工作
        val intentJson = options?.intent
            ?.let { "{\"intent\":${PluginJson.quote(it.name.lowercase())}}" }
            ?: "null"
        val raw = invoke(context, "login", "[$args, $intentJson]")
            ?: return LoginResult.Failure("插件未实现 login")
        return JsTypeConverter.toLoginResult(raw)
    }

    override suspend fun logout(context: PluginContext) {
        invoke(context, "logout", "[]")
    }

    override suspend fun authState(context: PluginContext): AuthState {
        // invoke 的 null 有两种含义：插件没实现 authState、或这次调用失败
        // （运行时尚未就绪 / 已被关闭 / 脚本抛异常）。两种都不能当成
        // 「插件说它未登录」——否则用户登录成功了，界面却写着未登录。
        // 交给上层判定：抛出去由 PluginInvoker 翻译成「这次问不出来」。
        val raw = invoke(context, "authState", "[]")
            ?: throw IllegalStateException("插件 authState 未实现或调用失败")
        return JsTypeConverter.toAuthState(raw)
    }

    override suspend fun fetch(context: PluginContext, date: String): FetchResult {
        collectDate = date
        val raw = invoke(context, "fetch", "[${PluginJson.quote(date)}]")
        // 未实现 fetch 与执行失败都必须明确说「这次没取到」。
        // 早期这里返回空列表，把故障伪装成「今日无数据」，用户无从判断
        if (raw == null) {
            return FetchResult.Failure("插件 fetch 未实现或执行失败，详见日志")
        }
        return JsTypeConverter.toFetchResult(raw)
    }

    override suspend fun health(context: PluginContext): PluginHealth {
        val raw = invoke(context, "health", "[]")
            ?: return PluginHealth.Degraded("插件未实现 health")
        return JsTypeConverter.toPluginHealth(raw)
    }

    override suspend fun detailUi(context: PluginContext): PluginNode? {
        val raw = invoke(context, "detailUi", "[]") ?: return null
        val parsed = JsTypeConverter.toUiTree(raw)
        // 截断与未知类型都要在日志里留痕：用户在界面上只会看到「内容略多」
        // 或「暂不支持」的占位，没有日志就无从判断是插件写错还是宿主太旧
        if (parsed.truncated) {
            AppLogger.warn("插件 ${context.pluginId} 的详情页节点数超上限，已截断")
        }
        return parsed.root
    }

    override suspend fun onAction(
        context: PluginContext,
        actionId: String,
        value: String?,
    ): ActionResult {
        // 第二个实参固定占位：JS 侧 onAction(actionId) 只写一个参数也能正常工作，
        // 需要输入文本时再取第二个——不必为了可选参数维护两套签名
        val valueJson = if (value == null) "null" else PluginJson.quote(value)
        val raw = invoke(context, "onAction", "[${PluginJson.quote(actionId)}, $valueJson]")
            ?: return ActionResult.NoOp
        return JsTypeConverter.toActionResult(raw)
    }

    /**
     * 启动会话：转发给 JS 的 `sessionStart(config)`。
     *
     * JS 侧必须**显式** `return true` 才算启动成功。不把「没写返回值」当成成功，
     * 是因为「插件没实现会话」与「实现了但启动失败」在上层都表现为不可用，
     * 而误判成成功会让 UI 进入一个永远收不到消息的空对话。
     */
    override suspend fun onSessionStart(
        context: PluginContext,
        config: SessionConfig,
    ): Boolean {
        val options = PluginJsonObject().apply {
            config.options.forEach { (k, v) -> put(k, v) }
        }
        sessionContext = context
        val raw = invoke(context, "sessionStart", "[$options]")
        if (raw == null) {
            // 未实现或执行失败：不留会话上下文，泵也就不再为它绑上下文
            sessionContext = null
            return false
        }
        return raw.trim() == "true"
    }

    override suspend fun onSessionEvent(
        context: PluginContext,
        event: SessionEvent,
    ): SessionAction {
        val payload = PluginJsonObject().apply {
            put("type", event.type)
            put("payload", event.payload)
        }
        val raw = invoke(context, "sessionEvent", "[$payload]")
            ?: return SessionAction.Continue
        return JsTypeConverter.toSessionAction(raw)
    }

    override suspend fun onSessionStop(context: PluginContext) {
        try {
            invoke(context, "sessionStop", "[]")
        } finally {
            // 无论停止是否成功都要解绑：会话已经不该再占用上下文，
            // 否则泵会把之后所有后台推进都绑到一个过期的会话上
            sessionContext = null
        }
    }

    /**
     * 统一调用入口。
     *
     * ## 为什么必须真的等
     *
     * JS 侧插件方法都是 `async`，内部 `await ctx.http.get(...)` 要等宿主回调。
     * 这种调用第一次进 `callFunction` 必然抛 [PendingResultException]。
     *
     * 早期版本在这里直接返回 null，上层于是退到契约默认值——`login` 退成
     * `Failure("插件未实现 login")`、`fetch` 退成空列表。用户看到的是
     * 「插件没有实现 login」，而实际上登录请求已经发出去了。
     *
     * 现在的做法是**轮询重试**：每次带着同一个 callKey 重新进入，引擎发现该键
     * 有挂起的 Promise 就不再执行函数体，只检查它是否落定。HTTP 回调把结果投回
     * 专用线程后，下一次重试就能拿到终值。
     *
     * ## 为什么要分线程而不能在这里 sleep
     *
     * 等待期间必须让出专用线程：job queue 的推进（[JsPluginRuntime.resolveAndDrain]）
     * 也要在这个线程上排队。若在这里同步阻塞，回调永远排不进来，就成了死等。
     * 所以用协程 [delay] 让出线程，每次重试都是一次独立的线程投递。
     *
     * ## 为什么要区分「未实现」与「执行失败」
     *
     * 两者在契约里都是可接受的降级，但对用户含义完全不同：
     * - 未实现（导出函数不存在）：插件本来就没提供这个能力，静默降级是对的
     * - 执行失败（脚本抛错、被中断、Promise 被 reject）：是**故障**，必须留日志
     *
     * @return JS 返回的 JSON 文本；调用失败或未实现时返回 null，
     *   由各方法退到契约允许的安全默认值
     */
    private suspend fun invoke(
        context: PluginContext,
        function: String,
        argsJson: String,
    ): String? = mutex.withLock {
        bindContext(context)
        contextBridge.setCollectDate(collectDate)

        // 每次调用一个新键：引擎用它区分「同一次挂起调用的续等」与「新的一次调用」
        val callKey = "$id#$function#${invocationSeq.incrementAndGet()}"

        val startedAt = System.currentTimeMillis()

        while (true) {
            try {
                // 必须投到插件专用线程：QuickJS 上下文不能跨线程使用。
                // 同时施加单段同步预算：纯同步死循环不会产生 Promise 落地，
                // 只靠「无进展超时」永远判不出来
                val raw = runtime.runOnRuntimeWithBudget { rt ->
                    rt.callFunction(
                        moduleName = entryModule,
                        functionName = function,
                        argsJson = argsJson,
                        callKey = callKey,
                    )
                }
                // 本次调用正常返回 → 清零连续超限计数（历史超时不应永久拖累插件）
                runtime.clearSyncBudgetFaults()
                return@withLock raw
            } catch (e: JsSyncBudgetExceededException) {
                // 宿主主动打断：丢弃挂起调用，本次结果按失败返回。
                // 连续超限由上层（故障停用策略）处理，这里只负责不把线程卡死
                runtime.post { rt -> rt.dropInflightCall(callKey) }
                AppLogger.error("JS 插件 $id.$function ${e.message}")
                return@withLock null
            } catch (e: PendingResultException) {
                // 还没落定。让出线程等回调推进，然后带着同一个键再问一次
                //
                // 超时按「无进展」而不是「总时长」算：会话类调用可以合理地跑很久
                // （等用户输入、等下一帧），只要期间有 Promise 落地就算还在推进；
                // 而真的卡死时不会有任何落地，计时就不会被刷新。
                // startedAt 必须一起取最大值：否则上一次活动在两分钟前时，
                // 刚进来的调用一遇到挂起就会被判成「已经超时」。
                val lastProgress = maxOf(startedAt, runtime.lastActivityAt())
                if (System.currentTimeMillis() - lastProgress >= inactivityTimeoutMillis) {
                    // 放弃等待：必须丢弃挂起的 Promise，否则它和它的 resolver 会一直挂在引擎里
                    runtime.post { rt -> rt.dropInflightCall(callKey) }
                    AppLogger.warn(
                        "JS 插件 $id.$function 已 $inactivityTimeoutMillis ms 无进展（Promise 未落地），已放弃"
                    )
                    return@withLock null
                }
                delay(ASYNC_POLL_INTERVAL_MILLIS)
            } catch (e: JsException) {
                // 函数不存在（未实现）与执行失败都走这里。用消息前缀区分：
                // native 侧对缺失函数用的是 "not a function" 文案。
                if (e.message?.contains("not a function") == true) {
                    AppLogger.debug("JS 插件 $id 未实现 $function，按缺省行为处理")
                } else {
                    AppLogger.error("JS 插件 $id.$function 执行失败：${e.message}")
                }
                return@withLock null
            } catch (e: IllegalStateException) {
                // 运行时已关闭（插件被卸载/撤销信任）——不是插件的问题，只记一次
                AppLogger.warn("JS 插件 $id.$function 调用时运行时已关闭")
                return@withLock null
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private companion object {
        /**
         * 单次插件调用的「无进展」等待上限。
         *
         * 登录链路要串好几轮 HTTP（握手 → 提交密码 → 二次验证），
         * 加上小米服务端偶发慢响应，60 秒是留了余量的值。
         * 语义是「多久没有任何 Promise 落地」，不是调用总时长——
         * 所以会话类调用只要还在收消息就不会被掐断。
         */
        const val DEFAULT_INACTIVITY_TIMEOUT_MILLIS = 60_000L

        /**
         * 轮询间隔。
         *
         * 用轮询而不是回调通知：native 侧没有「Promise 落定」的推送通道，
         * 而 `callFunction` 本身是同步查询。
         * 50ms 对网络级操作足够细，对 CPU 也几乎无感。
         */
        const val ASYNC_POLL_INTERVAL_MILLIS = 50L
    }
}

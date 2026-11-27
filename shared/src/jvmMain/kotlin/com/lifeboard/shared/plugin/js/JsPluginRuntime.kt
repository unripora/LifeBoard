package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.JsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay

/**
 * 一个 JS 插件的运行时宿主。
 *
 * ## 为什么必须有专用单线程
 *
 * QuickJS 的 `JSContext` **只能在创建它的线程上使用**。早期版本在
 * `Dispatchers.Default` 里调 `callFunction`，结果是所有调用静默失败——
 * native 层拿不到有效上下文，异常被兜底 catch 吞掉，看起来像「插件不工作」。
 *
 * 所以这里固定一个单线程调度器：运行时在它上面创建，之后**所有**对
 * `JsRuntime` 的调用都投到同一个线程执行。这同时顺带解决了并发问题：
 * 同一插件的多次调用天然按提交顺序串行。
 *
 * ## 生命周期
 *
 * [close] 会取消所有在途任务并释放 native 资源。插件卸载或撤销信任时必须调用，
 * 否则 QuickJS 的堆（默认上限 64MB）会一直占着。
 */
class JsPluginRuntime private constructor(
    val pluginId: String,
) : AutoCloseable {

    /** 专用单线程：运行时创建与所有调用都在这里。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "js-plugin-$pluginId").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    /** 用 SupervisorJob：单次调用失败不应该让整个插件的调度器失效。 */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 运行时本身；只在 [dispatcher] 线程上访问。 */
    private var runtime: JsRuntime? = null

    @Volatile
    private var closed = false

    /**
     * 最近一次「插件实质推进」的时间戳。
     *
     * 推进的定义只有一种：某个挂起的 Promise 落了地（宿主回调完成）。
     * 它用来支撑「无活动超时」——见 [JsSourcePlugin] 的 invoke 循环。
     * 空转的 job queue 推进不算推进，否则任何调用都不会超时。
     */
    @Volatile
    private var lastActivityAt: Long = System.currentTimeMillis()

    /** 单段同步预算超限次数；连续超限用于停用插件 */
    private val syncBudgetExceeded = java.util.concurrent.atomic.AtomicInteger(0)

    /** 常驻泵每轮推进前执行的钩子（例如恢复会话上下文绑定）；在专用线程上调用 */
    @Volatile
    private var pumpHook: (() -> Unit)? = null

    /**
     * 在专用线程上初始化运行时并装载插件。
     *
     * @param modules 模块名 → 源码
     * @param entry 入口模块名
     * @param buildBridges 构造桥接（在专用线程上执行，便于拿到 runtime 后再包装）
     * @return 成功返回 true；任一环节失败返回 false 并保留失败原因
     */
    fun start(
        modules: Map<String, String>,
        entry: String,
        buildBridges: (JsRuntime) -> CompositeHostDispatcher,
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("运行时已关闭"))

        return runCatching {
            val task = executor.submit<Result<Unit>> {
                try {
                    val rt = JsRuntime()
                    runtime = rt

                    // 先装桥接再执行模块：模块顶层代码可能就要用 ctx.*
                    val bridges = buildBridges(rt)
                    bridges.install(rt)

                    rt.setModules(modules)
                    rt.evaluateModule(entry)

                    // 模块装载完再起泵：顶层代码还没跑之前推进 job queue 没有意义
                    startPump()

                    Result.success(Unit)
                } catch (e: Throwable) {
                    // 必须抓 Throwable 而不是 Exception：native 库加载失败抛的是
                    // UnsatisfiedLinkError，属于 Error 分支，`catch (Exception)` 接不住，
                    // 它会在专用线程里逃逸出去，把「插件跑不起来」升级成进程崩溃
                    Result.failure(e)
                }
            }
            task.get()
        }.getOrElse { Result.failure(it) }
    }

    /**
     * 暴露运行时引用，供 [JsSourcePlugin] 直接调用。
     *
     * 安全性由调用方保证：[JsSourcePlugin] 的调用全部发生在
     * 本类专用线程上（由 [runOnRuntime] 投递），不会跨线程使用。
     */
    fun runtimeRef(): JsRuntime =
        runtime ?: throw IllegalStateException("运行时未初始化或已关闭")

    /**
     * 在专用线程上执行一段针对运行时的操作。
     *
     * 会把 [ExecutionException] 拆开、重新抛出原本的异常：
     * 否则调用方 `catch (JsException)` 抓不到——JS 的抛错会被包成
     * `ExecutionException`，看起来像「未知错误」，契约层的兜底逻辑全部失效。
     */
    fun <T> runOnRuntime(block: (JsRuntime) -> T): T {
        val rt = runtimeRef()
        return try {
            executor.submit<T> { block(rt) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 调度一段异步任务到专用线程。 */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /**
     * 把一段「只碰运行时」的操作投递到专用线程执行，不等待结果。
     *
     * 这是异步回灌的唯一入口：OkHttp 回调等工作在其它线程产生的结果，
     * 必须先投到这里再交给 QuickJS。直接在回调线程调 `resolvePromise`
     * 属于跨线程使用 `JSContext`，轻则静默失败，重则踩到已释放的上下文。
     *
     * 投递失败（运行时已关闭）时静默丢弃：调用方通常是网络回调，
     * 插件卸载后回调晚到是正常现象，不该再报错。
     */
    fun post(block: (JsRuntime) -> Unit) {
        if (closed) return
        runCatching {
            executor.execute {
                val rt = runtime ?: return@execute
                runCatching { block(rt) }
            }
        }
    }

    /**
     * 落地一个异步结果，并在同一线程上立刻推进 job queue。
     *
     * 两步必须绑在一起做：`resolvePromise` 只投递结果不推进微任务，
     * 若分两次投递，中间可能插入新调用，`await` 之后的代码会被推迟到
     * 下一个不相关的事件才能跑起来——表现为「偶发拿不到数据」。
     */
    fun resolveAndDrain(token: String, resultJson: String = "null", error: String? = null) {
        post { rt ->
            rt.resolvePromise(token, resultJson, error)
            rt.drainJobs()
            lastActivityAt = System.currentTimeMillis()
        }
    }

    /** 最近一次实质推进（Promise 落地）的时间戳，供无活动超时判定 */
    fun lastActivityAt(): Long = lastActivityAt

    /**
     * 设置常驻泵每轮推进前的钩子。
     *
     * 泵会在没有调用进入的情况下继续推进 job queue，此时 JS 里的 `ctx.*`
     * 需要一个「当前上下文」。短时调用只会把上下文绑在自己那一次进入里，
     * 所以会话期间必须由钩子把会话上下文重新绑上，否则后台循环里的
     * `ctx.credentials` 之类会读到上一次调用留下的临时实例。
     */
    fun setPumpHook(hook: (() -> Unit)?) {
        pumpHook = hook
    }

    /**
     * 常驻泵：按固定间隔推进一次 job queue。
     *
     * 为什么需要它：`ctx.ws.recv` 这类宿主函数是拉取模型，插件用
     * 「后台 async 循环 + await」消费消息。循环的续体只有在 job queue
     * 被推进时才继续跑，而落地回调并非每次都会自己推进（例如只投递结果的
     * 回调线程）。没有泵时，后台循环会停在某个 await 上不再前进，
     * 表现为「连接还在、消息却不再处理」。
     *
     * 间隔取 5 ms：单次空转实测 4~6 µs（M0 验证），空转开销约为千分之一 CPU；
     * 再小会把专用线程压满，再大则后台消息处理出现可感知延迟。
     * 用协程 `delay` 而不是定时器线程：等待期间不占线程，推进时才回到专用线程。
     */
    private fun startPump() {
        scope.launch {
            while (true) {
                delay(PUMP_INTERVAL_MILLIS)
                runCatching { pumpHook?.invoke() }
                runCatching { runtime?.drainJobs() }
            }
        }
    }

    /**
     * 在专用线程上执行一段针对运行时的操作，并施加**单段同步执行预算**。
     *
     * ## 为什么需要独立预算
     *
     * 现有的「60 秒无进展」只覆盖「等宿主回调」：插件只要持续 `await`，
     * 计时就会被刷新。但 `for(;;){}` 这类**纯同步**死循环根本不会产生
     * Promise 落地，宿主线程会被永久占住——扫描、卸载、退出全部挂死。
     *
     * 这里从进入 JS 到真正返回计一次预算；超时即请求中断，
     * 让解释器在下一个安全点抛异常退出。
     *
     * ## 为什么超时后还要再等一小段
     *
     * `requestInterrupt` 是异步的：只置标志，解释器在安全点才检查。
     * 立即返回会让调用方以为线程空闲，实际仍在跑。短暂等待后无论是否退出
     * 都向上抛错，由上层决定隔离/重建。
     */
    fun <T> runOnRuntimeWithBudget(block: (JsRuntime) -> T): T {
        val rt = runtimeRef()
        val future = executor.submit<T> { block(rt) }
        return try {
            future.get(SYNC_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            runCatching { runtime?.requestInterrupt() }
            // 给解释器一点时间在安全点退出；超时也不阻塞永久
            runCatching { future.get(INTERRUPT_GRACE_MS, TimeUnit.MILLISECONDS) }
            syncBudgetExceeded.incrementAndGet()
            throw JsSyncBudgetExceededException(
                "插件 $pluginId 单段同步执行超过 ${SYNC_BUDGET_MS}ms，已请求中断"
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 单段同步预算超限的累计次数，供故障停用策略使用 */
    fun syncBudgetExceededCount(): Int = syncBudgetExceeded.get()

    /** 正常完成一次业务调用后清零，避免历史超时影响后续判定 */
    fun clearSyncBudgetFaults() {
        syncBudgetExceeded.set(0)
    }

    /**
     * 调用插件的 `onUnload` 钩子（尽力、有超时）。
     *
     * ## 为什么单独一个入口而不复用 [runOnRuntime]
     *
     * 卸载期不允许无限等待：插件可能在钩子里写死循环或阻塞 I/O。
     * 这里投递到专用线程后**限时等待**，超时即请求中断并返回失败，
     * 调用方据此继续回收，不会把关闭流程永久挂住。
     *
     * ## 不保证一定执行
     *
     * 运行时已关闭、中断态、或插件没实现 `onUnload` 时都会快速返回失败。
     * 因此**关键状态不能只靠这个钩子保存**——这是本任务的硬约束。
     *
     * @return 成功执行返回 success；超时、未实现或运行时不可用返回 failure
     */
    fun callUnloadHook(entryModule: String, timeoutMillis: Long): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("运行时已关闭"))
        val task = runCatching {
            executor.submit<Result<Unit>> {
                runCatching {
                    val rt = runtime ?: return@submit Result.failure(
                        IllegalStateException("运行时未初始化")
                    )
                    rt.callFunction(
                        moduleName = entryModule,
                        functionName = "onUnload",
                        argsJson = "[]",
                        callKey = "$pluginId#onUnload",
                    )
                    Result.success(Unit)
                }.getOrElse { Result.failure(it) }
            }
        }.getOrElse { return Result.failure(it) }

        return runCatching {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }.getOrElse { error ->
            // 超时：请求中断，让解释器在下一次安全点退出。不等结果
            runCatching { runtime?.requestInterrupt() }
            Result.failure(
                if (error is java.util.concurrent.TimeoutException) {
                    IllegalStateException("onUnload 超过 ${timeoutMillis}ms 未完成，已请求中断")
                } else {
                    error
                }
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 加超时：若插件里有死循环，native 中断会兜底；这里再保一层，
        // 避免关停把调用方（扫描/退出流程）永久挂住。
        runCatching {
            executor.submit {
                runCatching { runtime?.close() }
                runtime = null
            }.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        scope.cancel()
        executor.shutdownNow()
    }

    companion object {
        /** 关停等待上限：超时后放弃，交给 native 中断兜底 */
        private const val CLOSE_TIMEOUT_SECONDS = 5L

        /** 常驻泵的推进间隔（毫秒），取值理由见 [startPump] */
        private const val PUMP_INTERVAL_MILLIS = 5L

        /** 单段同步执行预算：超过即视为卡死，请求中断 */
        const val SYNC_BUDGET_MS = 1_000L

        /** 请求中断后等待解释器退出的宽限 */
        private const val INTERRUPT_GRACE_MS = 500L

        fun create(pluginId: String): JsPluginRuntime = JsPluginRuntime(pluginId)
    }
}

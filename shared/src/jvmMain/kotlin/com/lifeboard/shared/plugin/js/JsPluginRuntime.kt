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

        fun create(pluginId: String): JsPluginRuntime = JsPluginRuntime(pluginId)
    }
}

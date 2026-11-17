package com.lifeboard.jsengine

/**
 * 本机能否创建 JS 运行时。
 *
 * 纯查询：不建上下文、不抛异常。调用方（插件加载器、界面）应当在构造
 * [JsRuntime] 之前先问这一句。
 *
 * 为什么必须是顶层函数而不是 `JsRuntime.isAvailable()`：`JsRuntime` 已经有一个
 * `private companion object` 装内部常量，把入口放进那里就得把整个 companion
 * 提升为 public，连带把 `<error:` 这类实现细节暴露出去。顶层函数没有这个问题。
 *
 * Android 侧只编译了部分 ABI（见 js-engine 的 `abiFilters`），装在不支持的
 * 架构上时构造会抛 `UnsatisfiedLinkError`。插件是可选能力，不该因为它跑不起来
 * 就让整个 App 打不开。
 */
fun jsEngineAvailable(): Boolean = JsNativeBridge.isAvailable()

/**
 * QuickJS 运行时。
 *
 * 每个实例拥有独立的 JS 堆与全局对象，插件之间的脚本互不可见。
 * 使用方必须在用完后调用 [close]，否则 native 侧内存不会被释放。
 *
 * 非线程安全：同一实例只能在一个线程上使用（QuickJS 本身的约束）。
 *
 * 平台差异全部收在 [JsNativeBridge] 里：
 *   - Android 走 JNI（System.loadLibrary）
 *   - 桌面走 JNA（按符号名绑定 lifeboard-js.dll）
 * 本类只描述「引擎应该有什么行为」，两端行为一致。
 */
class JsRuntime : AutoCloseable {

    private var handle: Long = JsNativeBridge.create()

    init {
        check(handle != 0L) { "创建 QuickJS 运行时失败" }
    }

    /**
     * 执行一段脚本（global 作用域），返回值的字符串形式。
     *
     * 这是最小验证入口：只证明引擎能跑起来。
     * 插件调用走 [evaluateModule]，不复用此方法。
     *
     * @throws JsException 脚本抛出异常或语法错误时
     */
    fun evaluate(code: String, filename: String = "<eval>"): String {
        check(handle != 0L) { "运行时已关闭" }
        return JsNativeBridge.evaluate(handle, code, filename).unwrap()
    }

    /**
     * 注册模块源码表。必须在 [evaluateModule] 之前调用。
     *
     * 模块名即 import 语句里写的路径，例如 main.js 里 `import {md5} from './lib/crypto.js'`
     * 就需要表中存在键 `lib/crypto.js`（相对路径会被 QuickJS 归一化后去掉前导 `./`）。
     *
     * @param modules 模块名 → 源码
     */
    fun setModules(modules: Map<String, String>) {
        check(handle != 0L) { "运行时已关闭" }
        JsNativeBridge.setModules(
            handle,
            JsonArrays.encode(modules.keys.toList()),
            JsonArrays.encode(modules.values.toList()),
        )
    }

    /**
     * 以 ES 模块方式执行指定模块，返回其导出名列表。
     *
     * 执行时会连带解析并执行所有 import 的依赖，并驱动 Promise 微任务，
     * 因此模块顶层 await 也能完成。
     *
     * @return 导出名，如 ["login", "fetch", "health"]
     * @throws JsException 编译失败、执行抛错、模块未注册时
     */
    fun evaluateModule(moduleName: String): List<String> {
        check(handle != 0L) { "运行时已关闭" }
        return JsonArrays.decode(JsNativeBridge.evaluateModule(handle, moduleName).unwrap())
    }

    /**
     * 调用模块导出的函数。
     *
     * 参数与返回值都走 JSON 文本：调用方传入 JSON 数组文本（如 `["2026-11-11"]`），
     * 拿到 JSON 结果文本后自行解析成目标类型。这样跨语言的类型映射只在一处维护。
     *
     * 异步函数（`export async function`）会在同一线程内驱动 job queue 等它落定；
     * 若其 Promise 依赖宿主回调（如 HTTP），会抛 [PendingResultException]。
     * 宿主注入回调结果后，用**同一个** [callKey] 再调本方法即可接着取结果——
     * 引擎会复用那个挂起的 Promise，不会重新执行函数体。
     *
     * @param moduleName 模块名，与 [setModules] 的键一致
     * @param functionName 导出函数名
     * @param argsJson JSON 数组文本，省略参数时传 `"[]"`
     * @param callKey 调用标识，**必须在并发/重试场景下唯一**。
     *   推荐格式：`"$pluginId#$function#${自增序号}"`。
     *   用「插件 + 函数」这种固定字符串会出问题：上一次调用还在等网络时，
     *   下一次同函数调用会复用到上一次的 Promise，拿到的是上一次的结果。
     * @return JSON 文本（可能是 `"null"`）
     * @throws JsException 编译失败、函数不存在、执行抛错、Promise 被 reject
     * @throws PendingResultException 需要宿主异步完成，尚未落定
     */
    fun callFunction(
        moduleName: String,
        functionName: String,
        argsJson: String = "[]",
        callKey: String = "",
    ): String {
        check(handle != 0L) { "运行时已关闭" }
        val raw = JsNativeBridge.callFunction(handle, moduleName, functionName, argsJson, callKey)
        if (raw == PENDING_MARKER) {
            throw PendingResultException("$moduleName.$functionName 仍在等待宿主回调", callKey)
        }
        return raw.unwrap()
    }

    /**
     * 注册宿主函数，让 JS 能调用 Kotlin 侧能力。
     *
     * [names] 是 JS 侧的全局路径名，支持点分，如 `"ctx.http.get"`：
     * 引擎会自动逐级创建 `ctx`、`ctx.http` 对象，把函数挂到叶子上。
     *
     * 注册时机必须在 [setModules] 之后也可以——[setModules] 重建上下文时会
     * 自动把已注册的宿主函数重新挂上。
     *
     * @param names 形如 ["ctx.http.get", "ctx.log.d"]
     * @param dispatcher 按名字分发的实现
     */
    fun registerHostFunctions(names: List<String>, dispatcher: HostFunctionDispatcher) {
        check(handle != 0L) { "运行时已关闭" }
        JsNativeBridge.registerHostFunctions(handle, JsonArrays.encode(names), dispatcher)
    }

    /**
     * 落地一个由宿主异步完成的 Promise。
     *
     * 使用场景：JS 调用 `await ctx.http.get(...)` 时，宿主返回
     * [HostFunctionDispatcher.pending] 标记，JS 侧拿到挂起的 Promise；
     * 宿主完成异步操作后调用本方法投递结果，`await` 才会继续。
     *
     * @param token 与 [HostFunctionDispatcher.pending] 使用的 token 一致
     * @param resultJson 成功的 JSON 结果
     * @param error 非空表示失败，内容作为 Promise reject 的原因
     * @return 是否找到并落地了对应 Promise（token 不存在时返回 false）
     */
    fun resolvePromise(token: String, resultJson: String = "null", error: String? = null): Boolean {
        check(handle != 0L) { "运行时已关闭" }
        return JsNativeBridge.resolvePromise(handle, token, resultJson, error)
    }

    /**
     * 驱动 job queue，让已 resolve 的 Promise 继续推进。
     *
     * 与 [resolvePromise] 分开是必须的：[resolvePromise] 只投递结果，不做推进，
     * 否则当它在宿主函数执行期间被同步调用时会重入 JS 栈，
     * 表现为 "Maximum call stack size exceeded"。
     *
     * @return 执行的 job 数量；-1 表示有 job 抛出异常
     */
    fun drainJobs(): Int {
        check(handle != 0L) { "运行时已关闭" }
        return JsNativeBridge.drainJobs(handle)
    }

    /**
     * 请求中断当前正在执行的脚本。
     *
     * **可以从任意线程调用**：它只置一个原子标志，不碰任何 JS 对象。
     * 解释器在下一次安全点检查到标志后抛异常退出，用于兜底插件里的死循环
     * （如 `for(;;){}`）——这类代码没有任何 await，宿主无法通过常规方式打断。
     *
     * 中断是「一次性」的：下一次 [callFunction] 开始时会自动清除，
     * 因此不需要调用方额外复位。
     */
    fun requestInterrupt() {
        if (handle != 0L) {
            JsNativeBridge.requestInterrupt(handle)
        }
    }

    /**
     * 丢弃某个调用键上挂起的 Promise。
     *
     * 宿主放弃等待（超时、用户取消、插件卸载）后必须调用，否则
     * Promise 句柄会一直挂在那里泄漏，且后续同键调用会拿到永不落定的旧 Promise。
     *
     * @return 是否真的移除了记录
     */
    fun dropInflightCall(callKey: String): Boolean {
        if (handle == 0L) return false
        return JsNativeBridge.dropCall(handle, callKey)
    }

    /** 触发一次 GC，用于插件卸载后及时回收。 */
    fun gc() {
        if (handle != 0L) {
            JsNativeBridge.gc(handle)
        }
    }

    override fun close() {
        if (handle != 0L) {
            JsNativeBridge.destroy(handle)
            handle = 0L
        }
    }

    private companion object {
        const val ERROR_PREFIX = "<error:"
        const val PENDING_MARKER = "<pending>"

        /** native 层用 `<error:...>` 前缀表示失败，这里统一转成异常。 */
        fun String.unwrap(): String {
            if (startsWith(ERROR_PREFIX) && endsWith(">")) {
                throw JsException(removePrefix(ERROR_PREFIX).removeSuffix(">"))
            }
            return this
        }
    }
}

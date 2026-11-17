package com.lifeboard.jsengine

/**
 * 引擎 native 层的平台适配点。
 *
 * Android 与桌面各有一份 actual 实现：
 *   - androidMain：JNI（符号名由 JNI 规范固定，`Java_com_lifeboard_jsengine_JsNativeBridge_*`）
 *   - desktopMain：JNA 按 `lb_*` 符号名绑定 lifeboard-js.dll
 *
 * 所有方法都在同一线程上调用（[JsRuntime] 本身非线程安全），
 * 因此实现方无需额外同步。
 */
internal expect object JsNativeBridge {

    /**
     * native 库是否可用。
     *
     * 为什么需要这个查询：Android 侧只编译了部分 ABI（见 js-engine 的
     * `abiFilters`），装在未覆盖架构的设备上时 `System.loadLibrary` 会抛
     * `UnsatisfiedLinkError`。若让它在初始化块里直接抛，崩溃点是对象首次被
     * 访问的那一刻——也就是 `MainActivity.onCreate` 构造插件宿主时，
     * 表现为**整个 App 启动即闪退**。
     *
     * 插件是可选功能，跑不起来不该等于 App 打不开，所以引擎改成惰性加载：
     * 调用方先问这一句，false 就整体降级（插件页给提示），不再往下构造。
     * 桌面侧的动态库是随包分发的，恒为 true。
     */
    fun isAvailable(): Boolean

    fun create(): Long
    fun destroy(handle: Long)
    fun evaluate(handle: Long, code: String, filename: String): String

    /** [namesJson] / [sourcesJson] 都是 JSON 字符串数组文本，等长等序。 */
    fun setModules(handle: Long, namesJson: String, sourcesJson: String)

    fun evaluateModule(handle: Long, moduleName: String): String
    fun callFunction(
        handle: Long,
        moduleName: String,
        functionName: String,
        argsJson: String,
        callKey: String,
    ): String

    /** [namesJson] 是 JSON 字符串数组文本。 */
    fun registerHostFunctions(
        handle: Long,
        namesJson: String,
        dispatcher: HostFunctionDispatcher,
    )

    fun resolvePromise(handle: Long, token: String, resultJson: String, error: String?): Boolean
    fun drainJobs(handle: Long): Int
    fun dropCall(handle: Long, callKey: String): Boolean
    fun requestInterrupt(handle: Long)
    fun gc(handle: Long)
}

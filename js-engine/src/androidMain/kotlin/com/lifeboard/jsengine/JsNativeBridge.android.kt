package com.lifeboard.jsengine

/**
 * Android 侧：[JsNativeBridge] 的 JNI 实现。
 *
 * 函数签名必须与 `src/main/cpp/jni_bridge.cpp` 里的
 * `Java_com_lifeboard_jsengine_JsNativeBridge_*` 导出名一一对应——
 * JNI 的符号名按包名+类名+方法名拼出来，所以本类改名即意味着收不到回调，
 * 必须同步改 C++ 侧。
 */
internal actual object JsNativeBridge {

    /**
     * 加载结果缓存。
     *
     * 放在 `by lazy` 里而不是 `init` 块：`init` 会在对象首次被访问时就抛
     * `UnsatisfiedLinkError`，而首次访问发生在 `MainActivity.onCreate`
     * 构造插件宿主时——插件的一颗 `.so` 缺失会连累整个 App 打不开。
     *
     * `lazy` 默认线程安全，多次访问只真正加载一次；失败结果也被缓存，
     * 不会每次调用都重试一遍 dlopen。
     */
    private val loadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("lifeboard-js") }
    }

    actual fun isAvailable(): Boolean = loadResult.isSuccess

    /**
     * 断言库已加载。
     *
     * 保留抛异常而不是静默返回 0：走到这里说明调用方跳过了
     * [isAvailable] 的检查，那是编码错误，应该暴露而不是掩盖。
     * 包装后的消息带上原始失败原因，便于在日志里区分「库没打进包」
     * 与「符号不匹配」。
     */
    private fun ensureLoaded() {
        loadResult.getOrElse { cause ->
            throw UnsatisfiedLinkError(
                "liblifeboard-js.so 加载失败，本机 ABI 可能不在打包范围内：" +
                    "${cause.message}",
            )
        }
    }

    actual fun create(): Long {
        ensureLoaded()
        return nativeCreate()
    }

    actual fun destroy(handle: Long) = nativeDestroy(handle)

    actual fun evaluate(handle: Long, code: String, filename: String): String =
        nativeEvaluate(handle, code, filename)

    actual fun setModules(handle: Long, namesJson: String, sourcesJson: String) {
        // JNI 侧接收真正的 String[]，这里把 JSON 文本还原成数组。
        // 之所以统一成 JSON 文本：commonMain 的 [JsRuntime] 只有一份实现，
        // 它不能依赖 Array<String> 与 JSON 的差异，桥接层负责各自解析。
        nativeSetModules(handle, JsonArrays.decode(namesJson).toTypedArray(),
            JsonArrays.decode(sourcesJson).toTypedArray())
    }

    actual fun evaluateModule(handle: Long, moduleName: String): String =
        nativeEvaluateModule(handle, moduleName)

    actual fun callFunction(
        handle: Long,
        moduleName: String,
        functionName: String,
        argsJson: String,
        callKey: String,
    ): String = nativeCallFunction(handle, moduleName, functionName, argsJson, callKey)

    actual fun registerHostFunctions(
        handle: Long,
        namesJson: String,
        dispatcher: HostFunctionDispatcher,
    ) {
        nativeRegisterHostFunctions(handle, JsonArrays.decode(namesJson).toTypedArray(), dispatcher)
    }

    actual fun resolvePromise(
        handle: Long,
        token: String,
        resultJson: String,
        error: String?,
    ): Boolean = nativeResolvePromise(handle, token, resultJson, error)

    actual fun drainJobs(handle: Long): Int = nativeDrainJobs(handle)

    actual fun dropCall(handle: Long, callKey: String): Boolean = nativeDropCall(handle, callKey)

    actual fun requestInterrupt(handle: Long) = nativeRequestInterrupt(handle)

    actual fun gc(handle: Long) = nativeGc(handle)

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEvaluate(handle: Long, code: String, filename: String): String
    private external fun nativeSetModules(handle: Long, names: Array<String>, sources: Array<String>)
    private external fun nativeEvaluateModule(handle: Long, moduleName: String): String
    private external fun nativeCallFunction(
        handle: Long,
        moduleName: String,
        functionName: String,
        argsJson: String,
        callKey: String,
    ): String
    private external fun nativeRegisterHostFunctions(
        handle: Long,
        names: Array<String>,
        dispatcher: HostFunctionDispatcher,
    ): Unit
    private external fun nativeResolvePromise(
        handle: Long,
        token: String,
        resultJson: String,
        error: String?,
    ): Boolean
    private external fun nativeDrainJobs(handle: Long): Int
    private external fun nativeDropCall(handle: Long, callKey: String): Boolean
    private external fun nativeRequestInterrupt(handle: Long)
    private external fun nativeGc(handle: Long)
}

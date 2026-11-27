package com.lifeboard.jsengine

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * 桌面（Windows x86_64）侧：[JsNativeBridge] 的 JNA 实现。
 *
 * 与 Android 的 JNI 实现共享**完全一致**的对外行为，差别只有绑定方式：
 * JNI 靠固定的 `Java_<包>_<类>_<方法>` 符号名，JNA 靠 [Lib] 上的 `lb_*` 符号名。
 *
 * 动态库从 `resources/win32-x86-64/lifeboard-js.dll` 加载（随 jar 一起打包），
 * 由 `tools/build-quickjs-desktop.ps1` 编译产出。
 */
internal actual object JsNativeBridge {

    /**
     * 与 desktop_bridge.cpp 里的 `LbHostCallback` 对应。
     *
     * 约束（JNA 回调的硬要求，逐条踩过坑）：
     *   - [userData] 必须**可空** `Pointer?`：注册时传 `null`，若声明为非空，
     *     Kotlin 的空断言会在回调内抛 NPE，而 JNA 会吞异常并返回默认值 0（NULL），
     *     native 侧只看到「回调返回 null」，真实原因完全不可见。
     *   - 字符串参数用 [Pointer] 而不是 `String`：`Library.OPTION_STRING_ENCODING`
     *     只作用于 Library 方法的**参数/返回值**，**不覆盖 Callback 的参数**。
     *     用 `String` 时 JNA 按系统代码页（本机 936/GBK）解码，中文直接变乱码。
     *     声明为 Pointer 后由本文件自己按 UTF-8 读，编码与 native 侧对齐。
     *   - 返回值用 `Long`（地址数值）而不是 `Pointer`：语义明确，
     *     不依赖 JNA 对回调返回值的内部映射规则。
     */
    private interface NativeHostCallback : Callback {
        fun invoke(userData: Pointer?, name: Pointer, argsJson: Pointer): Long
    }

    private interface Lib : Library {
        fun lb_create(): Long
        fun lb_destroy(handle: Long)
        fun lb_evaluate(handle: Long, code: String, filename: String): Pointer
        fun lb_set_modules(handle: Long, namesJson: String, sourcesJson: String)
        fun lb_evaluate_module(handle: Long, moduleName: String): Pointer
        fun lb_call_function(
            handle: Long,
            moduleName: String,
            functionName: String,
            argsJson: String,
            callKey: String,
        ): Pointer
        fun lb_register_host_functions(
            handle: Long,
            namesJson: String,
            callback: NativeHostCallback,
            userData: Pointer?,
        )
        fun lb_resolve_promise(
            handle: Long,
            token: String,
            resultJson: String,
            errorText: String?,
        ): Int
        fun lb_drain_jobs(handle: Long): Int
        fun lb_drop_call(handle: Long, callKey: String): Int
        fun lb_request_interrupt(handle: Long)
        fun lb_clear_interrupt(handle: Long)
        fun lb_gc(handle: Long)
        fun lb_free_string(text: Pointer)
    }

    /**
     * 加载动态库，并强制 UTF-8 字符串编解码。
     *
     * JNA 默认用**系统 ANSI 代码页**在 Java String 与 `char*` 之间转换。
     * 本机代码页是 936（GBK），中文参数会变成非 UTF-8 字节，
     * 到 QuickJS 侧就是 "Bad UTF-8 sequence in JSON"。
     * 因此必须显式传 `Library.OPTION_STRING_ENCODING = "UTF-8"`，
     * 与 native 侧（quickjs 全程按 UTF-8 处理字符串）对齐。
     */
    private val lib: Lib by lazy {
        Native.load(
            "lifeboard-js",
            Lib::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
        )
    }

    /**
     * 桌面侧恒为 true。
     *
     * dll 由 `tools/build-quickjs-desktop.ps1` 产出并随 jar 分发，不存在
     * Android 那种「只编了部分 ABI」的问题。真加载不出来属于构建事故，
     * 让 [lib] 在首次使用时照常抛错即可，没必要在这里吞掉。
     */
    actual fun isAvailable(): Boolean = true

    /**
     * 把 native 返回的 `char*` 取成 Kotlin 字符串并释放。
     *
     * native 侧用 malloc 分配，必须回传给 `lb_free_string`，
     * 否则每读一次结果就泄漏一块内存（模块导出名、每次函数调用的 JSON 都走这里）。
     */
    private fun Pointer.takeCString(): String {
        if (this == Pointer.NULL) return ""
        return try {
            getString(0, "UTF-8")
        } finally {
            lib.lb_free_string(this)
        }
    }

    /**
     * 宿主函数表：**必须**持有强引用。
     *
     * JNA 只按 pointer 回调，不持有 Callback 的 Java 引用；一旦本地变量被 GC，
     * native 侧再回调就是野指针（表现为进程直接崩，没有异常栈）。
     * 因此把分配出去的 callback 钉在这个 map 里，runtime 销毁时整批释放。
     */
    private val hostCallbacks = java.util.concurrent.ConcurrentHashMap<Long, NativeHostCallback>()

    /**
     * 回调返回值的持有表：**按实例隔离**，销毁只清自己那一份。
     *
     * native 侧读走字符串后不会 free（这是 JVM 的 `Memory`，交给 C 的 free 会
     * 破坏堆）；而若不持强引用，`Memory` 可能在 native 读完前被 GC 回收。
     *
     * 早先这里是**全局一个 ArrayList**，销毁任意一个运行时就清空整张表——
     * 导致「关掉插件 A 把插件 B 尚在使用的缓冲也放了」的跨实例误伤。
     * 现在按 handle 分桶，并只保留最近几次：native 在回调返回后立刻复制，
     * 留多了纯属浪费内存。
     */
    private val returnedBuffers =
        java.util.concurrent.ConcurrentHashMap<Long, java.util.ArrayDeque<com.sun.jna.Memory>>()

    /** 每个实例最多保留的回调缓冲个数 */
    private const val MAX_RETAINED_BUFFERS = 2

    /** 登记一块回调返回值缓冲，只对当前实例生效 */
    private fun retainBuffer(handle: Long, mem: com.sun.jna.Memory) {
        val queue = returnedBuffers.computeIfAbsent(handle) { java.util.ArrayDeque() }
        synchronized(queue) {
            queue.addLast(mem)
            while (queue.size > MAX_RETAINED_BUFFERS) queue.removeFirst()
        }
    }

    actual fun create(): Long = lib.lb_create()

    actual fun destroy(handle: Long) {
        hostCallbacks.remove(handle)
        // 只释放本实例的缓冲，其它插件的运行时不受影响
        returnedBuffers.remove(handle)
        lib.lb_destroy(handle)
    }

    actual fun evaluate(handle: Long, code: String, filename: String): String =
        lib.lb_evaluate(handle, code, filename).takeCString()

    actual fun setModules(handle: Long, namesJson: String, sourcesJson: String) {
        lib.lb_set_modules(handle, namesJson, sourcesJson)
    }

    actual fun evaluateModule(handle: Long, moduleName: String): String =
        lib.lb_evaluate_module(handle, moduleName).takeCString()

    actual fun callFunction(
        handle: Long,
        moduleName: String,
        functionName: String,
        argsJson: String,
        callKey: String,
    ): String =
        lib.lb_call_function(handle, moduleName, functionName, argsJson, callKey).takeCString()

    actual fun registerHostFunctions(
        handle: Long,
        namesJson: String,
        dispatcher: HostFunctionDispatcher,
    ) {
        // 复用已有 callback，避免重复注册时旧引用被回收
        val callback = hostCallbacks.getOrPut(handle) {
            object : NativeHostCallback {
                override fun invoke(userData: Pointer?, name: Pointer, argsJson: Pointer): Long {
                    // JNA 在回调里抛出的 Java 异常**不会**传回 native，
                    // 只会让 JNA 返回该类型的默认值（Long → 0，即 NULL），
                    // native 侧于是看到「回调返回 null」。这里必须自己兜住并打印，
                    // 否则真实原因会被彻底吞掉。
                    return try {
                        // 显式按 UTF-8 读取，绕开 JNA 对 Callback 参数用系统代码页解码的行为
                        val nameText = name.getString(0, "UTF-8")
                        val argsText = argsJson.getString(0, "UTF-8")
                        val result = dispatcher.invoke(nameText, argsText)
                        val bytes = result.toByteArray(Charsets.UTF_8)
                        val mem = com.sun.jna.Memory(bytes.size + 1L)
                        mem.write(0, bytes, 0, bytes.size)
                        mem.setByte(bytes.size.toLong(), 0)
                        // 这块内存是 JVM 托管的 Memory，native 侧**不得** free。
                        // 为让它活到 native 读完，登记到**本实例**的持有表里
                        retainBuffer(handle, mem)
                        Pointer.nativeValue(mem)
                    } catch (t: Throwable) {
                        val nameText = runCatching { name.getString(0, "UTF-8") }.getOrDefault("<未知>")
                        System.err.println("宿主函数 '$nameText' 执行失败：${t}")
                        t.printStackTrace()
                        0L
                    }
                }
            }
        }
        lib.lb_register_host_functions(handle, namesJson, callback, null)
    }

    actual fun resolvePromise(
        handle: Long,
        token: String,
        resultJson: String,
        error: String?,
    ): Boolean = lib.lb_resolve_promise(handle, token, resultJson, error) != 0

    actual fun drainJobs(handle: Long): Int = lib.lb_drain_jobs(handle)

    actual fun dropCall(handle: Long, callKey: String): Boolean =
        lib.lb_drop_call(handle, callKey) != 0

    actual fun requestInterrupt(handle: Long) = lib.lb_request_interrupt(handle)

    actual fun gc(handle: Long) = lib.lb_gc(handle)
}

package com.lifeboard.shared.log

import com.lifeboard.shared.repository.LogStore
import com.lifeboard.shared.repository.model.AppLogRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 应用内日志记录器。
 *
 * ## 三件事
 * 1. **平台控制台照常输出**：Android 上是 logcat，桌面上是标准错误流
 * 2. **按顺序异步落库**：调用方只做一次 `channel.trySend`，不会因为写库卡住采集或界面
 * 3. **自动带上调用点**：`info("...")` 就够了，不需要每处手写 tag
 *
 * ## 为什么放在 commonMain
 * 插件运行时要跨端共用，而插件代码里到处是日志调用。如果 `AppLogger` 留在 Android 侧，
 * 共享层的插件代码就没法写日志，只能再套一层接口——那等于为了日志多一层抽象。
 * 平台差异其实只有「往哪输出」一处，用 [LogSink] 抽掉即可。
 *
 * ## 失败了怎么办
 * 写库失败只打平台控制台，绝不回写日志——否则「记录日志失败」本身会再产生一条日志，
 * 一路递归下去直接把栈打爆。
 */
object AppLogger {

    private const val TAG_TO_CONSOLE = "LifeBoard"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 容量有界：写库跟不上时宁可丢日志，也不能无限占内存拖垮宿主。
     * 日志是诊断手段，不该成为新的故障源。
     */
    private val queue = Channel<AppLogRecord>(capacity = 512)

    @Volatile
    private var started = false

    /**
     * 控制台输出实现。
     *
     * 默认值在单测与未初始化的场景下兜底：测试里没有 Android 的 `Log`，
     * 也没有必要往标准输出刷一堆噪声，所以默认什么都不做；
     * 真正的输出实现由各平台在启动时通过 [init] 注入。
     */
    @Volatile
    private var sink: LogSink = LogSink.None

    /**
     * 启动消费协程。
     *
     * 必须在应用启动时调用一次：建库本身也要读磁盘，
     * 放到第一次写日志时再做会让「首条日志」承担建库开销。
     *
     * @param sink 平台控制台输出；不传则只落库不输出控制台
     * @param storeProvider 取日志存储；返回 null 时本条日志只输出控制台
     */
    fun init(sink: LogSink, storeProvider: () -> LogStore?) {
        this.sink = sink
        this.storeProvider = storeProvider
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch { consume() }
        }
    }

    /** 日志存储提供者；返回 null 时只输出控制台，不落库 */
    @Volatile
    private var storeProvider: () -> LogStore? = { null }

    private suspend fun consume() {
        for (entry in queue) {
            val store = storeProvider() ?: continue
            runCatching {
                store.insertAppLog(entry)
                // 每次都裁剪：等用户打开日志页再清，库早就涨上去了
                store.trimAppLogs()
            }.onFailure {
                sink.write("WARN", TAG_TO_CONSOLE, "写日志失败：${entry.tag} ${entry.message} $it")
            }
        }
    }

    fun debug(message: String) = record("DEBUG", message)

    fun info(message: String) = record("INFO", message)

    fun warn(message: String) = record("WARN", message)

    fun error(message: String, tr: Throwable? = null) =
        record("ERROR", if (tr == null) message else "$message\n${tr.stackTraceToString()}")

    /**
     * 写一条日志。
     *
     * @param level DEBUG / INFO / WARN / ERROR
     */
    private fun record(level: String, message: String) {
        val caller = callerLocation()
        // 控制台与库内使用同一个格式，看到其中一份就能对上另一份
        sink.write(level, caller.first.ifBlank { TAG_TO_CONSOLE }, "[${caller.first}] $message")

        if (!started) return
        queue.trySend(
            AppLogRecord(
                level = level,
                tag = caller.first,
                location = caller.second,
                message = message,
                time = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 找到真正的调用方。
     *
     * 跳过本对象自己的栈帧（以及协程包装），否则每条日志的来源都会变成 `AppLogger`，
     * 完全失去定位价值。取不到时退化成空标签，不抛异常。
     */
    private fun callerLocation(): Pair<String, String> {
        val selfName = AppLogger::class.simpleName.orEmpty()
        val frame = Throwable().stackTrace.firstOrNull {
            !it.className.endsWith(".$selfName") &&
                !it.className.startsWith("kotlinx.coroutines")
        } ?: return "" to ""
        val file = frame.fileName ?: ""
        val tag = frame.className.substringAfterLast('.')
        return tag to if (file.isBlank()) "" else "$file:${frame.lineNumber}"
    }
}

/**
 * 平台控制台输出。
 *
 * 只做「把一行文本送到平台日志通道」这一件事，不带级别过滤、不带格式化——
 * 那些属于调用方策略，放在这里会让两个平台的实现各自漂移。
 */
fun interface LogSink {

    fun write(level: String, tag: String, message: String)

    companion object {
        /** 什么都不做。单测与未初始化场景的默认值 */
        val None: LogSink = LogSink { _, _, _ -> }
    }
}

package com.lifeboard.server.log

import android.content.Context
import android.util.Log
import com.lifeboard.server.db.AppLogDao
import com.lifeboard.server.db.AppLogEntity
import com.lifeboard.server.db.LifeBoardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 应用内日志记录器。
 *
 * 设计参考 `external/AutoAccounting` 的 `Logger`，但去掉了它的 HTTP 上报环节：
 * 那个项目的日志要在「被注入的目标 App 进程」里产生，必须跨进程汇总；
 * LifeBoard 的插件与宿主同进程，直接写同一个 Room 库就够了，
 * 多一层本地 HTTP 只会多一处失败点。
 *
 * ## 三件事
 * 1. **logcat 仍然照常输出**：电脑连着时 `adb logcat` 用法完全不变
 * 2. **按顺序异步落库**：调用方只做一次 `channel.trySend`，不会因为写库卡住采集或界面
 * 3. **自动带上调用点**：`info("...")` 就够了，不需要每处手写 tag
 *
 * ## 失败了怎么办
 * 写库失败只打 logcat，绝不回写日志——否则「记录日志失败」本身会再产生一条日志，
 * 一路递归下去直接把栈打爆。
 */
object AppLogger {

    private const val TAG_TO_LOGCAT = "LifeBoard"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 容量有界：写库跟不上时宁可丢日志，也不能无限占内存拖垮宿主。
     * 日志是诊断手段，不该成为新的故障源。
     */
    private val queue = Channel<AppLogEntity>(capacity = 512)

    @Volatile
    private var started = false

    /** 数据库上下文；为 null 时只输出 logcat，不落库 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 启动消费协程。
     *
     * 必须在 `Application.onCreate` 里调用一次：Room 初始化本身也要读磁盘，
     * 放到第一次写日志时再做会让「首条日志」承担建库开销。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch { consume() }
        }
    }

    private suspend fun consume() {
        for (entry in queue) {
            val ctx = appContext ?: continue
            runCatching {
                val dao: AppLogDao = LifeBoardDatabase.get(ctx).appLogDao()
                dao.insert(entry)
                // 每次都裁剪：等用户打开日志页再清，库早就涨上去了
                dao.trim()
            }.onFailure {
                Log.w(TAG_TO_LOGCAT, "写日志失败：${entry.tag} ${entry.message}", it)
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
        // logcat 与库内使用同一个格式，看到其中一份就能对上另一份
        val line = "[${caller.first}] $message"
        when (level) {
            "DEBUG" -> Log.d(TAG_TO_LOGCAT, line)
            "WARN" -> Log.w(TAG_TO_LOGCAT, line)
            "ERROR" -> Log.e(TAG_TO_LOGCAT, line)
            else -> Log.i(TAG_TO_LOGCAT, line)
        }

        if (!started) return
        queue.trySend(
            AppLogEntity(
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
        val frame = Throwable().stackTrace.firstOrNull {
            it.className != AppLogger::class.java.name &&
                !it.className.startsWith("kotlinx.coroutines")
        } ?: return "" to ""
        val file = frame.fileName ?: ""
        val tag = frame.className.substringAfterLast('.')
        return tag to if (file.isBlank()) "" else "$file:${frame.lineNumber}"
    }
}

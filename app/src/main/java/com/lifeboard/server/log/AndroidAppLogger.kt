package com.lifeboard.server.log

import android.content.Context
import android.util.Log
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.log.LogSink

/**
 * Android 侧日志装配。
 *
 * 日志逻辑本身已在 `shared` 的 [AppLogger]（插件运行时跨端共用，日志调用遍地都是，
 * 不可能为了它再套一层接口）。这里只负责两件 Android 专属的事：
 * 1. 把记录送到 logcat
 * 2. 告诉共享实现怎么拿到 `LogStore`
 */
object AndroidAppLogger {

    private const val TAG = "LifeBoard"

    /** Android 的 logcat 输出实现 */
    private val logcatSink = LogSink { level, tag, message ->
        when (level) {
            "DEBUG" -> Log.d(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    /**
     * 在 `Application.onCreate` 里调用一次。
     *
     * `DatabaseProviders.logStore` 内部已缓存，这里每次现取即可，
     * 不在装配阶段提前建库——日志初始化不该拖慢冷启动。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        AppLogger.init(
            sink = logcatSink,
            storeProvider = { runCatching { DatabaseProviders.logStore(app) }.getOrNull() },
        )
    }
}

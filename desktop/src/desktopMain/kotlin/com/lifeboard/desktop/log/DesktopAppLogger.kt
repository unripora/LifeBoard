package com.lifeboard.desktop.log

import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.log.LogSink
import com.lifeboard.shared.repository.LogStore

/**
 * 桌面端日志装配。
 *
 * 输出目标选标准错误流而不是标准输出：桌面端将来要出打包版，
 * 标准输出可能被 launcher 接管，标准错误流更稳；而且日志属于诊断信息，
 * 与正常的控制台消息分开也更合理。
 *
 * 落库与 Android 共用同一套 `LogStore`（SQLDelight 生成，两端同一份 SQL）。
 */
object DesktopAppLogger {

    private val stderrSink = LogSink { level, tag, message ->
        System.err.println("[$level] $tag: $message")
    }

    /**
     * @param storeProvider 取日志存储；桌面端依赖注入比较晚，
     *        所以用 lambda 延迟到真正写日志时才解析
     */
    fun init(storeProvider: () -> LogStore?) {
        AppLogger.init(sink = stderrSink, storeProvider = storeProvider)
    }
}

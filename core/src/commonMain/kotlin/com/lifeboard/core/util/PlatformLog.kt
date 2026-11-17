package com.lifeboard.core.util

/**
 * 极简平台日志门面。
 *
 * `core` 是 KMP 模块（Android + 桌面共用），不能在 commonMain 直接使用
 * `android.util.Log`。这里只保留最必要的能力：一个带 throwable 的错误日志。
 * 需要结构化日志的地方仍走 `:server` 的 AppLogger，本类只服务于 `core` 内部
 * 极少数「兜底失败也不能抛异常」的位置。
 */
expect object PlatformLog {
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

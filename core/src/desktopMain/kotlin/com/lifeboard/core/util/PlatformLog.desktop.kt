package com.lifeboard.core.util

actual object PlatformLog {
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        // 桌面端在 :shared 接入正式文件日志前，先落到 stderr，避免静默吞掉异常
        System.err.println("[$tag] $message")
        throwable?.printStackTrace()
    }
}

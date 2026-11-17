package com.lifeboard.core.util

import android.util.Log

actual object PlatformLog {
    actual fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

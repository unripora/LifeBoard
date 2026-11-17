package com.lifeboard.shared.ui.common

/**
 * Android 实现：`android.util.Base64` 的 `DEFAULT` 模式。
 *
 * 用 `DEFAULT` 而不是 `NO_WRAP`：数据 URI 里的载荷通常由插件按行折过，
 * `NO_WRAP` 遇到换行会直接抛异常，而 `DEFAULT` 会跳过它们。
 */
actual fun decodeBase64(text: String): ByteArray? =
    runCatching { android.util.Base64.decode(text, android.util.Base64.DEFAULT) }.getOrNull()

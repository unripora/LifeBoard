package com.lifeboard.shared.ui.common

/**
 * 桌面实现：`java.util.Base64` 的 MIME 解码器。
 *
 * 用 MIME 而不是基本解码器：插件折行的载荷在基本解码器下会抛异常，
 * MIME 解码器按规范忽略换行与空白。
 */
actual fun decodeBase64(text: String): ByteArray? =
    runCatching { java.util.Base64.getMimeDecoder().decode(text) }.getOrNull()

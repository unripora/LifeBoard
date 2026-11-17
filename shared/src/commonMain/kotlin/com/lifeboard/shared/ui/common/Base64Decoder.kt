package com.lifeboard.shared.ui.common

/**
 * 把 base64 文本解码成字节。
 *
 * 插件给的图片是 `data:image/png;base64,...` 形式的数据 URI，
 * 解码要用到 `java.util.Base64`（Android 与桌面都有），
 * 但那是 JVM API，commonMain 里直接 import 编译不过，所以收成 expect/actual。
 *
 * 只在 [com.lifeboard.shared.plugin] 的验证码/图表这条链路上用得到，
 * 因此不做 URL-safe 变体，也不做流式解码——载荷本身就是一次性字符串。
 *
 * @return 解码后的字节；载荷非法时返回 null，由调用方决定是否提示
 */
expect fun decodeBase64(text: String): ByteArray?

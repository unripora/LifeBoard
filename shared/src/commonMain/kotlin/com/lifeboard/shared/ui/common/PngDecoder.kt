package com.lifeboard.shared.ui.common

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 把 PNG 字节解码成可绘制的位图（M3-3.2d）。
 *
 * ## 为什么需要 expect/actual
 *
 * `AppIconProvider` 返回的是 PNG 字节而不是 `ImageBitmap`，
 * 因为 `:server` 依赖 `:shared` 时 Compose 是 `implementation` 不传递——
 * 接口签名里出现 Compose 类型就编译不过。
 * 字节在界面层解码，而解码的 API 两端不同：
 *
 * - Android：`BitmapFactory.decodeByteArray` + `asImageBitmap`
 * - Desktop：`org.jetbrains.skia.Image.makeFromEncoded`
 *
 * 解码失败返回 null（而不是抛异常）：图标画不出来只影响观感，
 * 界面退化成首字母占位即可，不该让关于页崩掉。
 */
expect fun decodePng(bytes: ByteArray): ImageBitmap?

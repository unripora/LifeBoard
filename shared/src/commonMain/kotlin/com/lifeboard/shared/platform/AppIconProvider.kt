package com.lifeboard.shared.platform

/**
 * 应用图标（M3-3.3b）。
 *
 * ## 为什么返回 `ByteArray` 而不是 `ImageBitmap`
 *
 * 第一版这里写的是 `androidx.compose.ui.graphics.ImageBitmap`——看起来更自然，
 * 拿到就能塞进 `Image(bitmap = ...)`。但它有个致命问题：
 *
 * `:server` 依赖 `:shared` 时，`shared` 的 Compose 依赖是 `implementation`，
 * **不会传递给 `:server`**。于是 `AndroidAppIconProvider`（要放 `:server`
 * 或 `:app`）一旦签名里出现 `ImageBitmap` 就编译不过，而 `:server` 又不该
 * 为了一个图标去加整个 Compose 依赖。
 *
 * 换 PNG 字节之后，接口只依赖 `kotlin.ByteArray`：
 *
 * - `:server` 侧实现只要把 `Drawable` 编码成 PNG，不需要 Compose
 * - 界面层（在 `:shared`，本来就有 Compose）用
 *   `org.jetbrains.skia.Image.makeFromEncoded(...)` 或平台解码器转成 `ImageBitmap`
 *
 * 代价是多一次编码/解码。图标是 96×96 的小图，一次几十微秒，
 * 而「关于」页整个生命周期只取一次，可以忽略。
 *
 * ## 为什么是 PNG 而不是原始像素
 *
 * 原始像素要额外约定宽高、通道顺序、行序。PNG 自带这些，且跨端解码器都有。
 */
interface AppIconProvider {

    /**
     * 取应用自身图标的 PNG 字节。
     *
     * @return PNG 数据；拿不到时返回 null，界面应退化成首字母占位
     */
    suspend fun appIconPng(): ByteArray?
}

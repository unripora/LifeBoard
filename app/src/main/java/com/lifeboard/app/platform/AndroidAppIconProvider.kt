package com.lifeboard.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import com.lifeboard.shared.platform.AppIconProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android 端的应用自身图标（M3-3.3b）。
 *
 * ## 为什么在 `:app` 而不是 `:server`
 *
 * 接口 [AppIconProvider] 返回 PNG 字节，本身不依赖 Compose。但**取图标**
 * 这件事需要 `android.graphics` + 把 `Drawable` 画进 `Bitmap`，那是界面层的活；
 * `:server` 是后台服务模块，不该为一张图标引入绘图逻辑。
 *
 * 放在 `:app` 还有个实在的好处：`forActivity` 装配在 `MainActivity` 里，
 * 而那个类本来就在 `:app`，不用再绕一层依赖。
 *
 * ## 为什么用 `getApplicationIcon` 而不是读资源目录
 *
 * 「关于」页要显示的是**用户实际看到的那个图标**——它可能被主题、启动器
 * 或厂商换了皮。资源目录里那张原始图未必是它。
 *
 * ## 为什么编码成 PNG 交给上层解码
 *
 * 见 [AppIconProvider] 的注释：`:server` 拿不到 Compose 的 `ImageBitmap`。
 * 这里编码、上层解码，多一次往返，但保住了「`:server` 不依赖 Compose」这条线。
 */
class AndroidAppIconProvider(
    private val context: android.content.Context,
) : AppIconProvider {

    override suspend fun appIconPng(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            // 固定 96px：关于页的抬头是 56dp，按 2x 屏留够清晰度即可。
            // 直接用原始尺寸的话，有些厂商图标是 512px，编码出来上百 KB，
            // 而我们只需要画 56dp
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)

            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                // 及时回收：Bitmap 占的是 native 内存，不等 GC
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }
}

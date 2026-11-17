package com.lifeboard.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lifeboard.shared.platform.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 侧的「已安装应用」查询（M4-4.6）。
 *
 * 契约见 [AppCatalog]。这里只负责把 `PackageManager` 的结果翻译成通用条目：
 * 标识符用包名、名称用 `getApplicationLabel`、图标按需解码。
 *
 * ## 为什么要分两次取（列表不带给图标）
 *
 * `getApplicationIcon` 对每个包都要解析资源，几百个应用一次性解码会让列表打开时明显卡顿。
 * 所以 [list] 只返回名称与包名，图标等滚动到哪再通过 [icon] 解码，
 * 并用 [iconCache] 保证同一包名只解一次。
 */
class AndroidAppCatalog(context: Context) : AppCatalog {

    private val appContext = context.applicationContext

    override suspend fun list(): List<AppCatalog.AppEntry> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { info ->
                    AppCatalog.AppEntry(
                        key = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        detail = info.packageName,
                    )
                }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }

    override suspend fun icon(key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(iconCache) {
            if (iconCache.containsKey(key)) return@withContext iconCache[key]
            val bitmap = runCatching {
                appContext.packageManager.getApplicationIcon(key).toImageBitmap(ICON_SIZE)
            }.getOrNull()
            iconCache[key] = bitmap
            bitmap
        }
    }

    override suspend fun label(key: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString()
        }.getOrDefault(key)
    }

    private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bmp.asImageBitmap()
    }

    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    private companion object {
        /** 列表里图标最大显示 44dp，按 96px 解码足够清晰且省内存 */
        const val ICON_SIZE = 96
    }
}

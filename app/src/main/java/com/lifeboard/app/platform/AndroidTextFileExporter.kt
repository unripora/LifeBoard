package com.lifeboard.app.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lifeboard.shared.platform.TextFileExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [TextFileExporter] 的 Android 实现：写 `cacheDir` → `FileProvider` → 系统分享面板。
 *
 * ## 为什么用分享面板而不是「保存到文件」
 *
 * Android 的 `ACTION_CREATE_DOCUMENT` 也能让用户选保存位置，但用户拿到日志后
 * 十有八九是要**发出去**（发给开发者、发到群里），而不是存在手机上自己看。
 * 分享面板一屏里既有微信也有「保存到文件」，一步到位；只给保存对话框
 * 等于让人先存到 `Download`，再自己去文件管理器里找出来转发。
 *
 * ## 必须用 FileProvider
 *
 * `file://` URI 从 Android 7 起会被接收方拒收（`FileUriExposedException`）。
 *
 * ## 放在 `:app` 而不是 `:server`
 *
 * 它需要一个能弹系统面板的 `Context` 与 `FLAG_ACTIVITY_NEW_TASK` 的 Activity 栈，
 * 而后台服务模块不该有这种界面级依赖。与 `AndroidAppIconProvider` 同理。
 */
class AndroidTextFileExporter(
    private val context: Context,
) : TextFileExporter {

    override val available: Boolean = true

    override suspend fun export(
        fileName: String,
        mimeType: String,
        content: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // 每次导出前清掉上一次的：日志文件带日期名，不清会越攒越多，
            // 而 cacheDir 满了系统会在用户不知情时删掉别的缓存
            val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }

            val file = File(dir, fileName)
            file.writeText(content)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "导出行程日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private companion object {
        /** 与 `res/xml/file_paths.xml` 里声明的子目录同名 */
        const val EXPORT_DIR = "logs"
    }
}

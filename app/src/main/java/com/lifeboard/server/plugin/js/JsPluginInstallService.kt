package com.lifeboard.server.plugin.js

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lifeboard.server.plugin.PluginTrustStore
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.js.JsPluginInstallService as SharedInstallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android 侧的 `.jsplugin` 安装入口。
 *
 * 校验、解包、落盘的逻辑全部在共享层的 [SharedInstallService] 里
 * （桌面端同一份），本对象只负责两件 Android 特有的事：
 *
 * 1. 把 SAF 的 `content://` Uri 复制成真实文件——SAF 只给临时读权限，
 *    进程重启即失效，不能当作插件路径长期使用；
 * 2. 从 `ContentResolver` 读显示名，供界面展示「你选的是哪个文件」。
 */
object JsPluginInstallService {

    /** 暂存文件的落脚点，与正式插件目录分开 */
    private fun stagingDir(context: Context): String =
        File(context.cacheDir, "file_picker").apply { mkdirs() }.absolutePath

    private fun service(context: Context): SharedInstallService =
        SharedInstallService(
            fileSystem = fileSystem(context),
            stagingDirPath = stagingDir(context),
        )

    private fun fileSystem(context: Context) =
        com.lifeboard.shared.plugin.fs.AndroidPluginFileSystem(context.applicationContext.filesDir)

    /**
     * 把用户选中的文件装成 JS 插件包。
     *
     * @param uri 系统文件选择器返回的 Uri。扩展名不可信，
     *   是否真的是 JS 插件靠解包后读 manifest 判定
     */
    suspend fun installFromUri(context: Context, uri: Uri): SharedInstallService.Outcome =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val bytes = readBytes(appContext, uri)
                ?: return@withContext SharedInstallService.Outcome.Failed("无法读取所选文件")

            val staged = service(appContext).stage(bytes, displayName(appContext, uri) ?: "import")
                ?: return@withContext SharedInstallService.Outcome.Failed(
                    "无法写入暂存文件，请检查存储空间"
                )

            when (val outcome = service(appContext).install(staged)) {
                is SharedInstallService.Outcome.Installed -> outcome
                is SharedInstallService.Outcome.Failed -> {
                    // 校验失败时暂存文件不会被 install 消费掉，这里负责清理
                    runCatching { staged.delete() }
                    outcome
                }
                is SharedInstallService.Outcome.Conflict -> outcome
            }
        }

    /** 用户确认覆盖后调用。暂存文件由 [PluginManager] 按文件名找回 */
    suspend fun confirmOverwrite(context: Context, staged: File, targetName: String): Boolean =
        withContext(Dispatchers.IO) {
            service(context.applicationContext).confirmOverwrite(staged, targetName)
        }

    /** 放弃一次待确认的安装，清掉暂存文件 */
    fun discard(staged: File) {
        runCatching { staged.delete() }
    }

    /** 供 UI 显示「你选的是哪个文件」，读取 SAF 提供的显示名 */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrDefault(null)

    /**
     * 读一个已安装包的元数据，供冲突对比展示。
     *
     * 只读 manifest 与内容哈希，不创建运行时——安装阶段不该顺带执行脚本。
     */
    fun readPackageInfo(context: Context, archive: File): JsPluginPackageInfo? =
        service(context.applicationContext).readPackageInfo(archive)

    /** 把 SAF 的 Uri 整个读进内存。插件包有体积上限，不做流式 */
    private fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrElse { error ->
        AppLogger.warn("读取所选 JS 插件文件失败：${error.message}")
        null
    }.takeIf { it != null && it.isNotEmpty() }
}

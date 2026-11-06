package com.lifeboard.server.plugin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从用户选择的文件安装 `.ext` 插件。
 *
 * ## 为什么走系统文件选择器
 *
 * 早期设计假设用户会自己把 `.ext` 放进 `filesDir/plugins`。这不成立：
 * 那是应用私有目录，普通用户既看不到也没法往里丢文件。
 * 安装入口必须是系统的文档选择器（SAF），用户从下载目录、网盘、聊天记录里选就行。
 *
 * ## 为什么要先复制再校验
 *
 * 选中的 [Uri] 是临时的读权限，进程重启后就失效，**不能直接当作插件路径使用**。
 * 因此流程固定为「复制到私有目录的临时文件 → 校验 → 原子改名成正式包」：
 *
 * ```
 * file_picker/xxx.tmp          校验通过前的落脚点，始终不进 plugins/
 * plugins/foo.ext.part         正在写入（见 PluginInstaller.install）
 * plugins/foo.ext              正式插件包
 * ```
 *
 * 校验放在正式目录之外，是为了不让一个坏包以 `.ext` 结尾留在插件列表里，
 * 变成用户看不懂的「加载失败」条目。
 */
object PluginInstallService {

    private const val TAG = "LifeBoardPluginInstall"

    /** 选择结果：要么装好了，要么带着可展示的原因失败 */
    sealed class InstallOutcome {

        /**
         * 已装到插件目录，但**尚未信任**，因此还没被加载。
         *
         * 必须由用户看过签名指纹后确认信任（[PluginManager.trust] + [PluginManager.refresh]），
         * 安装动作本身不构成信任——这是插件与宿主同权限运行的前提条件。
         */
        data class Installed(
            val archiveName: String,
            val info: PluginArchiveInfo,
            val entryClass: String,
        ) : InstallOutcome()

        /** 装的包与已存在的同包名插件重复，需要用户确认是否覆盖 */
        data class Conflict(
            val existingArchiveName: String,
            val existing: PluginArchiveInfo,
            val incoming: PluginArchiveInfo,
            val stagedFile: File,
        ) : InstallOutcome()

        data class Failed(val reason: String) : InstallOutcome()
    }

    /**
     * 把用户选中的文件装成插件包。
     *
     * @param uri 系统文件选择器返回的 Uri，类型无所谓——扩展名不可信，
     *   真正判断是不是插件靠解析 APK
     */
    suspend fun installFromUri(context: Context, uri: Uri): InstallOutcome =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val staged = stageToTemp(appContext, uri)
                ?: return@withContext InstallOutcome.Failed("无法读取所选文件")

            try {
                validate(appContext, staged)
            } catch (e: Rejected) {
                staged.delete()
                return@withContext InstallOutcome.Failed(e.reason)
            }

            // validate 已确认能解析，这里的强断言是安全的
            val info = PluginInstaller.readArchiveInfo(appContext, staged)
                ?: run {
                    staged.delete()
                    return@withContext InstallOutcome.Failed("无法解析插件包")
                }
            val entryClass = PluginLoader.readEntryClass(staged)
                ?: run {
                    staged.delete()
                    return@withContext InstallOutcome.Failed(
                        "这不是 LifeBoard 插件：缺少 META-INF/lifeboard-plugin.properties 或其中没有 entryClass",
                    )
                }

            // 文件名用包名生成，不用用户选的那个：
            // SAF 给的显示名可能带空格、中文、奇怪后缀，甚至重名覆盖别的插件
            val targetName = "${info.packageName}.ext"

            // 同包名已存在：交给调用方问用户要不要覆盖
            val existing = PluginInstaller.listArchives(appContext)
                .firstOrNull { it.name == targetName }

            if (existing != null) {
                val existingInfo = PluginInstaller.readArchiveInfo(appContext, existing)
                if (existingInfo != null) {
                    return@withContext InstallOutcome.Conflict(
                        existingArchiveName = existing.name,
                        existing = existingInfo,
                        incoming = info,
                        stagedFile = staged,
                    )
                }
            }

            commit(appContext, staged, targetName).let { installed ->
                if (installed == null) {
                    InstallOutcome.Failed("写入插件目录失败，请检查存储空间")
                } else {
                    InstallOutcome.Installed(targetName, info, entryClass)
                }
            }
        }

    /** 用户确认覆盖后调用，把暂存文件落成正式包 */
    suspend fun confirmOverwrite(context: Context, staged: File, targetName: String): Boolean =
        withContext(Dispatchers.IO) {
            commit(context.applicationContext, staged, targetName) != null
        }

    /** 放弃一次待确认的安装，清掉暂存文件 */
    fun discard(staged: File) {
        runCatching { staged.delete() }
    }

    /**
     * 复制到私有临时目录。
     *
     * 不用 `Uri.path` 直接访问：SAF 返回的常常是 `content://` 形式的文档 Uri，
     * 背后是网盘或压缩包，根本没有真实路径。
     */
    private fun stageToTemp(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.cacheDir, "file_picker").also { if (!it.exists()) it.mkdirs() }
        // 每次换个新名字，避免上一次残留的半个文件被当成这次的输入
        val temp = File(dir, "import_${System.currentTimeMillis()}.tmp")

        val input = context.contentResolver.openInputStream(uri)
            ?: return@runCatching null
        input.use { stream ->
            temp.outputStream().use { out -> stream.copyTo(out) }
        }

        if (temp.length() == 0L) {
            temp.delete()
            return@runCatching null
        }
        temp
    }.getOrElse { error ->
        Log.w(TAG, "读取所选文件失败", error)
        null
    }

    /** 一次校验不通过，带可展示原因 */
    private class Rejected(val reason: String) : Exception(reason)

    /**
     * 判断这个文件能不能当插件用。
     *
     * 只做「不依赖加载」的检查：能解析出包信息、声明了插件特性、带签名。
     * 契约版本与接口实现要实例化之后才知道，放在加载阶段（[PluginLoader]）判定。
     */
    private fun validate(context: Context, staged: File) {
        // getPackageArchiveInfo 只认 .apk 之外的路径也行，但它对非 ZIP 文件返回 null
        val info = PluginInstaller.readArchiveInfo(context, staged)
            ?: throw Rejected("无法解析这个文件，它可能不是插件包（应为重命名为 .ext 的 APK）")

        if (!info.isSigned) {
            throw Rejected("插件包没有签名，无法建立信任，不能安装")
        }

        if (!declaresPluginFeature(context, staged)) {
            throw Rejected("包内没有声明 lifeboard.plugin 特性，不是给 LifeBoard 写的插件")
        }

        if (info.packageName.isBlank()) {
            throw Rejected("插件包没有包名")
        }
    }

    /** 读 manifest 里的 uses-feature，确认是给本应用的插件 */
    private fun declaresPluginFeature(context: Context, archive: File): Boolean = runCatching {
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                archive.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archive.absolutePath, flags)
        }
        // reqFeatures 读不到时按「没声明」处理：宁可拒绝，也不放进来路不明的包
        pi?.reqFeatures?.any { it.name == "lifeboard.plugin" } == true
    }.getOrDefault(false)

    /** 原子写入正式目录 */
    private fun commit(context: Context, staged: File, targetName: String): File? =
        PluginInstaller.install(staged, context, targetName).also {
            // 无论成功与否，暂存文件都不再需要
            // 失败时 install 已清掉 .part，这里只清源文件
            runCatching { staged.delete() }
        }

    /** 供 UI 显示「你选的是哪个文件」，读取 SAF 提供的显示名 */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrDefault(null)
}

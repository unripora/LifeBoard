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
 * plugins/foo.apk.part         正在写入（见 PluginInstaller.install）
 * plugins/foo.apk              正式插件包
 * ```
 *
 * 校验放在正式目录之外，是为了不让一个坏包以 `.apk` 结尾留在插件列表里，
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
            val targetName = "${info.packageName}${PluginInstaller.APK_SUFFIX}"

            // 同包名已存在：交给调用方问用户要不要覆盖
            val existing = PluginInstaller.listArchives(appContext)
                .firstOrNull { it.name == targetName }

            if (existing != null) {
                val existingInfo = PluginInstaller.readArchiveInfo(appContext, existing)
                if (existingInfo != null) {
                    // 确认覆盖之前先做版本与签名裁决：绕过这一步，一个换签名或降级的包
                    // 会先把旧包覆盖掉，再在加载阶段被拒，用户既丢了旧版本又用不了新版本
                    rejectIfNotUpgradable(appContext, info)?.let { reason ->
                        staged.delete()
                        return@withContext InstallOutcome.Failed(reason)
                    }
                    return@withContext InstallOutcome.Conflict(
                        existingArchiveName = existing.name,
                        existing = existingInfo,
                        incoming = info,
                        stagedFile = staged,
                    )
                }
            }

            // 首次安装不需要问覆盖，但已安装记录仍可能存在（用户先删了包文件没清记录）
            rejectIfNotUpgradable(appContext, info)?.let { reason ->
                staged.delete()
                return@withContext InstallOutcome.Failed(reason)
            }

            commit(appContext, staged, targetName).let { installed ->
                if (installed == null) {
                    InstallOutcome.Failed("写入插件目录失败，请检查存储空间")
                } else {
                    InstallOutcome.Installed(targetName, info, entryClass)
                }
            }
        }

    /**
     * 用户确认覆盖后调用，把暂存文件落成正式包。
     *
     * 确认框可能停留很久，期间已安装包也可能被别的入口换掉，
     * 所以这里**重新读一次待装包**再做裁决，不复用之前的结论。
     */
    suspend fun confirmOverwrite(context: Context, staged: File, targetName: String): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val incoming = PluginInstaller.readArchiveInfo(appContext, staged) ?: return@withContext false
            if (rejectIfNotUpgradable(appContext, incoming) != null) {
                runCatching { staged.delete() }
                return@withContext false
            }
            commit(appContext, staged, targetName) != null
        }

    /**
     * 版本与签名裁决，返回拒绝原因；可安装时返回 null。
     *
     * 复用 [PluginVersionPolicy]（纯函数、已有单测），这里只负责把
     * 「已安装记录」喂给它。安装前拒绝和加载阶段拒绝必须给出同一个结论，
     * 否则会出现「装进去了但用不了」。
     */
    private fun rejectIfNotUpgradable(
        context: Context,
        incoming: PluginArchiveInfo,
    ): String? {
        val installed = PluginTrustStore.load(context).installedVersions[incoming.packageName]
        return when (val verdict = PluginVersionPolicy.evaluate(installed, incoming)) {
            is VersionVerdict.Rejected -> verdict.reason
            else -> null
        }
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
            ?: throw Rejected("无法解析这个文件，它可能不是插件包（应为 APK，后缀可以随意改）")

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

    /**
     * 读 manifest 里的 uses-feature，确认是给本应用的插件。
     *
     * ## 为什么不用 `getPackageArchiveInfo(...).reqFeatures`
     *
     * 真机实测这条取不到值：`reqFeatures` 只在完整安装解析时才填充，
     * 无论 flag 加不加 `GET_META_DATA`，对未安装的包都可能是 null，
     * 结果是所有合法插件都被判成「没声明特性」。
     *
     * ## 为什么不用 AssetManager + XmlResourceParser
     *
     * 试过用 `addAssetPath` 挂载未安装的 APK 再解 XML，同样在真机失败：
     * `AssetManager()` 的构造器、`addAssetPath`、`openXmlResourceParser`
     * 都是隐藏 API，从 Android 14 起进了非 SDK 接口限制名单（本设备是 API 36），
     * 反射调用直接抛异常。
     *
     * ## 现在的做法
     *
     * 二进制 AXML 的字符串池是明文存放的，直接把 APK 里 `AndroidManifest.xml`
     * 的字节读出来做子串匹配。这不依赖任何隐藏 API，也不会因为系统版本变化失效。
     *
     * 代价是它只证明「这个字符串出现过」，不能证明它挂在 `<uses-feature>` 上。
     * 因此同时要求 `uses-feature` 这个标签名也在字符串池里，
     * 把「随便塞个字符串」的误判挡掉。真正的安全边界是签名校验（见 [validate]），
     * 这里只做「这不是给人乱选的文件」的初筛。
     */
    private fun declaresPluginFeature(context: Context, archive: File): Boolean = runCatching {
        java.util.zip.ZipFile(archive).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: return@use false
            if (entry.size > MAX_MANIFEST_BYTES) return@use false
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            containsAxmlString(bytes, "uses-feature") &&
                containsAxmlString(bytes, PLUGIN_FEATURE)
        }
    }.getOrElse {
        Log.w(TAG, "读取插件包的 uses-feature 失败：${archive.name}", it)
        false
    }

    /**
     * 在二进制 AXML 的字符串池里找一段明文。
     *
     * 字符串池按 [android.util.TypedValue] 的标志分 UTF-8 与 UTF-16 两种编码，
     * aapt2 对小字符串默认用 UTF-8，老工具链可能用 UTF-16，两种都试一遍。
     */
    private fun containsAxmlString(bytes: ByteArray, target: String): Boolean {
        fun indexOf(needle: ByteArray): Boolean {
            if (needle.isEmpty() || needle.size > bytes.size) return false
            outer@ for (i in 0..bytes.size - needle.size) {
                for (j in needle.indices) {
                    if (bytes[i + j] != needle[j]) continue@outer
                }
                return true
            }
            return false
        }
        return indexOf(target.toByteArray(Charsets.UTF_8)) ||
            indexOf(target.toByteArray(Charsets.UTF_16LE))
    }

    /** APK 里未编译前的清单文件路径（ZIP 内的固定名字） */
    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    /** 正常 manifest 只有几 KB，超过这个量说明文件不对，直接拒掉免得读进内存 */
    private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

    /** 插件包必须在 manifest 里声明的 uses-feature 名，与 plugin-api/PLUGIN_SDK.md 一致 */
    private const val PLUGIN_FEATURE = "lifeboard.plugin"

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

package com.lifeboard.server.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * `.ext` 文件在磁盘上的读写。
 *
 * 为什么不用系统安装器：`.ext` 就是改了后缀的标准 APK，不经 PackageManager 托管，
 * 用户丢进目录即可生效。代价是**下面这些事都得自己做**，少一件就会留下半个包：
 *
 * - **原子写入**：先写 `xxx.apk.part`，写完再 `renameTo` 成 `xxx.apk`。
 *   直接往最终路径写的话，中途被杀进程会留下一个残缺的 APK，
 *   下次扫描读到它要么解析失败，要么更糟——解析成功但类缺失。
 * - **先落临时目录再移入**：避免扫描时读到写了一半的文件。
 *
 * 目录约定：
 * ```
 * filesDir/plugins/                 插件根目录
 *   ├─ foo.apk                       已安装的插件包
 *   ├─ foo.apk.part                  正在写入的临时文件
 *   └─ {pluginId}/data/              插件私有目录（见 HostPluginContext）
 * ```
 *
 * ## 为什么磁盘上用 `.apk` 而不是分发的 `.ext`
 *
 * Android 14（API 34）起，`DexClassLoader` 加载未安装的代码时会校验源文件路径：
 * 只有可写目录下**被系统认作代码包**的文件才放行，判断依据是后缀。
 * 用自定义的 `.ext` 时权限位和 SELinux 标签都正常，仍然抛
 * `SecurityException: Writable dex file ... is not allowed`（API 36 实机复现）。
 *
 * 因此磁盘落盘一律用 `.apk`。用户拿到的分发文件叫什么后缀都行，
 * 安装时由 [PluginInstallService] 按包名重命名。
 */
object PluginInstaller {

    private const val TAG = "LifeBoardPluginInstall"

    /** 插件包在磁盘上的后缀。改了会让已安装的插件全部失联，务必与分发用的 `.ext` 区分开 */
    const val APK_SUFFIX = ".apk"

    private const val PART_SUFFIX = ".apk.part"

    /** 插件根目录；不存在则创建 */
    fun pluginsDir(context: Context): File =
        File(context.filesDir, "plugins").also { if (!it.exists()) it.mkdirs() }

    /**
     * 扫描已安装的插件包。
     *
     * 只认 [APK_SUFFIX] 结尾的文件；`.part` 是未完成的写入，直接跳过——
     * 它不是「损坏的插件」，不该出现在用户看到的列表里报错。
     *
     * 插件私有目录 `{pluginId}/` 也是目录，会被 `isFile` 过滤掉。
     */
    fun listArchives(context: Context): List<File> =
        pluginsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(APK_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * 把 [source] 原子地安装为插件包。
     *
     * @param fileName 目标文件名，调用方负责保证以 [APK_SUFFIX] 结尾
     * @return 安装后的文件；失败返回 null，且不留下残留
     */
    fun install(source: File, context: Context, fileName: String): File? {
        val target = File(pluginsDir(context), fileName)
        val part = File(pluginsDir(context), fileName + PART_SUFFIX)

        return runCatching {
            // 清掉可能存在的上次残留，否则 copyTo 会追加内容
            if (part.exists()) part.delete()

            source.inputStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }

            // 已存在的旧包是只读的（见 [markLoadable]），renameTo 会因目标存在而失败。
            // 先删掉：删除由目录权限决定，插件目录是应用私有的，能删。
            if (target.exists() && !target.delete()) {
                error("无法替换已有的插件包：${target.name}")
            }

            // renameTo 在同一文件系统上是原子的：要么看到旧文件，要么看到完整新文件
            if (!part.renameTo(target)) {
                error("重命名失败：${part.name} -> ${target.name}")
            }

            markLoadable(target)
            target
        }.getOrElse { error ->
            Log.w(TAG, "安装插件失败：$fileName", error)
            part.delete()
            // 覆盖安装失败时不要把旧的也删了，用户至少还能用回原版本
            null
        }
    }

    /**
     * 把插件包设为只读，让 `DexClassLoader` 愿意加载它。
     *
     * Android 14（API 34）起，动态加载代码时会检查源文件：只要**属主还带写位**
     * 就抛 `SecurityException: Writable dex file ... is not allowed`。
     * 这里检查的是文件自身的权限位，跟后缀、父目录权限都无关
     * （API 36 实机验证：`rw-------` 报错，`r--------` 正常加载）。
     *
     * `setReadOnly` 只能去掉写位，不能改其他位，对 `chmod` 的依赖比反射更安全。
     * 失败只记录不抛：某些只读挂载的存储可能拒绝改权限，
     * 真到那时加载阶段会给出明确报错，不必在这里挡住安装。
     */
    fun markLoadable(archive: File) {
        if (!archive.setReadOnly()) {
            Log.w(TAG, "无法把插件包设为只读，加载可能失败：${archive.name}")
        }
    }

    /** 删除插件包。用于撤销信任与卸载 */
    fun remove(context: Context, fileName: String): Boolean =
        File(pluginsDir(context), fileName).delete()

    /**
     * 读出包里的插件元数据，不加载任何类。
     *
     * 用 `GET_SIGNING_CERTIFICATES` 而不是已废弃的 `GET_SIGNATURES`：
     * minSdk 29 时前者可用，后者在部分机型上对新签名方案返回空。
     */
    fun readArchiveInfo(context: Context, archive: File): PluginArchiveInfo? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(archive.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archive.absolutePath, flags)
        } ?: run {
            Log.w(TAG, "无法解析插件包：${archive.name}")
            return null
        }

        val hashes = packageInfo.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }
            .orEmpty()

        return PluginArchiveInfo(
            packageName = packageInfo.packageName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            versionName = packageInfo.versionName.orEmpty(),
            signatureHashes = hashes,
        )
    }

    /** SHA-256 → 大写十六进制。与信任存储里的格式一致，改了会让已信任的插件全部失效 */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }
}

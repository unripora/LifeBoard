package com.lifeboard.shared.plugin.fs

/**
 * 插件包的文件系统操作。
 *
 * ## 为什么是接口而不是 expect/actual
 *
 * `:shared` 的 androidMain 与 desktopMain **都跑在 JVM 上**，
 * `java.io.File` 与 `java.util.zip.ZipFile` 两端都能直接用。
 * 差异不在语言能力，而在「根目录在哪」：Android 给 `context.filesDir`，
 * 桌面给用户目录。所以抽成接口、两端各写一个实现，比 expect/actual 更直白，
 * 也不会把 JVM 专有的 `File` 类型渗进 commonMain 的契约里。
 *
 * ## 安全边界
 *
 * 解包是攻击面：一个恶意 `.jsplugin` 可以用 `../../` 把文件写到宿主私有目录之外。
 * 所以路径穿越防护写在 [MAX_UNCOMPRESSED_BYTES] 一起作为**共享常量**，
 * 两端实现必须复用，不允许各自实现一套判断。
 */
interface PluginFileSystem {

    /** 插件包与解压目录的根。实现需保证目录已存在 */
    fun pluginsRoot(): String

    /** 列出根目录下指定后缀的包文件名（不含路径） */
    fun listArchives(suffix: String): List<String>

    /** 包文件是否还存在 */
    fun archiveExists(archiveName: String): Boolean

    /**
     * 把包内容读进内存。
     *
     * 只保留 `.js` 与 `manifest.json`——其余资源不进内存也不落盘。
     * 这一步同时做路径穿越防护与体积上限检查。
     *
     * @return 包内相对路径 → 字节
     */
    fun readArchive(archiveName: String): Map<String, ByteArray>

    /**
     * 把包解压到 `pluginsRoot()/{去掉后缀的包名}/` 下，返回解压目录路径。
     *
     * 每次调用前先清空目标目录：包可能已被替换成新版本，
     * 复用旧目录会读到新旧混杂的内容。
     */
    fun extractArchive(archiveName: String): String

    /**
     * 原子写入包文件。
     *
     * 先写临时文件再 rename：直接写目标文件时若中途失败，
     * 用户会得到一个半截的包，下次启动被当成损坏插件。
     */
    fun writeArchive(archiveName: String, bytes: ByteArray)

    /** 删除包文件。不存在时返回 false */
    fun deleteArchive(archiveName: String): Boolean

    /** 递归删除目录。不存在时静默返回 */
    fun deleteDir(path: String)

    /** 插件私有数据目录（凭据缓存、中间文件）。与包目录分开，重装不丢数据 */
    fun pluginDataDir(pluginId: String): String

    /**
     * 插件根目录的当前「盘面指纹」。
     *
     * 用来判断「目录里的包有没有变过」，但不读包内容：
     * 只取文件名 + 大小 + 最后修改时间。这三个量对「换版本、删掉、新拷进来」
     * 都会变，而重扫一次要重建全部 QuickJS 运行时，代价远高于比一次指纹。
     *
     * 刻意**不用** `WatchService`：Android 上没有对应的可靠实现，
     * 桌面端对「写到一半的文件」也会连发多次事件。两端共用同一套轮询判断，
     * 「什么时候该重扫」就只有一份规则。
     */
    fun snapshot(suffix: String): List<ArchiveFingerprint> =
        listArchives(suffix).map { name ->
            val file = java.io.File(pluginsRoot(), name)
            ArchiveFingerprint(name, file.length(), file.lastModified())
        }

    companion object {
        /** 单个包解压后的总字节上限。超过即拒绝，防止 zip bomb */
        const val MAX_UNCOMPRESSED_BYTES: Long = 16L * 1024 * 1024

        /** 包内条目数上限。条目极多时目录遍历本身就足以拖死宿主 */
        const val MAX_ENTRIES: Int = 512

        /** 插件包后缀。安装入口据此筛选，别的地方不要硬编码 */
        const val JS_SUFFIX: String = ".jsplugin"

        /**
         * 条目路径是否安全。
         *
         * 拒绝绝对路径与任何形式的 `..`：`foo/../../etc/passwd` 与 `/etc/passwd`
         * 都要挡住。两端实现必须先过这一关再落盘。
         */
        fun isSafeEntryName(name: String): Boolean =
            name.isNotBlank() &&
                !name.startsWith("/") &&
                !name.startsWith("\\") &&
                !name.contains("..") &&
                !name.contains(':')
    }
}

/**
 * 一个插件包在磁盘上的指纹。
 *
 * 只含「变了没有」所需的三个量，不含内容哈希——算内容哈希要读整包，
 * 而这一步的用途只是决定「要不要重扫」，重扫本身会算真正的哈希。
 */
data class ArchiveFingerprint(
    val name: String,
    val size: Long,
    val lastModified: Long,
)

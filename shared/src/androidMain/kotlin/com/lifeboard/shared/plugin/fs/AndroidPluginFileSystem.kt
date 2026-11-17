package com.lifeboard.shared.plugin.fs

import com.lifeboard.shared.log.AppLogger
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 基于 `java.io.File` 的插件包文件系统，给 Android 侧用。
 *
 * 桌面侧有一份几乎相同的实现（`DesktopPluginFileSystem`）。没有合并成公共基类，
 * 是因为项目只配了 `androidMain` / `desktopMain` 两个源集，没有建 jvm 中间层；
 * 为一份百来行的文件操作去引一个源集，构建配置的复杂度高于收益。
 *
 * ## 体积与条目上限为什么在这里卡
 *
 * zip bomb 的典型形态是「压缩后几 KB、解压后几 GB」。检查必须发生在
 * **写入之前**，一旦先落盘再判断，磁盘已经被填满了。
 * 所以边遍历边累加，一旦越过上限立刻中止。
 */
class AndroidPluginFileSystem(
    private val filesDir: File,
) : PluginFileSystem {

    /** 插件根目录，落在应用私有空间内 */
    private fun root(): File = File(filesDir, "js-plugins").apply { mkdirs() }

    override fun pluginsRoot(): String = root().absolutePath

    override fun listArchives(suffix: String): List<String> =
        root().listFiles { f -> f.isFile && f.name.endsWith(suffix) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    override fun archiveExists(archiveName: String): Boolean =
        File(root(), archiveName).isFile

    override fun readArchive(archiveName: String): Map<String, ByteArray> {
        val archive = File(root(), archiveName)
        if (!archive.isFile) throw IOException("插件包不存在：$archiveName")

        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0

        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue

                if (++count > PluginFileSystem.MAX_ENTRIES) {
                    throw IOException("插件包条目数超过上限 ${PluginFileSystem.MAX_ENTRIES}")
                }

                val name = entry.name
                if (!PluginFileSystem.isSafeEntryName(name)) {
                    AppLogger.warn("跳过不安全的包内路径：$name（$archiveName）")
                    continue
                }

                // 只保留脚本与 manifest：其它资源不进内存，也不落盘
                val isJs = name.endsWith(".js")
                val isManifest = name == MANIFEST_NAME
                if (!isJs && !isManifest) continue

                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                total += bytes.size
                if (total > PluginFileSystem.MAX_UNCOMPRESSED_BYTES) {
                    throw IOException(
                        "插件包解压后超过 ${PluginFileSystem.MAX_UNCOMPRESSED_BYTES} 字节上限"
                    )
                }
                result[name] = bytes
            }
        }
        return result
    }

    override fun extractArchive(archiveName: String): String {
        val target = File(root(), archiveName.substringBeforeLast('.'))

        // 每次加载都重新解压：包可能已被替换，复用旧目录会读到新旧混合的内容
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("无法创建解压目录：${target.absolutePath}")
        }

        readArchive(archiveName).forEach { (name, bytes) ->
            val out = File(target, name)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        return target.absolutePath
    }

    override fun writeArchive(archiveName: String, bytes: ByteArray) {
        val target = File(root(), archiveName)
        // 先写临时文件再 rename：中途失败不会留下半截的包被当成损坏插件
        val temp = File(root(), "$archiveName.tmp")
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            temp.delete()
            throw IOException("无法覆盖已有插件包：$archiveName")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("无法写入插件包：$archiveName")
        }
    }

    override fun deleteArchive(archiveName: String): Boolean {
        val file = File(root(), archiveName)
        return file.isFile && file.delete()
    }

    override fun deleteDir(path: String) {
        runCatching { File(path).deleteRecursively() }
    }

    override fun pluginDataDir(pluginId: String): String {
        // 数据目录与包目录分开：重装插件不该丢掉已登录的凭据
        val dir = File(filesDir, "plugin-data/$pluginId").apply { mkdirs() }
        return dir.absolutePath
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
    }
}

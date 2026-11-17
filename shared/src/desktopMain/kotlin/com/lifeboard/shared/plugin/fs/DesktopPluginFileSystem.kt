package com.lifeboard.shared.plugin.fs

import com.lifeboard.shared.log.AppLogger
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 基于 `java.io.File` 的插件包文件系统，给桌面侧用。
 *
 * 与 Android 侧的 `AndroidPluginFileSystem` 逻辑一致，只换根目录。
 * 根目录取用户目录而不是当前工作目录：桌面端可能从快捷方式、
 * 命令行或安装目录启动，工作目录每次都不一样，插件装完下次就找不到了。
 *
 * @param baseDir 插件根目录的父目录，默认 `~/.lifeboard`
 */
class DesktopPluginFileSystem(
    private val baseDir: File = defaultBaseDir(),
) : PluginFileSystem {

    private fun root(): File = File(baseDir, "js-plugins").apply { mkdirs() }

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
        val dir = File(baseDir, "plugin-data/$pluginId").apply { mkdirs() }
        return dir.absolutePath
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"

        fun defaultBaseDir(): File =
            File(System.getProperty("user.home") ?: ".", ".lifeboard")
    }
}

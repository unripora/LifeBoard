package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.fs.PluginFileSystem
import java.io.File
import java.util.zip.ZipFile

/**
 * 安装 `.jsplugin` 插件包。
 *
 * ## 与 APK 安装的差异
 *
 * APK 那套靠系统 `PackageManager` 解析包信息、靠证书建立信任；
 * JS 包是纯文件，宿主必须自己解 ZIP、读 `manifest.json`、算内容哈希。
 * 因此校验责任全在宿主：任何一步判定不过都不落盘，避免留下
 * 用户看不懂的「加载失败」条目。
 *
 * ## 为什么先写暂存文件再落盘
 *
 * 各端拿到的「源文件」都只是一个字节流（Android 是 SAF 的临时 Uri，
 * 桌面是用户选的路径），不能直接当作插件路径长期使用。流程固定为：
 *
 * ```
 * <暂存目录>/js_import_xxx.tmp   校验通过与失败前的落脚点，不进插件目录
 * <插件根>/foo.jsplugin.part      正在写入
 * <插件根>/foo.jsplugin           正式插件包
 * ```
 *
 * ## 为什么不做版本降级裁决
 *
 * JS 包的 versionCode 由作者自己写在 `manifest.json` 里，宿主无法验证真伪，
 * 拿它做升级裁决只会给人虚假的安全感。真正防「偷换」的是内容哈希：
 * 内容一变签名指纹就变，已信任记录自动失效，用户必须重新确认。
 *
 * ## 与 [JsPluginLoader] 的分工
 *
 * 这里只负责「把字节落成合法的包文件」，**不加载、不执行、不写信任记录**。
 * 安装完成的包会出现在 `scan()` 的待信任列表里，是否信任由用户决定。
 */
class JsPluginInstallService(
    private val fileSystem: PluginFileSystem,
    /** 暂存目录的绝对路径。实现方保证目录可写且随应用卸载一起清理 */
    private val stagingDirPath: String,
) {

    /** 选择结果：要么装好了，要么带着可展示的原因失败 */
    sealed class Outcome {

        /**
         * 已装到插件目录，但**尚未信任**，因此还没被加载。
         *
         * 必须由用户看过内容哈希后确认信任，安装动作本身不构成信任。
         */
        data class Installed(
            val archiveName: String,
            val info: JsPluginPackageInfo,
        ) : Outcome()

        /** 同 id 插件已存在，需要用户确认是否覆盖 */
        data class Conflict(
            val existingArchiveName: String,
            val existing: JsPluginPackageInfo,
            val incoming: JsPluginPackageInfo,
            /** 覆盖后要落成的文件名。与现有包同名，但显式带出来避免调用方自己拼 */
            val incomingArchiveName: String,
            /**
             * 已校验通过、等待落盘的暂存文件。
             *
             * 带出来是因为「用户可能过一会儿才点确认」：调用方必须持有它，
             * 才能在确认时重新校验一次再落盘。共用同一个暂存文件也正是
             * 「覆盖」语义的落点——不重新读一遍用户选的文件，
             * 避免确认期间源文件被换掉却仍按旧结论安装。
             */
            val staged: File,
        ) : Outcome()

        data class Failed(val reason: String) : Outcome()
    }

    /**
     * 把一个来源文件装成 JS 插件包。
     *
     * @param source 已完成读取的原始文件（调用方负责把 Uri/路径变成真实文件）
     */
    fun install(source: File): Outcome {
        val parsed = try {
            inspect(source)
        } catch (e: Rejected) {
            return Outcome.Failed(e.reason)
        }

        // 落盘名用 manifest 的 id 生成，不用来源文件名：
        // 后者可能带空格、中文、奇怪后缀，甚至与其它插件重名
        val targetName = "${parsed.manifest.id}${PluginFileSystem.JS_SUFFIX}"
        val existingPath = fileSystem.pluginsRoot() + File.separator + targetName
        val existing = File(existingPath)
        if (existing.isFile) {
            val existingInfo = readPackageInfo(existing)
            if (existingInfo != null) {
                return Outcome.Conflict(
                    existingArchiveName = existing.name,
                    existing = existingInfo,
                    incoming = parsed.info,
                    incomingArchiveName = targetName,
                    staged = source,
                )
            }
            // 旧包自己都读不出来，没有覆盖价值，直接按全新安装处理
        }

        return if (commit(source, targetName) != null) {
            Outcome.Installed(targetName, parsed.info)
        } else {
            Outcome.Failed("写入插件目录失败，请检查存储空间")
        }
    }

    /**
     * 用户确认覆盖后调用，把暂存文件落成正式包。
     *
     * 确认框可能停留很久，期间包文件也可能被别的入口换掉，
     * 所以这里**重新解析一次暂存文件**再落盘，不复用之前的结论。
     */
    fun confirmOverwrite(staged: File, targetName: String): Boolean {
        // 暂存文件可能已被清理（系统清缓存、用户取消又重进），
        // 直接判失败而不是抛异常，否则调用方要为一个可预期的情形写 try
        if (!staged.isFile) {
            AppLogger.warn("JS 插件覆盖安装失败：暂存文件已不存在 ${staged.name}")
            return false
        }

        val parsed = try {
            inspect(staged)
        } catch (e: Rejected) {
            AppLogger.warn("JS 插件覆盖安装被拒：${e.reason}")
            runCatching { staged.delete() }
            return false
        }

        // 落盘名必须由**重新解析**得到的 id 推导，不能信任调用方传进来的旧值：
        // 确认框停留期间暂存文件可能已被换掉，若仍按旧名字落盘，
        // 就会出现「manifest 说自己是 A，文件名却是 B」的错位包。
        val safeTargetName = "${parsed.manifest.id}${PluginFileSystem.JS_SUFFIX}"
        if (safeTargetName != targetName) {
            AppLogger.warn(
                "JS 插件覆盖安装：包内 id 已变化（$targetName → $safeTargetName），按新 id 落盘"
            )
        }

        return commit(staged, safeTargetName) != null
    }

    /** 放弃一次待确认的安装，清掉暂存文件 */
    fun discard(staged: File) {
        runCatching { staged.delete() }
    }

    /**
     * 把一批原始字节落到暂存目录，返回暂存文件。
     *
     * 不做校验：校验统一在 [install] 里做，这样「读文件」与「验包」
     * 各自只有一份，两端实现只需要提供字节。
     */
    fun stage(bytes: ByteArray, hintName: String = "import"): File? = runCatching {
        val dir = File(stagingDirPath).apply { mkdirs() }
        // 每次换个新名字，避免上一次残留的半个文件被当成这次的输入。
        // 用 createTempFile 而不是自己拼时间戳：毫秒时间戳在同一毫秒内的两次安装
        // 会撞名，后写入的会把前者覆盖，用户看到的是「装了 A 结果变成 B」。
        val temp = File.createTempFile("${sanitize(hintName)}_", STAGED_SUFFIX, dir)
        temp.writeBytes(bytes)
        if (temp.length() == 0L) {
            temp.delete()
            null
        } else {
            temp
        }
    }.getOrElse { error ->
        AppLogger.warn("写入插件暂存文件失败：${error.message}")
        null
    }

    /**
     * 读一个已安装包的元数据，供冲突对比展示。
     *
     * 只读 manifest 与内容哈希，不创建运行时——安装阶段不该顺带执行脚本。
     */
    fun readPackageInfo(archive: File): JsPluginPackageInfo? = runCatching {
        readArchive(archive)?.info
    }.getOrNull()

    /**
     * 解包校验，返回 manifest 与包信息。
     *
     * 只做静态检查，不执行任何脚本：用户还没点信任，脚本一行都不该跑。
     */
    private fun inspect(staged: File): Parsed = readArchive(staged) ?: throw Rejected(
        "无法解析这个文件，它可能不是 JS 插件包（应为 ZIP，内含 manifest.json）",
    )

    /**
     * 读 ZIP 内容并校验。
     *
     * 做了路径穿越与资源上限防护：条目名含 `..`、以 `/` 开头的一律跳过，
     * 总量或条目数超限直接拒绝——一个恶意包可以把宿主私有目录写满。
     * 判断逻辑复用 [PluginFileSystem] 的共享常量，不在这里另立一套阈值。
     */
    private fun readArchive(archive: File): Parsed? {
        val zip = runCatching { ZipFile(archive) }.getOrNull() ?: return null

        zip.use { zf ->
            val files = mutableMapOf<String, ByteArray>()
            var totalBytes = 0L
            var entryCount = 0

            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!PluginFileSystem.isSafeEntryName(name)) continue

                // 只保留脚本与 manifest：安装阶段其余资源一概不落盘
                if (!name.endsWith(".js") && name != JsPluginManifest.MANIFEST_NAME) continue

                entryCount++
                if (entryCount > PluginFileSystem.MAX_ENTRIES) return null

                val bytes = zf.getInputStream(entry).use { it.readBytes() }
                totalBytes += bytes.size
                if (totalBytes > PluginFileSystem.MAX_UNCOMPRESSED_BYTES) return null

                files[name] = bytes
            }

            val manifestBytes = files[JsPluginManifest.MANIFEST_NAME] ?: return null
            val manifest = JsPluginManifest.parse(manifestBytes.toString(Charsets.UTF_8))
                ?: return null

            // 入口脚本必须在包里，否则装进去也只会加载失败
            if (files[manifest.entry] == null) return null

            val contentHash = JsPluginManifest.computeContentHash(files)

            // 作者声明了哈希就比对：值对不上说明包在传输中被改过
            manifest.declaredHash?.let { declared ->
                if (!declared.equals(contentHash, ignoreCase = true)) return null
            }

            return Parsed(manifest, manifest.toPackageInfo(contentHash))
        }
    }

    /**
     * 原子写入正式目录：先写 `.part` 再改名，避免半个包出现在列表里。
     *
     * 无论成功与否都删掉暂存文件——它对调用方已经没有价值了。
     */
    private fun commit(staged: File, targetName: String): File? = runCatching {
        val dir = File(fileSystem.pluginsRoot()).apply { mkdirs() }
        val target = File(dir, targetName)
        // .part 也要唯一：两次并发安装同一个 id 时，共用一个 .part
        // 会让两个写入流互相踩，最终 rename 出一个内容混杂的包
        val part = File.createTempFile(targetName, PART_SUFFIX, dir)

        staged.copyTo(part, overwrite = true)

        // renameTo 不保证覆盖，已存在同名目标时先删掉再改名
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return@runCatching null
        }

        // 包体一旦确定就冻结成只读，减少被其它路径误改的机会
        target.setReadOnly()
        AppLogger.info("JS 插件已安装：$targetName")
        target
    }.getOrElse { error ->
        AppLogger.error("写入 JS 插件目录失败：$targetName", error)
        null
    }.also {
        runCatching { staged.delete() }
    }

    /** 文件名里只允许安全字符，避免 hintName 带路径分隔符 */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .take(24)
            .ifEmpty { "import" }

    /** 一次校验不通过，带可展示原因 */
    private class Rejected(val reason: String) : Exception(reason)

    /** 解析结果：manifest 与由此得到的包元数据 */
    private data class Parsed(
        val manifest: JsPluginManifest,
        val info: JsPluginPackageInfo,
    )

    private companion object {
        /** 暂存后缀，与正式包的 `.part` 区分开，便于排查 */
        const val STAGED_SUFFIX = ".tmp"

        /** 写入中标记，中途失败不会留下一个像正式包的半截文件 */
        const val PART_SUFFIX = ".part"
    }
}

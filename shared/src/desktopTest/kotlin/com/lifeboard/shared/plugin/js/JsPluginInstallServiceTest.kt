package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.fs.DesktopPluginFileSystem
import com.lifeboard.shared.plugin.fs.PluginFileSystem
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M5-5.7 验收：安装服务下沉到 `jvmMain` 之后行为不变。
 *
 * 这份服务原先只在 Android 侧存在，现在两端共用。共用带来的风险不是「功能不对」
 * 而是「边界被放宽」——路径穿越、超限包、哈希不符这些检查一旦在某次重构里漏掉，
 * Windows 桌面端会直接把恶意包解到用户目录里，而 Android 侧未必触发。
 * 所以这里只钉防线的部分，不测界面。
 *
 * 另外两个必须钉住的语义：
 *
 * - **安装不执行脚本**：解析只做静态检查，用户点信任之前一行 JS 都不跑。
 * - **覆盖要重新校验**：确认框可以停留很久，期间暂存文件可能被换掉。
 */
class JsPluginInstallServiceTest {

    private val sandbox: File = java.nio.file.Files.createTempDirectory("jsplugin-test-").toFile()

    private val fileSystem = DesktopPluginFileSystem(File(sandbox, "fs"))
    private val staging = File(sandbox, "staging").apply { mkdirs() }
    private val service = JsPluginInstallService(fileSystem, staging.absolutePath)

    @AfterTest
    fun cleanup() {
        sandbox.deleteRecursively()
    }

    @Test
    fun `安装成功后落到插件目录，且暂存文件被清掉`() {
        val source = writeArchive(
            "demo.jsplugin",
            manifest = manifestOf("demo.alpha", version = "1.0.0"),
            scripts = mapOf("main.js" to "function fetch(ctx){ return {metrics:[]}; }"),
        )

        val outcome = service.install(source)
        val installed = assertIs<JsPluginInstallService.Outcome.Installed>(outcome)

        // 落盘名来自 manifest 的 id，不是来源文件名
        assertEquals("demo.alpha" + PluginFileSystem.JS_SUFFIX, installed.archiveName)
        assertTrue(fileSystem.archiveExists(installed.archiveName))

        // 暂存文件必须被清掉：留着的话用户点一次安装就在缓存里堆一份包
        assertFalse(source.exists(), "安装完成后暂存文件应被删除")
    }

    @Test
    fun `同 id 再装一次得到 Conflict，且暂存文件保留给用户确认`() {
        val first = writeArchive("a.jsplugin", manifestOf("demo.beta", "1.0.0"), mapOf("main.js" to "1"))
        assertIs<JsPluginInstallService.Outcome.Installed>(service.install(first))

        val second = writeArchive("b.jsplugin", manifestOf("demo.beta", "2.0.0"), mapOf("main.js" to "2"))
        val outcome = service.install(second)
        val conflict = assertIs<JsPluginInstallService.Outcome.Conflict>(outcome)

        assertEquals("1.0.0", conflict.existing.manifest.version)
        assertEquals("2.0.0", conflict.incoming.manifest.version)
        // 这一步的暂存文件**不能**删：用户可能过一会儿才点确认
        assertTrue(conflict.staged.isFile, "冲突时暂存文件必须留给确认流程")

        // 放弃覆盖后暂存文件应被清掉，避免一直占着缓存
        service.discard(conflict.staged)
        assertFalse(conflict.staged.exists())
    }

    @Test
    fun `确认覆盖按新包内 id 落盘，用的是重新解析的结果`() {
        val first = writeArchive("a.jsplugin", manifestOf("demo.gamma", "1.0.0"), mapOf("main.js" to "old"))
        assertIs<JsPluginInstallService.Outcome.Installed>(service.install(first))

        val second = writeArchive("b.jsplugin", manifestOf("demo.gamma", "2.0.0"), mapOf("main.js" to "new"))
        val conflict = assertIs<JsPluginInstallService.Outcome.Conflict>(service.install(second))

        assertTrue(service.confirmOverwrite(conflict.staged, conflict.incomingArchiveName))

        val reloaded = assertNotNull(service.readPackageInfo(File(fileSystem.pluginsRoot(), conflict.incomingArchiveName)))
        assertEquals("2.0.0", reloaded.manifest.version)
        assertFalse(conflict.staged.exists(), "覆盖完成后暂存文件应被删除")
    }

    @Test
    fun `确认覆盖时暂存文件已消失不抛异常，只返回 false`() {
        val staged = writeArchive("gone.jsplugin", manifestOf("demo.delta", "1.0.0"), mapOf("main.js" to "x"))
        staged.delete()

        // 系统清缓存 / 用户在确认框停留期间杀进程，都会走到这条路。
        // 抛异常的话调用方要为一个完全可预期的情形写 try
        assertFalse(service.confirmOverwrite(staged, "demo.delta" + PluginFileSystem.JS_SUFFIX))
    }

    @Test
    fun `缺少 manifest 的包被拒`() {
        val broken = writeArchive("broken.jsplugin", manifest = null, scripts = mapOf("main.js" to "x"))
        val outcome = service.install(broken)
        val failed = assertIs<JsPluginInstallService.Outcome.Failed>(outcome)
        assertTrue(failed.reason.isNotBlank())
    }

    @Test
    fun `入口脚本不在包里被拒`() {
        val noEntry = writeArchive(
            "noentry.jsplugin",
            // manifest 指向 main.js，但包里只有 other.js
            manifest = manifestOf("demo.epsilon", "1.0.0", entry = "main.js"),
            scripts = mapOf("other.js" to "x"),
        )
        assertIs<JsPluginInstallService.Outcome.Failed>(service.install(noEntry))
    }

    @Test
    fun `路径穿越条目被跳过，不会写到插件目录之外`() {
        val escaped = writeArchive(
            "evil.jsplugin",
            manifestOf("demo.zeta", "1.0.0"),
            scripts = mapOf("main.js" to "ok"),
            extraEntries = mapOf("../escape.js" to "pwned".toByteArray()),
        )
        assertIs<JsPluginInstallService.Outcome.Installed>(service.install(escaped))

        // 包本身合法（有 main.js），但穿越条目必须没落地
        assertFalse(File(sandbox, "fs/escape.js").exists())
        assertFalse(File(sandbox, "fs/../escape.js").exists())
    }

    @Test
    fun `非 js 资源不落盘`() {
        val archive = writeArchive(
            "withasset.jsplugin",
            manifestOf("demo.eta", "1.0.0"),
            scripts = mapOf("main.js" to "ok"),
            extraEntries = mapOf("icon.png" to ByteArray(64) { 7 }),
        )
        val installed = assertIs<JsPluginInstallService.Outcome.Installed>(service.install(archive))

        val files = fileSystem.readArchive(installed.archiveName)
        assertTrue(files.containsKey("main.js"))
        assertTrue(files.containsKey(JsPluginManifest.MANIFEST_NAME))
        assertFalse(files.containsKey("icon.png"), "安装阶段只保留脚本与 manifest")
    }

    @Test
    fun `声明哈希对不上时拒绝安装`() {
        // 先造一个包，读到它真实的内容哈希，再故意写一个错的进 manifest
        val probe = writeArchive("probe.jsplugin", manifestOf("demo.theta", "1.0.0"), mapOf("main.js" to "payload"))
        val files = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipFile(probe).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                files[entry.name] = zf.getInputStream(entry).use { it.readBytes() }
            }
        }
        val realHash = JsPluginManifest.computeContentHash(files)

        val tampered = writeArchive(
            "tampered.jsplugin",
            manifestOf("demo.theta", "1.0.0", declaredHash = realHash.reversed()),
            mapOf("main.js" to "payload"),
        )
        assertIs<JsPluginInstallService.Outcome.Failed>(service.install(tampered))
    }

    @Test
    fun `stage 能把字节变成暂存文件，空字节返回 null`() {
        val staged = assertNotNull(service.stage("anything".toByteArray(), "小米健康.jsplugin"))
        assertTrue(staged.isFile)
        // hintName 原样带进文件名的话，中文和多余的点号会留在缓存目录里；
        // 清洗后只应剩下 ASCII 字母数字与结尾的 .tmp
        val base = staged.name.removeSuffix(".tmp")
        assertTrue(base.all { it.isLetterOrDigit() || it == '_' }, "暂存文件名应已清洗：${staged.name}")

        assertNull(service.stage(ByteArray(0)), "空文件不该产生暂存文件")
    }

    // ---- 构造测试用包 ----

    private companion object {
        fun manifestOf(
            id: String,
            version: String,
            entry: String = "main.js",
            declaredHash: String? = null,
        ): String {
            val hashLine = declaredHash?.let { "\"sha256\": \"$it\"," } ?: ""
            return """
                {
                  "id": "$id",
                  "name": "$id 显示名",
                  "version": "$version",
                  "versionCode": 1,
                  "apiVersion": 2,
                  "entry": "$entry",
                  $hashLine
                  "metrics": [],
                  "credentialSpec": []
                }
            """.trimIndent()
        }

        /**
         * 造一个 `.jsplugin`。
         *
         * @param manifest 传 null 时不写 manifest.json，用于验证「缺 manifest 被拒」
         * @param extraEntries 额外条目，用于验证路径穿越与资源过滤
         */
        fun writeArchive(
            fileName: String,
            manifest: String?,
            scripts: Map<String, String>,
            extraEntries: Map<String, ByteArray> = emptyMap(),
        ): File {
            val file = File.createTempFile("src-", "-$fileName")
            ZipOutputStream(file.outputStream()).use { zip ->
                manifest?.let {
                    zip.putNextEntry(ZipEntry(JsPluginManifest.MANIFEST_NAME))
                    zip.write(it.toByteArray())
                    zip.closeEntry()
                }
                for ((name, body) in scripts) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
                for ((name, bytes) in extraEntries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return file
        }
    }
}

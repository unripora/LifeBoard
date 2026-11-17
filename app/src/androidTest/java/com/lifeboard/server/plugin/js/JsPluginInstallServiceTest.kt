package com.lifeboard.server.plugin.js

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.shared.plugin.js.JsPluginInstallService as SharedInstallService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `.jsplugin` 安装流程端到端验证（M5 关卡）。
 *
 * 覆盖：SAF 落盘、校验、冲突确认、覆盖安装、坏包拒绝、资源上限。
 *
 * 用 `file://` Uri 代替真实的 SAF `content://`：`ContentResolver.openInputStream`
 * 对 file 协议同样可用，能把「复制到暂存 → 校验 → 原子改名」这条链路完整跑通，
 * 而无需在测试里搭一个 DocumentsProvider。
 */
@RunWith(AndroidJUnit4::class)
class JsPluginInstallServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        JsPluginLoader.closeAll(context)
        JsPluginLoader.pluginsDir(context).deleteRecursively()
        JsPluginLoader.pluginsDir(context).mkdirs()
        com.lifeboard.server.plugin.PluginTrustStore.revokeAll(context)
    }

    @After
    fun tearDown() {
        JsPluginLoader.closeAll(context)
        JsPluginLoader.pluginsDir(context).deleteRecursively()
        com.lifeboard.server.plugin.PluginTrustStore.revokeAll(context)
    }

    /** 在缓存目录造一个待安装的包文件，模拟用户从下载目录选中的文件 */
    private fun incomingPackage(
        fileName: String = "picked.jsplugin",
        manifest: String,
        scripts: Map<String, String> = mapOf(
            "main.js" to "export function fetch(d) { return [] }",
        ),
    ): Uri {
        val dir = File(context.cacheDir, "js-test-incoming").apply { mkdirs() }
        val file = File(dir, fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(JsPluginManifest.MANIFEST_NAME))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            scripts.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    private fun validManifest(
        id: String = "js.test.install",
        versionCode: Int = 1,
    ): String = """{"id":"$id","name":"安装测试","version":"1.0.0","versionCode":$versionCode,""" +
        """"apiVersion":1,"entry":"main.js",""" +
        """"metrics":[{"key":"steps","bucket":"STEPS","name":"步数"}]}"""

    private fun installedFile(id: String = "js.test.install"): File =
        File(JsPluginLoader.pluginsDir(context), "$id${JsPluginLoader.SUFFIX}")

    // ---------- 成功路径 ----------

    @Test
    fun installsToPluginDirectory() = runBlocking {
        val uri = incomingPackage(manifest = validManifest())

        val outcome = JsPluginInstallService.installFromUri(context, uri)

        assertTrue("应当安装成功，实际：$outcome", outcome is SharedInstallService.Outcome.Installed)
        val installed = outcome as SharedInstallService.Outcome.Installed
        // 落盘名用 manifest id，而不是 SAF 给的 picked.jsplugin
        assertEquals("js.test.install${JsPluginLoader.SUFFIX}", installed.archiveName)
        assertTrue("包文件应当存在", installedFile().isFile)
        assertEquals("js.test.install", installed.info.packageName)
        // 内容哈希作为签名指纹
        assertTrue(installed.info.isSigned)
    }

    @Test
    fun installedPackageIsLoadableAfterTrust() = runBlocking {
        val uri = incomingPackage(manifest = validManifest())
        val outcome = JsPluginInstallService.installFromUri(context, uri)
            as SharedInstallService.Outcome.Installed

        // 未信任时不加载
        assertTrue(JsPluginLoader.scan(context).isEmpty())

        com.lifeboard.server.plugin.PluginTrustStore.trust(context, outcome.info.identity)

        val loaded = JsPluginLoader.scan(context)
        assertEquals(1, loaded.size)
        assertEquals("js.test.install", loaded[0].pluginId)
    }

    @Test
    fun stagedTempFileIsCleanedUp() = runBlocking {
        val uri = incomingPackage(manifest = validManifest())
        JsPluginInstallService.installFromUri(context, uri)

        val leftovers = File(context.cacheDir, "file_picker")
            .listFiles { f -> f.name.startsWith("js_import_") }
            .orEmpty()
        assertTrue("暂存文件应当被清掉，实际残留 ${leftovers.size} 个", leftovers.isEmpty())
    }

    // ---------- 冲突 ----------

    @Test
    fun sameIdReportsConflict() = runBlocking {
        val first = incomingPackage(fileName = "v1.jsplugin", manifest = validManifest())
        assertTrue(
            JsPluginInstallService.installFromUri(context, first)
                is SharedInstallService.Outcome.Installed,
        )

        val second = incomingPackage(
            fileName = "v2.jsplugin",
            manifest = validManifest(versionCode = 2),
        )
        val outcome = JsPluginInstallService.installFromUri(context, second)

        assertTrue("应当报冲突，实际：$outcome", outcome is SharedInstallService.Outcome.Conflict)
        val conflict = outcome as SharedInstallService.Outcome.Conflict
        assertEquals(1L, conflict.existing.versionCode)
        assertEquals(2L, conflict.incoming.versionCode)
        assertTrue("冲突时暂存文件应当保留，供确认后落盘", conflict.staged.isFile)
    }

    @Test
    fun confirmOverwriteReplacesOldPackage() = runBlocking {
        JsPluginInstallService.installFromUri(
            context,
            incomingPackage(fileName = "v1.jsplugin", manifest = validManifest()),
        )

        val outcome = JsPluginInstallService.installFromUri(
            context,
            incomingPackage(fileName = "v2.jsplugin", manifest = validManifest(versionCode = 2)),
        ) as SharedInstallService.Outcome.Conflict

        val ok = JsPluginInstallService.confirmOverwrite(
            context,
            outcome.staged,
            outcome.existingArchiveName,
        )

        assertTrue("覆盖应当成功", ok)
        val info = JsPluginInstallService.readPackageInfo(context, installedFile())
        assertEquals("旧版本应当被替换", 2L, info?.versionCode)
    }

    @Test
    fun discardRemovesStagedFile() = runBlocking {
        JsPluginInstallService.installFromUri(
            context,
            incomingPackage(fileName = "v1.jsplugin", manifest = validManifest()),
        )
        val outcome = JsPluginInstallService.installFromUri(
            context,
            incomingPackage(fileName = "v2.jsplugin", manifest = validManifest(versionCode = 2)),
        ) as SharedInstallService.Outcome.Conflict

        JsPluginInstallService.discard(outcome.staged)

        assertTrue("暂存文件应当被删掉", !outcome.staged.exists())
        assertEquals("旧包不该被动过", 1L, JsPluginInstallService.readPackageInfo(context, installedFile())?.versionCode)
    }

    // ---------- 校验失败 ----------

    @Test
    fun nonZipFileIsRejected() = runBlocking {
        val dir = File(context.cacheDir, "js-test-incoming").apply { mkdirs() }
        val junk = File(dir, "junk.jsplugin")
        junk.writeText("这不是一个 ZIP 文件")

        val outcome = JsPluginInstallService.installFromUri(context, Uri.fromFile(junk))

        assertTrue(outcome is SharedInstallService.Outcome.Failed)
        assertTrue("不该留下任何包文件", installedFile().exists() == false)
    }

    @Test
    fun missingManifestIsRejected() = runBlocking {
        val dir = File(context.cacheDir, "js-test-incoming").apply { mkdirs() }
        val file = File(dir, "no-manifest.jsplugin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("export function fetch(d) { return [] }".toByteArray())
            zip.closeEntry()
        }

        val outcome = JsPluginInstallService.installFromUri(context, Uri.fromFile(file))

        assertTrue(outcome is SharedInstallService.Outcome.Failed)
    }

    @Test
    fun missingEntryScriptIsRejected() = runBlocking {
        val uri = incomingPackage(
            manifest = validManifest(),
            scripts = mapOf("other.js" to "export const x = 1"),
        )

        val outcome = JsPluginInstallService.installFromUri(context, uri)

        assertTrue("入口缺失应当在安装阶段就拒掉", outcome is SharedInstallService.Outcome.Failed)
    }

    @Test
    fun declaredHashMismatchIsRejected() = runBlocking {
        // 声明一个必然对不上的哈希
        val manifest = """{"id":"js.test.bad","name":"x","version":"1","versionCode":1,""" +
            """"apiVersion":1,"entry":"main.js","sha256":"deadbeef"}"""
        val uri = incomingPackage(manifest = manifest)

        val outcome = JsPluginInstallService.installFromUri(context, uri)

        assertTrue(outcome is SharedInstallService.Outcome.Failed)
    }

    @Test
    fun invalidIdIsRejected() = runBlocking {
        // id 含冒号会破坏落库 key 的解析
        val manifest = """{"id":"a:b","name":"x","version":"1","versionCode":1,""" +
            """"apiVersion":1,"entry":"main.js"}"""
        val outcome = JsPluginInstallService.installFromUri(context, incomingPackage(manifest = manifest))

        assertTrue(outcome is SharedInstallService.Outcome.Failed)
    }

    @Test
    fun pathTraversalEntryIsIgnored() = runBlocking {
        val dir = File(context.cacheDir, "js-test-incoming").apply { mkdirs() }
        val file = File(dir, "slip.jsplugin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(JsPluginManifest.MANIFEST_NAME))
            zip.write(validManifest(id = "js.test.slip").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("export function fetch(d) { return [] }".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../../evil.js"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
        }

        val outcome = JsPluginInstallService.installFromUri(context, Uri.fromFile(file))

        assertTrue(outcome is SharedInstallService.Outcome.Installed)
        val evil = File(context.filesDir.parentFile, "evil.js")
        assertTrue("目录穿越的文件不该落盘", !evil.exists())
    }

    // ---------- 元数据读取 ----------

    @Test
    fun readPackageInfoReturnsMetadata() = runBlocking {
        JsPluginInstallService.installFromUri(context, incomingPackage(manifest = validManifest()))

        val info = JsPluginInstallService.readPackageInfo(context, installedFile())

        assertNotNull(info)
        assertEquals("js.test.install", info!!.packageName)
        assertEquals(1L, info.versionCode)
    }

    @Test
    fun readPackageInfoReturnsNullForBrokenFile() {
        val dir = File(context.cacheDir, "js-test-incoming").apply { mkdirs() }
        val junk = File(dir, "junk2.jsplugin").apply { writeText("not a zip") }

        assertNull(JsPluginInstallService.readPackageInfo(context, junk))
    }
}

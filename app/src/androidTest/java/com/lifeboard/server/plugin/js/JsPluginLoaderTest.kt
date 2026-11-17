package com.lifeboard.server.plugin.js

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.plugin.api.FetchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `.jsplugin` 包加载端到端验证（M4 关卡）。
 *
 * 覆盖：解压、manifest 校验、内容哈希、专用线程运行时、桥接注入、失败隔离。
 */
@RunWith(AndroidJUnit4::class)
class JsPluginLoaderTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // 清理上一次的残留，避免测试间互相影响
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

    /** 构造一个 .jsplugin 包。 */
    private fun writePackage(
        fileName: String,
        manifest: String,
        scripts: Map<String, String>,
    ): File {
        val file = File(JsPluginLoader.pluginsDir(context), fileName)
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
        return file
    }

    /**
     * 把包标记为已信任。
     *
     * 加载器现在会先判信任再执行脚本，测试里必须显式走这一步，
     * 与真实流程（安装 → 用户确认信任 → 扫描）保持一致。
     */
    private fun trust(archive: File) {
        val info = JsPluginInstallService.readPackageInfo(context, archive)
            ?: error("测试包无法解析：${archive.name}")
        com.lifeboard.server.plugin.PluginTrustStore.trust(context, info.identity)
    }

    private fun validManifest(
        id: String = "js.test.demo",
        entry: String = "main.js",
        sha256: String? = null,
    ): String {
        val hashPart = if (sha256 != null) ""","sha256":"$sha256"""" else ""
        return """{"id":"$id","name":"测试插件","version":"1.0.0","versionCode":1,""" +
            """"apiVersion":1,"entry":"$entry",""" +
            """"metrics":[{"key":"steps","bucket":"STEPS","name":"步数"}]$hashPart}"""
    }

    // ---------- 成功路径 ----------

    @Test
    fun loadsValidPackage() {
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(date) { return [{ metricKey: 'steps', value: 42 }] }"),
        )
        trust(archive)

        val loaded = JsPluginLoader.scan(context)

        assertEquals(1, loaded.size)
        assertEquals("js.test.demo", loaded[0].pluginId)
        assertTrue("应当声明了 steps 指标", loaded[0].metrics.containsKey("steps"))
        // 内容哈希作为签名指纹
        assertEquals(1, loaded[0].archive.signatureHashes.size)
        assertTrue(loaded[0].archive.isSigned)
    }

    @Test
    fun loadedPluginIsCallable() {
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf(
                "main.js" to """
                    import { double } from './lib/math.js'
                    export async function fetch(date) {
                        return [{ metricKey: 'steps', value: double(21) }]
                    }
                """.trimIndent(),
                "lib/math.js" to "export function double(x) { return x * 2 }",
            ),
        )
        trust(archive)

        val loaded = JsPluginLoader.scan(context)
        assertEquals(1, loaded.size)

        val plugin = loaded[0].plugin
        val metrics = kotlinx.coroutines.runBlocking {
            (plugin.fetch(FakeCtx(context), "2026-11-11") as FetchResult.Success).metrics
        }

        assertEquals(1, metrics.size)
        assertEquals("steps", metrics[0].metricKey)
        assertEquals(42.0, metrics[0].value, 0.001)
    }

    @Test
    fun declaredHashMismatchIsRejected() {
        writePackage(
            "bad.jsplugin",
            validManifest(id = "js.test.bad", sha256 = "deadbeef"),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )

        val loaded = JsPluginLoader.scan(context)

        assertTrue("不该加载成功", loaded.isEmpty())
        assertEquals(1, JsPluginLoader.brokenPlugins(context).size)
        assertTrue(
            JsPluginLoader.brokenPlugins(context)[0].reason.contains("sha256"),
        )
    }

    // ---------- 失败路径 ----------

    @Test
    fun missingManifestIsRejected() {
        val file = File(JsPluginLoader.pluginsDir(context), "no-manifest.jsplugin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("export function fetch(d) { return [] }".toByteArray())
            zip.closeEntry()
        }

        JsPluginLoader.scan(context)

        assertTrue(JsPluginLoader.loadedPlugins(context).isEmpty())
        assertTrue(
            JsPluginLoader.brokenPlugins(context)[0].reason.contains("manifest"),
        )
    }

    @Test
    fun missingEntryScriptIsRejected() {
        writePackage(
            "no-entry.jsplugin",
            validManifest(id = "js.test.noentry"),
            mapOf("other.js" to "export const x = 1"),
        )

        JsPluginLoader.scan(context)

        assertTrue(JsPluginLoader.loadedPlugins(context).isEmpty())
        assertTrue(
            JsPluginLoader.brokenPlugins(context)[0].reason.contains("入口"),
        )
    }

    @Test
    fun invalidManifestIdIsRejected() {
        // id 含冒号会破坏落库 key 的解析
        writePackage(
            "bad-id.jsplugin",
            """{"id":"a:b","name":"x","version":"1","versionCode":1,"apiVersion":1,"entry":"main.js"}""",
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )

        JsPluginLoader.scan(context)

        assertTrue(JsPluginLoader.loadedPlugins(context).isEmpty())
        assertEquals(1, JsPluginLoader.brokenPlugins(context).size)
    }

    @Test
    fun entryScriptSyntaxErrorIsReported() {
        writePackage(
            "syntax.jsplugin",
            validManifest(id = "js.test.syntax"),
            mapOf("main.js" to "export function ( {"),
        )

        JsPluginLoader.scan(context)

        assertTrue(JsPluginLoader.loadedPlugins(context).isEmpty())
        assertEquals(1, JsPluginLoader.brokenPlugins(context).size)
    }

    @Test
    fun oneBrokenPackageDoesNotBlockOthers() {
        val good = writePackage(
            "good.jsplugin",
            validManifest(id = "js.test.good"),
            mapOf("main.js" to "export function fetch(d) { return [{ metricKey: 'steps', value: 1 }] }"),
        )
        trust(good)
        writePackage(
            "bad.jsplugin",
            validManifest(id = "js.test.bad"),
            mapOf("main.js" to "!!! not javascript !!!"),
        )

        val loaded = JsPluginLoader.scan(context)

        assertEquals("好包应当成功", 1, loaded.size)
        assertEquals("js.test.good", loaded[0].pluginId)
        assertEquals("坏包应当单独记录", 1, JsPluginLoader.brokenPlugins(context).size)
    }

    @Test
    fun zipSlipEntriesAreIgnored() {
        val file = File(JsPluginLoader.pluginsDir(context), "slip.jsplugin")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(JsPluginManifest.MANIFEST_NAME))
            zip.write(validManifest(id = "js.test.slip").toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("main.js"))
            zip.write("export function fetch(d) { return [] }".toByteArray())
            zip.closeEntry()

            // 试图写到插件目录之外
            zip.putNextEntry(ZipEntry("../../../evil.js"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
        }

        JsPluginLoader.scan(context)

        val evil = File(context.filesDir.parentFile, "evil.js")
        assertTrue("目录穿越的文件不该落盘", !evil.exists())
    }

    @Test
    fun findReturnsLoadedPlugin() {
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )
        trust(archive)
        JsPluginLoader.scan(context)

        assertNull(JsPluginLoader.find(context, "不存在的插件"))
        assertEquals("js.test.demo", JsPluginLoader.find(context, "js.test.demo")?.pluginId)
    }

    @Test
    fun rescanReleasesOldRuntimes() {
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )
        trust(archive)

        val first = JsPluginLoader.scan(context)
        val second = JsPluginLoader.scan(context)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        // 重新扫描后仍是同一个 id，但实例是新的一份
        assertEquals("js.test.demo", second[0].pluginId)
        assertTrue("应当是新的插件实例", first[0] !== second[0])
    }

    // ---------- 信任门禁 ----------

    @Test
    fun untrustedPackageIsNotLoaded() {
        writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )

        val loaded = JsPluginLoader.scan(context)

        assertTrue("未信任的包不该被加载", loaded.isEmpty())
        assertEquals(1, JsPluginLoader.pendingPlugins(context).size)
        val pending = JsPluginLoader.pendingPlugins(context)[0]
        assertEquals("js.test.demo", pending.packageName)
        assertTrue("应当给出信任身份", pending.identity != null)
        assertTrue("有内容哈希，不算 unsigned", !pending.unsigned)
    }

    @Test
    fun trustingThenRescanningLoadsPlugin() {
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )

        assertTrue("首次扫描未信任", JsPluginLoader.scan(context).isEmpty())

        trust(archive)

        val loaded = JsPluginLoader.scan(context)
        assertEquals(1, loaded.size)
        assertTrue("信任后不该再留在待信任列表", JsPluginLoader.pendingPlugins(context).isEmpty())
    }

    @Test
    fun changingContentRevokesTrust() {
        // 先装一个包并信任
        val archive = writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [] }"),
        )
        trust(archive)
        assertEquals(1, JsPluginLoader.scan(context).size)

        // 内容被换掉后再扫描：内容包括内容哈希都变了，必须重新征求用户同意
        writePackage(
            "demo.jsplugin",
            validManifest(),
            mapOf("main.js" to "export function fetch(d) { return [{ metricKey: 'steps', value: 9 }] }"),
        )

        val loaded = JsPluginLoader.scan(context)
        assertTrue("内容变了应当重新进入待信任", loaded.isEmpty())
        assertEquals(1, JsPluginLoader.pendingPlugins(context).size)
    }
}

/** 简化版上下文，只满足加载器测试里调用 fetch 的需要。 */
private class FakeCtx(private val ctx: android.content.Context) :
    com.lifeboard.plugin.api.PluginContext {
    override val pluginId: String get() = "js.test.demo"
    override val credentials = object : com.lifeboard.plugin.api.CredentialStore {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(slot: String): String? = map[slot]
        override suspend fun put(slot: String, value: String) {
            map[slot] = value
        }
        override suspend fun remove(slot: String) {
            map.remove(slot)
        }
        override suspend fun clear() {
            map.clear()
        }
    }

    override val filesDirPath: String = File(ctx.filesDir, "js-test-fake").apply { mkdirs() }.absolutePath

    override suspend fun recentMetrics(
        metricKey: String,
        days: Int,
    ): List<com.lifeboard.plugin.api.MetricSample> = emptyList()
}

package com.lifeboard.server.plugin.js

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.plugin.PluginDataSource
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.server.plugin.PluginTrustStore
import com.lifeboard.server.source.SourceProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Android 宿主包装与 Worker 源注册的回归测试；不访问凭据或宿主数据库。 */
@RunWith(AndroidJUnit4::class)
class JsPluginProviderRegistrationTest {
    private lateinit var context: IsolatedPluginContext
    private lateinit var builtinKeys: List<String>

    @Before
    fun setUp() {
        // 不清理 targetContext 的真实插件或信任记录；每个测试都有独立目录与偏好命名空间。
        context = IsolatedPluginContext(InstrumentationRegistry.getInstrumentation().context)
        builtinKeys = SourceProviderRegistry.providers().filterNot { it.source.isPlugin }.map { it.source.key }
    }

    @After
    fun tearDown() {
        JsPluginLoader.closeAll(context)
        context.clearTestFiles()
    }

    @Test
    fun trustedMetricsRegisterOnceAndCreateExistingAdapters() {
        val archive = writePackage(
            metrics = """[
                {"key":"steps","bucket":"STEPS","name":"旧步数"},
                {"key":"steps","bucket":"STEPS","name":"步数"},
                {"key":"sleep","bucket":"DURATION_MINUTES","name":"睡眠"}
            ]""",
        )
        trust(archive)

        val loaded = JsPluginLoader.scan(context).single()
        val providers = pluginProviders()
        assertEquals(2, providers.size)
        assertEquals(2, providers.map { it.source.key }.distinct().size)
        assertEquals(
            setOf(SourceRef.pluginKey(ID, "steps"), SourceRef.pluginKey(ID, "sleep")),
            providers.map { it.source.key }.toSet(),
        )
        assertSame(loaded, JsPluginLoader.find(context, ID))
        assertSame(loaded, JsPluginLoader.loadedPlugins(context).single())
        providers.forEach { provider ->
            val source = provider.source as SourceRef.Plugin
            val spec = loaded.metrics.getValue(source.metricKey)
            assertEquals(spec.bucket, source.bucket)
            assertEquals("测试插件·${spec.displayName}", source.displayName)
            val adapter = SourceProviderRegistry.create(context, source)
            assertTrue(adapter is PluginDataSource)
            assertEquals(source, adapter!!.source)
            assertNotSame(adapter, SourceProviderRegistry.create(context, source))
        }
        assertEquals(MetricBucket.DURATION_MINUTES, providers.single {
            (it.source as SourceRef.Plugin).metricKey == "sleep"
        }.source.bucket)
        assertBuiltinsPreserved()

        JsPluginLoader.awaitFirstScan(context)
        assertSame(loaded, JsPluginLoader.find(context, ID))
        val rescanned = JsPluginLoader.scan(context).single()
        assertSame(loaded, rescanned)
        assertEquals(2, pluginProviders().size)
    }

    @Test
    fun pendingPackageNeverRegistersProviders() {
        writePackage()

        assertTrue(JsPluginLoader.scan(context).isEmpty())
        assertEquals(1, JsPluginLoader.pendingPlugins(context).size)
        assertTrue(pluginProviders().isEmpty())
        assertBuiltinsPreserved()
    }

    @Test
    fun revokeStopsRetainedAdaptersWithoutReplacingOtherPlugins() = runBlocking {
        trust(writePackage())
        trust(writePackage(fileName = "other.jsplugin", id = OTHER_ID))
        JsPluginLoader.scan(context)
        val loaded = JsPluginLoader.find(context, ID)!!
        val other = JsPluginLoader.find(context, OTHER_ID)!!
        val provider = pluginProviders().single { (it.source as SourceRef.Plugin).pluginId == ID }
        val retainedAdapter = provider.factory(context)

        // 使用真实管理器撤销路径，保证它修改的正是 Provider 捕获的同一个包装。
        PluginManager.revokeTrust(context, ID)

        assertSame(loaded, JsPluginLoader.find(context, ID))
        assertFalse(loaded.trusted)
        assertSame(other, JsPluginLoader.find(context, OTHER_ID))
        assertTrue(other.trusted)
        assertEquals(listOf(OTHER_ID), pluginProviders().map { (it.source as SourceRef.Plugin).pluginId })
        assertNull(SourceProviderRegistry.create(context, provider.source))
        assertTrue(retainedAdapter.collect(DATE).isEmpty())
        assertFalse(retainedAdapter.health().healthy)
        assertTrue(provider.factory(context).collect(DATE).isEmpty())

        // 持久化撤销后重扫也不能重新登记。
        JsPluginLoader.scan(context)
        assertNull(JsPluginLoader.find(context, ID))
        assertEquals(listOf(ID), JsPluginLoader.pendingPlugins(context).map { it.packageName })
        assertSame(other, JsPluginLoader.find(context, OTHER_ID))
        assertBuiltinsPreserved()
    }

    @Test
    fun trustingAgainRegistersFreshWrapperButDoesNotReviveStaleAdapters() = runBlocking {
        val archive = writePackage()
        trust(archive)
        val revoked = JsPluginLoader.scan(context).single()
        val provider = pluginProviders().single()
        val retainedAdapter = provider.factory(context)
        PluginManager.revokeTrust(context, ID)
        assertTrue(JsPluginLoader.scan(context).isEmpty())

        // PluginManager.trust 的同步路径是持久化信任后扫描，没有调用 markTrusted。
        // 不调用它的异步 publish，避免为了展示凭据槽位而触碰宿主数据库。
        trust(archive)
        val trusted = JsPluginLoader.scan(context).single()

        assertNotSame(revoked, trusted)
        assertTrue(trusted.trusted)
        assertFalse(revoked.trusted)
        assertEquals(provider.source, pluginProviders().single().source)
        assertTrue(SourceProviderRegistry.create(context, provider.source) is PluginDataSource)
        assertTrue(retainedAdapter.collect(DATE).isEmpty())
        assertTrue(provider.factory(context).collect(DATE).isEmpty())
    }

    @Test
    fun emptyScanClearsRegistrationAndInvalidatesRetainedAdapters() = runBlocking {
        val archive = writePackage()
        trust(archive)
        val old = JsPluginLoader.scan(context).single()
        val provider = pluginProviders().single()
        val retainedAdapter = provider.factory(context)
        assertTrue(archive.delete())

        assertTrue(JsPluginLoader.scan(context).isEmpty())

        assertFalse(old.trusted)
        assertTrue(pluginProviders().isEmpty())
        assertNull(SourceProviderRegistry.create(context, provider.source))
        assertTrue(retainedAdapter.collect(DATE).isEmpty())
        assertBuiltinsPreserved()
    }

    @Test
    fun removeImmediatelyUnregistersOnlyRemovedArchive() = runBlocking {
        val archive = writePackage()
        trust(archive)
        trust(writePackage(fileName = "other.jsplugin", id = OTHER_ID))
        JsPluginLoader.scan(context)
        val removed = JsPluginLoader.find(context, ID)!!
        val other = JsPluginLoader.find(context, OTHER_ID)!!
        val provider = pluginProviders().single { (it.source as SourceRef.Plugin).pluginId == ID }

        assertTrue(JsPluginLoader.remove(context, archive.name))

        assertFalse(removed.trusted)
        assertNull(JsPluginLoader.find(context, ID))
        assertSame(other, JsPluginLoader.find(context, OTHER_ID))
        assertTrue(other.trusted)
        assertEquals(listOf(OTHER_ID), pluginProviders().map { (it.source as SourceRef.Plugin).pluginId })
        assertTrue(provider.factory(context).collect(DATE).isEmpty())
        assertFalse(JsPluginLoader.remove(context, archive.name))
        assertEquals(1, pluginProviders().size)
        assertBuiltinsPreserved()
    }

    @Test
    fun closeClearsProvidersAndAllowsFirstScanToReload() = runBlocking {
        trust(writePackage())
        val old = JsPluginLoader.scan(context).single()
        val provider = pluginProviders().single()

        JsPluginLoader.closeAll(context)

        assertFalse(old.trusted)
        assertTrue(JsPluginLoader.loadedPlugins(context).isEmpty())
        assertTrue(pluginProviders().isEmpty())
        assertNull(SourceProviderRegistry.create(context, provider.source))
        assertTrue(provider.factory(context).collect(DATE).isEmpty())
        assertBuiltinsPreserved()

        JsPluginLoader.awaitFirstScan(context)
        val reloaded = JsPluginLoader.find(context, ID)!!
        assertNotSame(old, reloaded)
        assertTrue(reloaded.trusted)
        assertFalse(old.trusted)
        assertEquals(1, pluginProviders().size)
    }

    @Test
    fun changedPackageLosesOldProvidersUntilNewContentIsTrusted() = runBlocking {
        val archive = writePackage()
        trust(archive)
        val old = JsPluginLoader.scan(context).single()
        val provider = pluginProviders().single()
        writePackage(
            metrics = """[{"key":"sleep","bucket":"DURATION_MINUTES","name":"睡眠"}]""",
            script = "export function fetch(date) { throw new Error('updated script must be trusted first') }",
        )

        assertTrue(JsPluginLoader.scan(context).isEmpty())
        assertFalse(old.trusted)
        assertTrue(pluginProviders().isEmpty())
        assertEquals(1, JsPluginLoader.pendingPlugins(context).size)
        assertTrue(provider.factory(context).collect(DATE).isEmpty())

        trust(archive)
        val replacement = JsPluginLoader.scan(context).single()
        assertNotSame(old, replacement)
        assertTrue(replacement.trusted)
        assertEquals(listOf(SourceRef.pluginKey(ID, "sleep")), pluginProviders().map { it.source.key })
        assertNull(SourceProviderRegistry.create(context, provider.source))
        assertFalse(old.trusted)
        assertBuiltinsPreserved()
    }

    @Test
    fun manifestOnlyMetricUpdateReplacesProvidersWithoutNewTrust() = runBlocking {
        trust(writePackage())
        val old = JsPluginLoader.scan(context).single()
        val provider = pluginProviders().single()
        writePackage(metrics = """[{"key":"sleep","bucket":"DURATION_MINUTES","name":"睡眠"}]""")

        val updated = JsPluginLoader.scan(context).single()

        assertNotSame(old, updated)
        assertFalse(old.trusted)
        assertTrue(updated.trusted)
        assertEquals(old.archive.identity, updated.archive.identity)
        assertEquals(listOf(SourceRef.pluginKey(ID, "sleep")), pluginProviders().map { it.source.key })
        assertTrue(provider.factory(context).collect(DATE).isEmpty())
        assertTrue(JsPluginLoader.pendingPlugins(context).isEmpty())
    }

    private fun pluginProviders() = SourceProviderRegistry.providers().filter {
        val source = it.source
        source is SourceRef.Plugin && source.pluginId in setOf(ID, OTHER_ID)
    }

    private fun assertBuiltinsPreserved() {
        assertEquals(builtinKeys, SourceProviderRegistry.providers().filterNot { it.source.isPlugin }.map { it.source.key })
    }

    private fun trust(archive: File) {
        val info = JsPluginInstallService.readPackageInfo(context, archive) ?: error("Invalid test package")
        PluginTrustStore.trust(context, info.identity)
    }

    private fun writePackage(
        fileName: String = "provider.jsplugin",
        id: String = ID,
        metrics: String = """[{"key":"steps","bucket":"STEPS","name":"步数"}]""",
        script: String = "export function fetch(date) { throw new Error('revoked adapter must not execute') }",
    ): File {
        val archive = File(JsPluginLoader.pluginsDir(context), fileName)
        val manifest = """{"id":"$id","name":"测试插件","version":"1.0.0","versionCode":1,"apiVersion":1,"entry":"main.js","metrics":$metrics}"""
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf(
                JsPluginManifest.MANIFEST_NAME to manifest,
                "main.js" to script,
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    private class IsolatedPluginContext(base: Context) : ContextWrapper(base) {
        private val namespace = "js-provider-test-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, namespace).apply { mkdirs() }
        private val preferences = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${namespace}_$name"
            preferences += isolatedName
            return super.getSharedPreferences(isolatedName, mode)
        }

        fun clearTestFiles() {
            preferences.forEach { super.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
            root.deleteRecursively()
        }
    }

    private companion object {
        const val ID = "js.test.provider"
        const val OTHER_ID = "js.test.other.provider"
        const val DATE = "2026-11-11"
    }
}

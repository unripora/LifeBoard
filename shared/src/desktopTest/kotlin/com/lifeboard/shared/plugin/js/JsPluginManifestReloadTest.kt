package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.plugin.PluginTrustStore
import com.lifeboard.shared.plugin.fs.DesktopPluginFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsPluginManifestReloadTest {
    private val directory = Files.createTempDirectory("manifest-reload-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "fs"))
    private val trust = PluginTrustStore(MemorySettings())
    private val loader = JsPluginLoader(fileSystem, trust)
    private val context = object : PluginContext {
        override val pluginId = PLUGIN_ID
        override val filesDirPath = File(directory, "data").apply { mkdirs() }.absolutePath
        override val credentials = object : CredentialStore {
            override suspend fun get(slot: String): String? = null
            override suspend fun put(slot: String, value: String) = Unit
            override suspend fun remove(slot: String) = Unit
            override suspend fun clear() = Unit
        }
        override suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample> = emptyList()
    }

    @AfterTest
    fun cleanup() {
        loader.closeAll()
        directory.deleteRecursively()
    }

    @Test
    fun unchangedPackageReusesPluginAndRuntimeState() = runBlocking {
        writePackage()
        val first = scanTrusted()
        assertEquals(1.0, fetchValue(first))
        val rescanned = loader.scan().single()
        assertSame(first, rescanned)
        assertEquals(2.0, fetchValue(rescanned))
    }

    @Test
    fun manifestOnlyEntryUpdateUsesNewModuleWithoutRequiringNewTrust() = runBlocking {
        writePackage()
        val first = scanTrusted()
        assertEquals(1.0, fetchValue(first))

        writePackage(entry = "alternate.js")
        val updated = loader.scan().single()
        assertNotSame(first.plugin, updated.plugin)
        assertEquals(first.packageInfo.identity, updated.packageInfo.identity)
        assertEquals(42.0, fetchValue(updated))
        assertTrue(loader.pendingPlugins().isEmpty())
    }

    @Test
    fun manifestOnlyMetricUpdateRebuildsPublishedDeclarations() {
        writePackage(metricName = "Old steps")
        val first = scanTrusted()
        writePackage(metricName = "Updated steps")
        val updated = loader.scan().single()

        assertNotSame(first.plugin, updated.plugin)
        assertEquals("Updated steps", updated.plugin.metrics.single().displayName)
        assertEquals(first.packageInfo.identity, updated.packageInfo.identity)
    }

    private suspend fun fetchValue(loaded: JsLoadedPlugin): Double =
        (loaded.plugin.fetch(context, "2030-01-01") as FetchResult.Success).metrics.single().value

    private fun scanTrusted(): JsLoadedPlugin {
        assertTrue(loader.scan().isEmpty())
        trust.trust(requireNotNull(loader.pendingPlugins().single().identity))
        return loader.scan().single()
    }

    private fun writePackage(entry: String = "main.js", metricName: String = "Steps") {
        val manifest = """{
            "id":"$PLUGIN_ID", "name":"Reload test", "version":"1.0.0", "versionCode":1,
            "apiVersion":2, "entry":"$entry",
            "metrics":[{"key":"steps", "bucket":"STEPS", "name":"$metricName"}]
        }""".trimIndent()
        val files = linkedMapOf(
            "manifest.json" to manifest,
            "main.js" to "let count = 0; export function fetch(date) { return { status: 'success', metrics: [{ metricKey: 'steps', value: ++count }] }; }",
            "alternate.js" to "export function fetch(date) { return { status: 'success', metrics: [{ metricKey: 'steps', value: 42 }] }; }",
        )
        ZipOutputStream(File(fileSystem.pluginsRoot(), "reload.jsplugin").outputStream()).use { zip ->
            files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private class MemorySettings : SettingsStore {
        private val values = mutableMapOf<String, Any>()
        override fun getString(key: String, default: String) = values[key] as? String ?: default
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun putLong(key: String, value: Long) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun contains(key: String) = values.containsKey(key)
    }

    private companion object {
        const val PLUGIN_ID = "test.manifest.reload"
    }
}

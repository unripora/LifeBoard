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
import kotlin.test.assertTrue

/**
 * 单段同步预算测试（对应计划 5.2）。
 *
 * 核心场景：`for(;;){}` 这类**纯同步**死循环，既不会产生 Promise 落地，
 * 也不会自己退出。「60 秒无进展」对它无效——它根本不会进入等待。
 * 必须由宿主按「单段执行时长」主动打断。
 */
class JsSyncBudgetTest {
    private val directory = Files.createTempDirectory("sync-budget-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "fs"))
    private val trust = PluginTrustStore(MemorySettings())

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
        directory.deleteRecursively()
    }

    @Test
    fun infiniteSyncLoopIsInterruptedAndReturnsFailure() = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            val entry = loadWith(loader, "export function fetch(date) { for(;;) {} }")
            val started = System.currentTimeMillis()
            val result = entry.plugin.fetch(context, "2030-01-01")
            val elapsed = System.currentTimeMillis() - started

            assertTrue(
                elapsed < 6_000,
                "同步死循环必须在预算内被打断，实际 ${elapsed}ms（说明看门狗没生效）",
            )
            assertTrue(
                result is FetchResult.Failure,
                "被打断的调用必须明确失败，不能伪装成「今天没数据」",
            )
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun normalCallIsNotInterrupted() = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            // 正常返回的调用不能被误杀
            val entry = loadWith(loader, 
                "export function fetch(date) { return { status: 'success', metrics: [{ metricKey: 'steps', value: 3 }] }; }"
            )
            val result = entry.plugin.fetch(context, "2030-01-01")
            val metrics = (result as FetchResult.Success).metrics
            assertTrue(metrics.single().value == 3.0)
        } finally {
            loader.closeAll()
        }
    }

    private fun loadWith(loader: JsPluginLoader, entrySource: String): JsLoadedPlugin {
        val manifest = """{
            "id":"$PLUGIN_ID", "name":"Budget", "version":"1.0.0", "versionCode":1,
            "apiVersion":2, "entry":"main.js",
            "metrics":[{"key":"steps", "bucket":"STEPS", "name":"Steps"}]
        }""".trimIndent()
        ZipOutputStream(File(fileSystem.pluginsRoot(), "budget.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to entrySource).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        loader.scan()
        trust.trust(loader.pendingPlugins().single().identity!!)
        return loader.scan().single()
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
        const val PLUGIN_ID = "test.sync.budget"
    }
}

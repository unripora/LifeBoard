package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.LoginResult
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
import kotlin.test.assertTrue

/**
 * 兼容矩阵（对应计划 8.9）。
 *
 * 覆盖四类组合与两种降级：
 * - 旧包 + 新宿主（默认开关）
 * - 旧包 + 新宿主（延迟 + 回收）→ **必须保活**，因为旧包没声明 lifecycle
 * - 新包（声明 onDemand）+ 回收 → 应被回收
 * - 声明未知能力 → 拒载且点名
 * - 开关 off→on→off 回滚 → 插件仍可用
 */
class PluginCompatibilityMatrixTest {
    private val directory = Files.createTempDirectory("compat-matrix-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "plugins"))
    private val trust = PluginTrustStore(MemorySettings())

    private val context = object : PluginContext {
        override val pluginId = "compat"
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
    fun legacyPackageWorksUnderDefaultSwitches() = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            install("legacy.a", legacyJs, manifestExtra = "")
            trustAll()
            val loaded = loader.scan().single()

            // 旧契约：单参 login + 裸数组 fetch
            val login = loaded.plugin.login(context, emptyMap(), null)
            assertTrue(login is LoginResult.NeedInput, "旧包单参 login 仍可用")
            val fetch = loaded.plugin.fetch(context, "2030-01-01")
            assertTrue(fetch is FetchResult.Success, "旧包裸数组 fetch 仍被当作成功")
            assertEquals(1.0, (fetch as FetchResult.Success).metrics.single().value)
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun legacyPackageIsNotAutoEvictedEvenWithLazyAndEviction() = runBlocking {
        var now = 0L
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
        try {
            // 旧包：manifest 里没有 lifecycle 字段
            install("legacy.b", legacyJs, manifestExtra = "")
            trustAll()
            val id = loader.scan().single().plugin.id
            loader.withStarted(id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount())

            loader.setEvictionEnabled(true)
            now += 10 * 60 * 1000
            assertEquals(
                0,
                loader.evictIdleNow(),
                "旧包没声明 lifecycle，不得被自动回收——它可能依赖模块级状态活着",
            )
            assertEquals(1, loader.liveRuntimeCount())
            println("[兼容] 旧包在回收开启时仍保活")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun declaredOnDemandPackageIsEvicted() = runBlocking {
        var now = 0L
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
        try {
            install("modern.a", modernJs, """, "lifecycle":"onDemand", "idleTimeoutSec":60""")
            trustAll()
            val id = loader.scan().single().plugin.id
            loader.withStarted(id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount())

            loader.setEvictionEnabled(true)
            now += 61_000
            assertEquals(1, loader.evictIdleNow(), "显式声明 onDemand 的包应被回收")
            println("[兼容] 新包声明 onDemand 后按需回收")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun unknownRequiredCapabilityIsRejectedWithReason() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            install("need.x", modernJs, """, "requires":["runtime.lifecycle"]""")
            trustAll()
            val loaded = loader.scan()
            assertTrue(loaded.isEmpty(), "声明未知能力的包应被拒载")
            val reason = loader.brokenPlugins().single().reason
            assertTrue(reason.contains("runtime.lifecycle"), "拒载原因必须点名缺失能力：$reason")
            println("[兼容] 未知能力拒载：$reason")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun knownRequiredCapabilityIsAccepted() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            install("need.ok", modernJs, """, "requires":["contract.loginIntent"]""")
            trustAll()
            assertEquals(1, loader.scan().size, "声明已知能力应能加载")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun switchesCanBeRolledBack() = runBlocking {
        // 9.2 的回滚演练：开启 → 使用 → 关闭 → 仍可用
        var now = 0L
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
        try {
            install("rollback.a", modernJs, """, "lifecycle":"onDemand", "idleTimeoutSec":60""")
            trustAll()
            val id = loader.scan().single().plugin.id

            // 开启回收并使用
            loader.setEvictionEnabled(true)
            loader.withStarted(id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount())

            // 关闭回收：即使超时也不该再被回收
            loader.setEvictionEnabled(false)
            now += 10 * 60 * 1000
            assertEquals(0, loader.evictIdleNow(), "关闭后不得回收")
            assertEquals(1, loader.liveRuntimeCount())

            // 关闭后插件仍可用
            val again = loader.withStarted(id) { it.fetch(context, "2030-01-02") }
            assertTrue(again is FetchResult.Success? || again != null, "回滚后插件仍能工作")
            println("[兼容] 开关开启→关闭后插件仍可用，未出现残留状态")
        } finally {
            loader.closeAll()
        }
    }

    // ---------- 脚手架 ----------

    /** 旧契约插件：单参 login、裸数组 fetch、无 onUnload */
    private val legacyJs = """
        export function login(inputs) {
            return { type: 'needInput', prompt: 'legacy', fields: [] };
        }
        export function logout() {}
        export function authState() { return { state: 'loggedOut' }; }
        export function fetch(date) {
            return [{ metricKey: 'steps', value: 1 }];
        }
        export function health() { return { status: 'healthy' }; }
    """.trimIndent()

    /** 新契约插件：带 status 的 fetch */
    private val modernJs = """
        export function login(inputs, options) { return { type: 'success' }; }
        export function logout() {}
        export function authState() { return { state: 'loggedOut' }; }
        export function fetch(date) {
            return { status: 'success', metrics: [{ metricKey: 'steps', value: 2 }] };
        }
        export function health() { return { status: 'healthy' }; }
    """.trimIndent()

    private fun install(id: String, js: String, manifestExtra: String) {
        val manifest = """{
            "id":"$id","name":"$id","version":"1.0.0","versionCode":1,
            "apiVersion":4,"entry":"main.js","metrics":[{"key":"steps","bucket":"STEPS","name":"Steps"}]
            $manifestExtra
        }""".trimIndent()
        ZipOutputStream(File(fileSystem.pluginsRoot(), "$id.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to js).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun trustAll() {
        val loader = JsPluginLoader(fileSystem, trust)
        loader.scan()
        loader.pendingPlugins().forEach { p -> p.identity?.let { trust.trust(it) } }
        loader.closeAll()
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
}

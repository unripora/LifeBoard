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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 延迟实例化测试（对应计划 4.5 / 8.2）。
 *
 * ## 判定依据为什么是「有没有实例」而不是「脚本有没有跑」
 *
 * JS 只能跑在 `JsRuntime` 里。**没有运行时就不可能执行任何模块代码**，
 * 因此「延迟扫描后 `find()` 返回 null」已经等价于「模块没有被执行」。
 *
 * 比在入口脚本里埋副作用更可靠：模块顶层无法安全调用 `ctx.*`
 * （加载期还没有绑定上下文），而断点/计时又都不可移植。
 *
 * 对照组（非延迟模式）证明这套判定确实能区分两种模式——否则「没有实例」
 * 可能只是因为它本来就加载失败了。
 */
class JsPluginLazyInstantiationTest {
    private val directory = Files.createTempDirectory("lazy-instantiation-").toFile()
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
    fun lazyScanRegistersWithoutCreatingRuntime() {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            writePackage()
            loader.scan()
            trust.trust(loader.pendingPlugins().single().identity!!)

            val scanned = loader.scan()
            assertEquals(1, scanned.size, "待启动插件也要出现在列表里，否则用户以为没装上")
            // 列表里拿到的是委托外壳，不是已启动实例
            assertTrue(loader.hasPendingLaunch(PLUGIN_ID), "此时仍是待启动状态")
            assertTrue(loader.brokenPlugins().isEmpty(), "扫描未执行入口，不应出现 broken")
            assertTrue(loader.deferredPlugins().isNotEmpty(), "应登记为待启动")
        } finally {
            loader.closeAll()
        }
    }

    /**
     * 对照组：同一份包在非延迟模式下扫描即实例化。
     *
     * 若没有这条，上面「没有实例」的断言可能只是因为包本身加载失败，
     * 那样测试就永远通过、却什么也没验证。
     */
    @Test
    fun eagerScanCreatesRuntimeImmediately() {
        val eagerLoader = JsPluginLoader(fileSystem, trust, lazyInstantiation = false)
        try {
            writePackage()
            eagerLoader.scan()
            trust.trust(eagerLoader.pendingPlugins().single().identity!!)

            val loaded = eagerLoader.scan()
            assertEquals(1, loaded.size, "非延迟模式扫描即产出实例")
            assertNotNull(eagerLoader.find(PLUGIN_ID))
            assertTrue(eagerLoader.deferredPlugins().isEmpty(), "非延迟模式没有待启动项")
        } finally {
            eagerLoader.closeAll()
        }
    }

    @Test
    fun ensureStartedCreatesRuntimeOnDemand(): Unit = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            writePackage()
            loader.scan()
            trust.trust(loader.pendingPlugins().single().identity!!)
            val listed = loader.scan().single()

            // 列表阶段拿到的委托外壳就能直接调用：首次业务调用触发启动
            val result = listed.plugin.fetch(context, "2030-01-01")
            val metrics = (result as FetchResult.Success).metrics
            assertEquals(7.0, metrics.single().value, "委托外壳应透明触发启动并返回数据")

            assertTrue(!loader.hasPendingLaunch(PLUGIN_ID), "启动后不再待启动")
            assertNotNull(loader.ensureStarted(PLUGIN_ID), "启动后应能取到真实条目")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun repeatedCallsReuseSameRuntime(): Unit = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            writePackage()
            loader.scan()
            trust.trust(loader.pendingPlugins().single().identity!!)
            val listed = loader.scan().single()

            listed.plugin.fetch(context, "2030-01-01")
            val first = loader.ensureStarted(PLUGIN_ID)
            listed.plugin.fetch(context, "2030-01-02")
            val second = loader.ensureStarted(PLUGIN_ID)

            assertNotNull(first)
            assertNotNull(second)
            assertEquals(first.plugin, second.plugin, "多次调用必须复用同一实例")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun ensureStartedIsIdempotent() {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            writePackage()
            loader.scan()
            trust.trust(loader.pendingPlugins().single().identity!!)
            loader.scan()

            val first = loader.ensureStarted(PLUGIN_ID)
            val second = loader.ensureStarted(PLUGIN_ID)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals(first.plugin, second.plugin, "重复启动应复用同一实例")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun ensureStartedOnUnknownPluginReturnsNull() {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            assertNull(loader.ensureStarted("not.installed"))
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun lazyModeOffByDefault() {
        // 默认构造不得开启延迟实例化：行为必须与改造前一致
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            assertTrue(!loader.lazyInstantiation, "延迟实例化默认关闭，需显式开启")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun rescanAfterStartDoesNotDuplicateEntry() {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            writePackage()
            loader.scan()
            trust.trust(loader.pendingPlugins().single().identity!!)
            loader.scan()
            loader.ensureStarted(PLUGIN_ID)

            // 重扫：已启动的实例应被复用，且不重复出现在待启动列表
            val rescanned = loader.scan()
            assertEquals(1, rescanned.size, "重扫应复用已启动实例")
            assertTrue(loader.deferredPlugins().isEmpty(), "已启动插件不应回到待启动")
        } finally {
            loader.closeAll()
        }
    }

    private fun writePackage() {
        val entry = """
            export function fetch(date) {
                return { status: 'success', metrics: [{ metricKey: 'steps', value: 7 }] };
            }
        """.trimIndent()
        val manifest = """{
            "id":"$PLUGIN_ID", "name":"Lazy test", "version":"1.0.0", "versionCode":1,
            "apiVersion":2, "entry":"main.js",
            "metrics":[{"key":"steps", "bucket":"STEPS", "name":"Steps"}]
        }""".trimIndent()

        ZipOutputStream(File(fileSystem.pluginsRoot(), "lazy.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to entry)
                .forEach { (name, body) ->
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
        const val PLUGIN_ID = "test.lazy.instantiation"
    }
}

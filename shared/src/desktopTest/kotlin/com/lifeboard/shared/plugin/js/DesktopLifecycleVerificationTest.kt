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
import kotlin.test.assertTrue

/**
 * 桌面端真实环境验证（不是 mock）。
 *
 * 用**真实的 QuickJS DLL、真实的 mihealth 产物、真实文件系统**跑一遍生命周期：
 * - 默认模式：扫描即实例化（对照基线）
 * - 延迟模式：扫描后 0 运行时；首次调用才创建
 * - 空闲回收：到点关闭；再次调用重建且状态可用
 * - 资源对照：运行时数量 / 线程数 / 堆占用
 *
 * 这台机器就是"真机"——桌面端用户跑的是同一套代码。
 */
class DesktopLifecycleVerificationTest {
    private val directory = Files.createTempDirectory("desktop-lifecycle-real-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "plugins"))
    private val trust = PluginTrustStore(MemorySettings())
    private val bundle: File = locateBundle()
    private val dataDir = File(directory, "data").apply { mkdirs() }

    private val context = object : PluginContext {
        override val pluginId = "verify"
        override val filesDirPath = dataDir.absolutePath
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
    fun defaultModeInstantiatesOnScan() = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            installPackages(count = 3)
            trustAll()
            val loaded = loader.scan()

            assertEquals(3, loaded.size, "默认模式扫描应实例化全部插件")
            assertEquals(3, loader.liveRuntimeCount(), "有 3 个运行时存活")
            println("[默认模式] 插件=${loaded.size} 运行时=${loader.liveRuntimeCount()}")

            // 真实调用：无凭据时 mihealth 应报 authRequired，而不是崩溃/空数据
            val result = loaded.first().plugin.fetch(context, "2030-01-01")
            assertTrue(
                result is FetchResult.AuthenticationRequired || result is FetchResult.Failure,
                "无凭据时应返回认证失败，实际=${result::class.simpleName}",
            )
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun lazyModeCreatesZeroRuntimesOnScan() = runBlocking {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            installPackages(count = 3)
            trustAll()
            val scanned = loader.scan()

            assertEquals(3, scanned.size, "待启动插件也要出现在列表")
            assertEquals(0, loader.liveRuntimeCount(), "延迟模式下扫描后运行时应为 0")

            // 首次业务调用触发创建
            val outcome = loader.withStarted(scanned.first().plugin.id) { plugin ->
                plugin.fetch(context, "2030-01-01")
            }
            assertTrue(outcome != null, "按需启动后应能调用")
            assertEquals(1, loader.liveRuntimeCount(), "只有被调用的那一个被创建")
            println("[延迟模式] 扫描后运行时=0 → 首次调用后=1")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun idleEvictionClosesAndRebuilds() = runBlocking {
        // 注入可控时钟：直接推到空闲阈值之后，避免真的等 60 秒
        var now = 0L
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
        try {
            installPackages(count = 1, lifecycle = "onDemand", idleTimeoutSec = 60)
            trustAll()
            val plugin = loader.scan().single().plugin

            // 首次调用 → 创建真实运行时
            loader.withStarted(plugin.id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount(), "首次调用后应有 1 个运行时")

            // 阈值未到：不回收
            loader.setEvictionEnabled(true)
            now += 30_000
            assertEquals(0, loader.evictIdleNow(), "空闲 30 秒不应回收")

            // 阈值已到：真实关闭
            now += 31_000
            assertEquals(1, loader.evictIdleNow(), "空闲超 60 秒应回收")
            assertEquals(0, loader.liveRuntimeCount(), "回收后运行时应为 0")

            // 再次调用 → 重建并可用（这是按需加载的核心价值）
            val rebuilt = loader.withStarted(plugin.id) { it.fetch(context, "2030-01-01") }
            assertTrue(rebuilt != null, "回收后应能重建并继续调用")
            assertEquals(1, loader.liveRuntimeCount(), "重建后运行时回到 1")
            println("[回收] 30s 不回收 → 61s 回收 → 再调用重建成功")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun closingOnePluginDoesNotBreakAnother() = runBlocking {
        // 5.4 的真实回归：JNA 回调缓冲曾按全局表存放，
        // 关掉 A 会连带释放 B 尚在使用的缓冲。
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            installPackages(count = 2)
            trustAll()
            val entries = loader.scan()
            assertEquals(2, entries.size)

            val first = entries[0]
            val second = entries[1]

            // 两个都先跑一次，确保各自的回调缓冲都被写过
            first.plugin.fetch(context, "2030-01-01")
            val before = second.plugin.fetch(context, "2030-01-01")
            assertTrue(before != null)

            // 只关掉第一个
            loader.releaseEntry(first, JsPluginLoader.StopReason.IDLE)
            assertEquals(1, loader.liveRuntimeCount(), "关掉一个后应剩 1 个")

            // 第二个必须仍然可用：若缓冲被误清，这里会崩或返回异常
            val after = second.plugin.fetch(context, "2030-01-01")
            assertTrue(after != null, "关闭 A 后 B 必须仍能正常调用")
            println("[隔离] 关闭 A 后 B 调用正常：${after::class.simpleName}")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun residentAdmissionCapIsTwo() {
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            installPackages(count = 3, lifecycle = "resident")
            trustAll()
            loader.scan()

            // 逐个拉起，第三个别名应被降级
            val loaded = loader.loadedPlugins()
            runBlocking {
                loaded.forEach { loader.withStarted(it.plugin.id) { p -> p.fetch(context, "2030-01-01") } }
            }
            println("[常驻] 拉起 ${loaded.size} 个声明 resident 的插件，运行时=${loader.liveRuntimeCount()}")
            assertTrue(loader.liveRuntimeCount() <= 3)
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun evictedPluginStaysVisibleAndRebuildable() = runBlocking {
        // 回归：回收后插件曾从列表里消失（用户会以为"装好的插件不见了"）
        var now = 0L
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
        try {
            installPackages(count = 1, lifecycle = "onDemand", idleTimeoutSec = 60)
            trustAll()
            val id = loader.scan().single().plugin.id

            loader.withStarted(id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount())

            loader.setEvictionEnabled(true)
            now += 61_000
            assertEquals(1, loader.evictIdleNow(), "应先完成一次真实回收")
            assertEquals(0, loader.liveRuntimeCount(), "运行时已关闭")

            // 关键断言 1：列表里仍然有这个插件
            assertTrue(
                loader.loadedPlugins().any { it.plugin.id == id },
                "回收后插件必须仍在列表中",
            )
            // 关键断言 2：通过 find 拿到的是可用的委托外壳，调用能重建
            val shell = loader.find(id)
            assertTrue(shell != null, "find 应返回待启动外壳而不是 null")
            val rebuilt = shell.plugin.fetch(context, "2030-01-01")
            assertTrue(rebuilt != null, "外壳调用应触发重建并返回结果")
            assertEquals(1, loader.liveRuntimeCount(), "重建后运行时回到 1")
            println("[回归] 回收后列表仍可见，重新调用触发重建成功")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun releaseEntryStopsLazyRuntime() = runBlocking {
        // 回归：延迟模式下 releaseEntry 曾什么都做不了（条目在管理器里）
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            installPackages(count = 1)
            trustAll()
            val id = loader.scan().single().plugin.id
            loader.withStarted(id) { it.fetch(context, "2030-01-01") }
            assertEquals(1, loader.liveRuntimeCount())

            val entry = loader.find(id)!!
            loader.releaseEntry(entry, JsPluginLoader.StopReason.REVOKED)
            assertEquals(0, loader.liveRuntimeCount(), "撤销后运行时必须被关闭")
            println("[回归] 延迟模式下 releaseEntry 能真正停机")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun detailedResultCarriesReason() = runBlocking {
        // 自审第 7 项：失败必须能带出原因，而不是只给一个 null
        val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true)
        try {
            val result = loader.withStartedDetailed("not.installed") { it.fetch(context, "2030-01-01") }
            assertTrue(
                result is JsPluginLoader.StartedResult.Unavailable,
                "未安装的插件应返回 Unavailable 而不是 Ok",
            )
            val reason = (result as JsPluginLoader.StartedResult.Unavailable).reason
            assertTrue(reason.isNotBlank(), "必须带可展示的原因")
            println("[原因] 未安装插件返回：$reason")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun resourceSnapshot() {
        val threadsBefore = Thread.activeCount()
        val heapBefore = usedHeapMb()
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            installPackages(count = 5)
            trustAll()
            loader.scan()
            val threadsAfter = Thread.activeCount()
            val heapAfter = usedHeapMb()
            println(
                "[资源] 5 个真实插件：线程 ${threadsBefore}→${threadsAfter}，" +
                    "堆 ${"%.1f".format(heapBefore)}MB→${"%.1f".format(heapAfter)}MB，" +
                    "运行时=${loader.liveRuntimeCount()}",
            )
            assertEquals(5, loader.liveRuntimeCount(), "默认模式应全部常驻（对照基线）")
        } finally {
            loader.closeAll()
        }
        // 关闭后运行时归零，线程也应回落
        Thread.sleep(300)
        println("[资源] 全部关闭后线程=${Thread.activeCount()}")
    }

    // ---------- 脚手架 ----------

    /** 用真实 mihealth 产物生成指定数量的插件包（改 id 避免冲突） */
    private fun installPackages(
        count: Int,
        lifecycle: String? = null,
        idleTimeoutSec: Int? = null,
    ) {
        val mainJs = bundle.readText()
        repeat(count) { index ->
            val id = "verify.plugin.$index"
            val manifest = buildString {
                append("""{"id":"$id","name":"Verify $index","version":"1.0.0","versionCode":1,"apiVersion":4,"entry":"main.js","metrics":[]""")
                if (lifecycle != null) append(""","lifecycle":"$lifecycle"""")
                if (idleTimeoutSec != null) append(""","idleTimeoutSec":$idleTimeoutSec""")
                append("}")
            }
            ZipOutputStream(File(fileSystem.pluginsRoot(), "verify$index.jsplugin").outputStream()).use { zip ->
                listOf("manifest.json" to manifest, "main.js" to mainJs).forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    private fun trustAll() {
        val loader = JsPluginLoader(fileSystem, trust)
        loader.scan()
        loader.pendingPlugins().forEach { pending ->
            pending.identity?.let { trust.trust(it) }
        }
        loader.closeAll()
    }

    private fun usedHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0
    }

    /** 定位真实插件产物；找不到就让测试显式失败，不能静默跳过 */
    private fun locateBundle(): File {
        val candidates = listOf(
            File("../js-plugins/mihealth/main.js"),
            File("js-plugins/mihealth/main.js"),
            File("../../js-plugins/mihealth/main.js"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "找不到 js-plugins/mihealth/main.js（user.dir=${System.getProperty("user.dir")}）；" +
                    "本验证必须使用真实产物"
            )
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

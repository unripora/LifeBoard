package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.plugin.PluginTrustStore
import com.lifeboard.shared.plugin.fs.DesktopPluginFileSystem
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 使用真实 QuickJS，覆盖扫描、稳定外壳和租约之间的生产接线。 */
class LoaderLifecycleRegressionTest {
    private val directory = Files.createTempDirectory("loader-regression-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "plugins"))
    private val trust = PluginTrustStore(MemorySettings())
    private var now = 0L
    private val loader = JsPluginLoader(fileSystem, trust, lazyInstantiation = true, nowMs = { now })
    private val context = object : PluginContext {
        override val pluginId = ID
        override val filesDirPath = File(directory, "data").apply { mkdirs() }.absolutePath
        override val credentials = object : CredentialStore {
            private val values = mutableMapOf<String, String>()
            override suspend fun get(slot: String) = values[slot]
            override suspend fun put(slot: String, value: String) { values[slot] = value }
            override suspend fun remove(slot: String) { values.remove(slot) }
            override suspend fun clear() { values.clear() }
        }
        override suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample> = emptyList()
    }

    @AfterTest
    fun cleanup() {
        loader.closeAll()
        directory.deleteRecursively()
    }

    @Test
    fun revokedRecipeCannotBeFoundOrRestarted() = runBlocking {
        val shell = installTrusted()
        assertNotNull(loader.ensureStarted(ID))
        trust.revokePackage(ID)
        assertTrue(loader.scan().isEmpty())
        assertEquals(1, loader.pendingPlugins().size)
        assertNull(loader.find(ID))
        assertTrue(loader.loadedPlugins().isEmpty())
        assertNull(loader.ensureStarted(ID))
        assertIs<FetchResult.Failure>(shell.fetch(context, "2026-11-26"))
        assertEquals(0, loader.liveRuntimeCount())
    }

    @Test
    fun archiveReplacementIsCheckedBeforeDeferredExecution() {
        installTrusted()
        writePackage(SOURCE + "\nexport const untrustedReplacement = true;")
        assertNull(loader.ensureStarted(ID), "不能凭旧哈希执行刚替换的脚本")
        assertEquals(0, loader.liveRuntimeCount())
    }

    @Test
    fun manifestReplacementIsCheckedEvenWhenScriptHashIsUnchanged() {
        installTrusted()
        writePackage(SOURCE, name = "Changed manifest")
        assertNull(loader.ensureStarted(ID), "manifest 不参与内容哈希，仍需独立比对")
        assertEquals(0, loader.liveRuntimeCount())
    }

    @Test
    fun listenerRegisteredBeforeStartDoesNotRecurseOrWakeRuntime() = runBlocking {
        val shell = installTrusted()
        val events = mutableListOf<SessionUiEvent>()
        shell.setSessionUiListener { events += it }
        assertEquals(0, loader.liveRuntimeCount())
        assertTrue(shell.onSessionStart(context, SessionConfig()))
        assertTrue(events.any { it is SessionUiEvent.Status && it.text == "已连接" })
        shell.onSessionStop(context)
    }

    @Test
    fun sessionLeaseSurvivesIdleAndRescanThenReleasesOnStop() = runBlocking {
        val shell = installTrusted()
        // 保活句柄就是控制器会话期间持有的那个：没有它，两次输入之间实例是空闲的
        val keepAlive = loader.acquireSessionLease(ID)!!
        val events = mutableListOf<SessionUiEvent>()
        shell.setSessionUiListener { events += it }
        assertTrue(shell.onSessionStart(context, SessionConfig()))
        // 重扫后拿到的必须是**同一个外壳对象**：调用方（详情页、会话控制器）长期持有它，
        // 若被换成真实实例，后续调用就绕过租约，空闲回收能在会话进行中把运行时关掉。
        val rescanned = loader.scan().single().plugin
        val foundAgain = loader.find(ID)?.plugin
        assertSame(rescanned, foundAgain, "find 与 scan 必须给出同一个外壳对象")
        assertSame(shell, rescanned, "重扫必须复用同一个外壳对象")
        loader.setEvictionEnabled(true)
        now = 120_000L
        assertEquals(0, loader.evictIdleNow(), "持有会话租约时即使长时间没有输入也不能回收")
        shell.onSessionEvent(context, SessionEvent(type = "input", payload = "hello"))
        assertTrue(events.any { it is SessionUiEvent.Message && it.text == "hello" })
        shell.onSessionStop(context)
        keepAlive.release()
        now += 61_000L
        assertEquals(1, loader.evictIdleNow())
        assertEquals(0, loader.liveRuntimeCount())
        assertTrue(shell.onSessionStart(context, SessionConfig()), "回收后重新连接必须重新挂上监听")
        assertEquals(2, events.count { it is SessionUiEvent.Status && it.text == "已连接" })
        shell.onSessionStop(context)
    }

    @Test
    fun revokeDuringSessionMakesKeepAliveReportDisconnected() = runBlocking {
        val shell = installTrusted()
        val keepAlive = loader.acquireSessionLease(ID)!!
        assertTrue(shell.onSessionStart(context, SessionConfig()))
        assertTrue(keepAlive.isAlive())
        trust.revokePackage(ID)
        loader.scan()
        assertTrue(!keepAlive.isAlive(), "撤权后必须能立刻察觉，而不是拿着已关闭的实例继续发送")
        assertNull(keepAlive.withPluginSuspend { "still working" })
    }

    @Test
    fun evictedRuntimeIsRebuiltOnNextSessionStart() = runBlocking {
        val shell = installTrusted()
        val keepAlive = loader.acquireSessionLease(ID)!!
        assertTrue(shell.onSessionStart(context, SessionConfig()))
        keepAlive.release()
        loader.setEvictionEnabled(true)
        now += 61_000L
        assertEquals(1, loader.evictIdleNow())
        assertTrue(shell.onSessionStart(context, SessionConfig()), "回收后再次开会话要能重建")
        assertEquals(1, loader.liveRuntimeCount())
        shell.onSessionStop(context)
    }

    @Test
    fun failedSessionStartDoesNotPinRuntime() = runBlocking {
        val shell = installTrusted(SOURCE.replace("return true;", "return false;"))
        assertEquals(false, shell.onSessionStart(context, SessionConfig()))
        loader.setEvictionEnabled(true)
        now = 61_000L
        assertEquals(1, loader.evictIdleNow())
    }

    private fun installTrusted(source: String = SOURCE): com.lifeboard.plugin.api.SourcePlugin {
        writePackage(source)
        loader.scan()
        trust.trust(loader.pendingPlugins().single().identity!!)
        return loader.scan().single().plugin
    }

    private fun writePackage(source: String, name: String = "Lifecycle regression") {
        val manifest = """{"id":"$ID","name":"$name","version":"1.0.0","versionCode":1,"apiVersion":5,"entry":"main.js","metrics":[],"lifecycle":"onDemand","idleTimeoutSec":60}"""
        ZipOutputStream(File(fileSystem.pluginsRoot(), "probe.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to source).forEach { (name, body) ->
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
        const val ID = "regression.lifecycle"
        val SOURCE = """
            export function fetch() { return {status:'success',metrics:[]}; }
            export function sessionStart() {
                ctx.session.emit(JSON.stringify({type:'status',text:'已连接',inputEnabled:true}));
                return true;
            }
            export function sessionEvent(event) {
                ctx.session.emit(JSON.stringify({type:'message',role:'assistant',text:event.payload}));
                return {action:'continue'};
            }
            export function sessionStop() {}
        """.trimIndent()
    }
}

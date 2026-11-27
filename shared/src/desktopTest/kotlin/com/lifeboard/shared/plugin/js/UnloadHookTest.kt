package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.plugin.PluginTrustStore
import com.lifeboard.shared.plugin.fs.DesktopPluginFileSystem
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `onUnload` 钩子测试（对应计划 4.6 / 4.7）。
 *
 * 三条必须守住的线：
 * 1. **超时不阻塞**：钩子里写死循环，关闭流程仍须在预算内返回
 * 2. **撤权不执行插件代码**：撤销信任/清除数据时不得回调插件
 * 3. **钩子期间不能写文件**：加载期未绑定上下文，旧代际无法把已清数据写回
 */
class UnloadHookTest {
    private val directory = Files.createTempDirectory("unload-hook-").toFile()
    private val fileSystem = DesktopPluginFileSystem(File(directory, "fs"))
    private val trust = PluginTrustStore(MemorySettings())
    private val dataDir = File(directory, "data").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun hookHasTimeoutAndDoesNotBlockRecovery() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            val entry = loadWithHook(loader, "export function onUnload() { for(;;) {} }")
            val started = System.currentTimeMillis()
            loader.releaseEntry(entry, JsPluginLoader.StopReason.SHUTDOWN)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(
                elapsed < 6_000,
                "钩子写死循环时关闭流程必须在预算内返回，实际 ${elapsed}ms",
            )
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun revokedSkipsHookEntirely() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            val entry = loadWithHook(loader, "export function onUnload() { for(;;) {} }")
            val started = System.currentTimeMillis()
            loader.releaseEntry(entry, JsPluginLoader.StopReason.REVOKED)
            val elapsed = System.currentTimeMillis() - started
            // 若调用了钩子，必然等满超时；跳过则几乎立即返回
            assertTrue(
                elapsed < 1_000,
                "撤销信任不得执行插件代码，实际耗时 ${elapsed}ms 说明钩子被调用了",
            )
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun hookCannotWriteFiles() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            val entry = loadWithHook(
                loader,
                """
                export function onUnload() {
                    ctx.files.write('unload-marker.txt', 'should-not-exist');
                }
                """.trimIndent(),
            )
            loader.releaseEntry(entry, JsPluginLoader.StopReason.SHUTDOWN)
            assertFalse(
                File(dataDir, "unload-marker.txt").exists(),
                "卸载钩子期间不得写文件——否则旧代际能把已清数据写回",
            )
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun missingHookIsHarmless() {
        val loader = JsPluginLoader(fileSystem, trust)
        try {
            // 插件没实现 onUnload：关闭应照常完成，不能报错或卡住
            val entry = loadWithHook(loader, "")
            val started = System.currentTimeMillis()
            loader.releaseEntry(entry, JsPluginLoader.StopReason.SHUTDOWN)
            assertTrue(System.currentTimeMillis() - started < 3_000)
        } finally {
            loader.closeAll()
        }
    }

    /** 写入含指定 onUnload 的包并完成信任 + 扫描，返回已加载条目 */
    private fun loadWithHook(loader: JsPluginLoader, onUnloadSource: String): JsLoadedPlugin {
        val entry = """
            export function fetch(date) {
                return { status: 'success', metrics: [] };
            }
            $onUnloadSource
        """.trimIndent()
        val manifest = """{
            "id":"$PLUGIN_ID", "name":"Unload hook", "version":"1.0.0", "versionCode":1,
            "apiVersion":2, "entry":"main.js", "metrics":[]
        }""".trimIndent()
        ZipOutputStream(File(fileSystem.pluginsRoot(), "unload.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to entry).forEach { (name, body) ->
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
        const val PLUGIN_ID = "test.unload.hook"
    }
}

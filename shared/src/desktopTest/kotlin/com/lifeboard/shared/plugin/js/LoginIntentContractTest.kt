package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.LoginIntent
import com.lifeboard.plugin.api.LoginOptions
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
 * 登录意图契约测试（对应计划 7.1 的宿主侧）。
 *
 * 要证明两件事：
 * 1. 宿主确实把 `options.intent` 作为**第二个实参**传给 JS
 * 2. 不传 options 时插件拿到的是 `undefined`（旧宿主兼容路径不变）
 */
class LoginIntentContractTest {
    private val directory = Files.createTempDirectory("login-intent-").toFile()
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
    fun intentIsPassedAsSecondArgument() = runBlocking {
        val loader = loadPlugin(requiresLoginIntent = true)
        try {
            val plugin = loader.scan().single().plugin

            val result = plugin.login(context, emptyMap(), LoginOptions(LoginIntent.SUBMIT))
            val prompt = (result as LoginResult.NeedInput).prompt
            assertEquals("intent=submit", prompt, "宿主必须把意图作为第二个实参传给 JS")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun missingOptionsKeepsLegacyPath() = runBlocking {
        val loader = loadPlugin(requiresLoginIntent = false)
        try {
            val plugin = loader.scan().single().plugin
            // 旧宿主不传 options：插件应看到 undefined，而不是被塞一个默认值
            val result = plugin.login(context, emptyMap(), null)
            val prompt = (result as LoginResult.NeedInput).prompt
            assertEquals("intent=none", prompt, "不传 options 时插件应拿到 undefined")
        } finally {
            loader.closeAll()
        }
    }

    @Test
    fun manifestDeclaringLoginIntentIsAccepted() {
        val loader = loadPlugin(requiresLoginIntent = true)
        try {
            val loaded = loader.scan()
            assertEquals(1, loaded.size, "声明 contract.loginIntent 的插件应能加载")
            assertTrue(loader.brokenPlugins().isEmpty())
        } finally {
            loader.closeAll()
        }
    }

    private fun loadPlugin(requiresLoginIntent: Boolean): JsPluginLoader {
        val loader = JsPluginLoader(fileSystem, trust)
        val requires = if (requiresLoginIntent) """ "requires": ["contract.loginIntent"], """ else ""
        val manifest = """{
            "id":"$PLUGIN_ID", "name":"Intent", "version":"1.0.0", "versionCode":1,
            "apiVersion":2, "entry":"main.js", $requires
            "metrics":[]
        }""".trimIndent()
        val entry = """
            export function login(inputs, options) {
                const intent = options && options.intent ? options.intent : 'none';
                return {
                    type: 'needInput',
                    prompt: 'intent=' + intent,
                    fields: [{ name: 'account', displayName: '账号' }]
                };
            }
        """.trimIndent()
        ZipOutputStream(File(fileSystem.pluginsRoot(), "intent.jsplugin").outputStream()).use { zip ->
            mapOf("manifest.json" to manifest, "main.js" to entry).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        loader.scan()
        trust.trust(loader.pendingPlugins().single().identity!!)
        return loader
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
        const val PLUGIN_ID = "test.login.intent"
    }
}

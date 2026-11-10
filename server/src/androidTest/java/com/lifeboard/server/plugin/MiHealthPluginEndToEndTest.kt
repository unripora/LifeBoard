package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.PluginApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 小米健康插件的端到端加载验证（M5）。
 *
 * ## 它证明了什么
 *
 * M5 的意义不是「代码搬过去了」，而是**契约真的能承载一个真实站点**。
 * 这个测试守住三条最容易悄悄坏掉的约定：
 *
 * 1. 自带 OkHttp 的插件能被宿主加载——插件的实现依赖不能和宿主冲突
 * 2. 契约类仍是 `compileOnly`——若被打进插件 dex，`instance is SourcePlugin` 会失败
 * 3. 多轮登录协议可用——第一轮不带输入时，插件应返回要填的字段而不是失败
 *
 * ## 它不做什么
 *
 * 不测真实登录与拉数：那需要真实小米账号，且会触发风控。
 * 这部分只能人工在插件页走一遍。
 */
@RunWith(AndroidJUnit4::class)
class MiHealthPluginEndToEndTest {

    private lateinit var context: Context

    /** assets 里的文件名（`.ext` 只是分发后缀） */
    private val assetName = "mihealth-plugin.ext"

    /**
     * 落到插件目录时的文件名必须是 `包名.apk`：
     * [PluginInstaller.listArchives] 只认 `.apk` 结尾，写成 `.ext` 会被直接过滤掉。
     */
    private val archiveName = "com.lifeboard.plugin.mihealth${PluginInstaller.APK_SUFFIX}"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        cleanPlugins()
        PluginTrustStore.revokeAll(context)
    }

    @After
    fun tearDown() {
        cleanPlugins()
        PluginTrustStore.revokeAll(context)
    }

    private fun cleanPlugins() {
        PluginInstaller.pluginsDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun installArchive() {
        val apk = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        apk.use { input ->
            val target = File(PluginInstaller.pluginsDir(context), archiveName)
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test
    fun untrustedMiHealth_isListedAsPendingWithSignature() {
        installArchive()

        val loaded = PluginLoader.scan(context)

        assertTrue("未信任不能加载", loaded.isEmpty())
        assertTrue("包本身没问题，不该报加载失败", PluginLoader.brokenPlugins().isEmpty())

        val pending = PluginLoader.pendingPlugins()
        assertEquals(1, pending.size)
        val row = pending.first()
        assertEquals("com.lifeboard.plugin.mihealth", row.packageName)
        assertFalse("插件包必须带签名，否则无法建立信任", row.unsigned)
        assertNotNull(row.identity)
        assertTrue(row.identity!!.signatureHashes.isNotEmpty())
    }

    /**
     * 关键用例：插件自带 OkHttp，且契约类是 compileOnly。
     *
     * 只要有一项做错，这里就拿不到可用的 [com.lifeboard.plugin.api.SourcePlugin]：
     * - OkHttp 没打进插件 → 类加载失败
     * - 契约类打进了插件 → 类型不同，`instance is` 判断失败
     */
    @Test
    fun trustedMiHealth_loadsAndDeclaresBothMetrics() {
        installArchive()
        val archive = File(PluginInstaller.pluginsDir(context), archiveName)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginTrustStore.trust(context, info.identity)

        val loaded = PluginLoader.scan(context)

        assertEquals(1, loaded.size)
        val plugin = loaded.first().plugin
        assertEquals("com.lifeboard.plugin.mihealth", plugin.id)
        assertEquals(PluginApi.VERSION, plugin.apiVersion)

        // 两个量纲：步数与睡眠。metricKey 复用 MetricBucket 名，
        // 这样插件数据与历史内置数据在 metric 表里语义一致
        val metrics = plugin.metrics.associateBy { it.metricKey }
        assertEquals(2, metrics.size)
        assertEquals(MetricBucket.STEPS, metrics[MetricBucket.STEPS.name]?.bucket)
        assertEquals(MetricBucket.SLEEP_MINUTES, metrics[MetricBucket.SLEEP_MINUTES.name]?.bucket)

        // 登录槽位：账号与密码，且密码必须要求掩码
        val slots = plugin.credentialSpec.slots
        assertEquals(2, slots.size)
        assertTrue("密码槽位必须掩码，否则输入时明文暴露", slots.any { it.secret })
    }

    /**
     * 多轮登录协议：宿主空手问一轮，插件应告诉我们要填什么，而不是直接失败。
     *
     * 这条是 M5 里契约改动的核心——旧契约只有 Success/Failure，
     * 验证码那段流程根本没有地方表达。
     */
    @Test
    fun loginWithoutInputs_asksForCredentials() = runBlocking {
        installArchive()
        val archive = File(PluginInstaller.pluginsDir(context), archiveName)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginTrustStore.trust(context, info.identity)
        PluginLoader.scan(context)

        val result = PluginManager.login(context, "com.lifeboard.plugin.mihealth")

        assertTrue("第一轮应要求用户填账号密码，而不是直接失败", result is LoginResult.NeedInput)
        val need = result as LoginResult.NeedInput
        assertTrue("至少要问账号", need.fields.any { it.name == "account" })
        val password = need.fields.firstOrNull { it.name == "password" }
        assertNotNull("要问密码", password)
        assertTrue("密码字段必须标记为敏感", password!!.secret)
    }

    /** 未登录时健康度应为 Broken，并给出去登录的提示 */
    @Test
    fun healthWithoutLogin_isBroken() = runBlocking {
        installArchive()
        val archive = File(PluginInstaller.pluginsDir(context), archiveName)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginTrustStore.trust(context, info.identity)
        PluginLoader.scan(context)

        val loaded = PluginLoader.loadedPlugins().first()
        val ctx = HostPluginContext(loaded.pluginId, context)
        val health = loaded.plugin.health(ctx)

        assertTrue(
            "未登录时必须报告不可用，否则会一直重试拉数 $health",
            health is com.lifeboard.plugin.api.PluginHealth.Broken,
        )
    }
}

package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.plugin.api.AuthState
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
 * 真实 `.ext` 的端到端加载验证。
 *
 * 这是整条链路唯一能证明「插件能被装、能实例化、能出数」的测试：
 * - JVM 单测只能验规则（[PluginTrustTest]）
 * - 之前的加载器测试只跑过空目录
 *
 * 用的包是 `:demo-plugin` 模块产出的真实 APK（由 Gradle 复制进 androidTest assets），
 * 不是 mock。因此它同时验证了契约的 `compileOnly` 约定——若插件把 `:plugin-api`
 * 打进自己的 dex，`instance is SourcePlugin` 会失败，这个测试立刻红。
 */
@RunWith(AndroidJUnit4::class)
class PluginLoaderEndToEndTest {

    private lateinit var context: Context
    private val demoArchive = "demo-plugin.ext"

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

    /** 把 assets 里的 demo 插件 APK 落到插件目录，模拟用户放入 .ext */
    private fun installDemoArchive() {
        val apk = InstrumentationRegistry.getInstrumentation().context.assets.open("demo-plugin.ext")
        apk.use { input ->
            val target = File(PluginInstaller.pluginsDir(context), demoArchive)
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test
    fun uninstalled_scanFindsNothing() {
        assertTrue(PluginLoader.scan(context).isEmpty())
        assertTrue(PluginLoader.brokenPlugins().isEmpty())
    }

    @Test
    fun untrustedPlugin_isNotLoaded_butShowsUpAsPending() {
        installDemoArchive()

        // 首次扫描：包在，但用户还没信任，不应加载，也不该被当成坏包
        val loaded = PluginLoader.scan(context)

        assertTrue(loaded.isEmpty())
        assertTrue(PluginLoader.brokenPlugins().isEmpty())

        // 但它必须出现在「待信任」里：否则用户通过文件选择器装完后，
        // 界面上什么都看不到，会以为安装失败了
        val pending = PluginLoader.pendingPlugins()
        assertEquals(1, pending.size)
        val row = pending.first()
        assertEquals("com.lifeboard.plugin.demo", row.packageName)
        assertFalse(row.unsigned)
        assertNotNull("有签名的待信任插件要能被信任", row.identity)
    }

    @Test
    fun trustedPlugin_isLoadedAndCanProduceData() = runBlocking {
        installDemoArchive()
        val info = PluginInstaller.readArchiveInfo(context, File(PluginInstaller.pluginsDir(context), demoArchive))
        assertNotNull(info)
        // demo 插件用 debug 签名，一定有签名
        assertTrue(info!!.isSigned)

        PluginTrustStore.trust(context, info.identity)
        val loaded = PluginLoader.scan(context)

        assertEquals(1, loaded.size)
        val plugin = loaded.first()
        assertEquals("com.lifeboard.plugin.demo", plugin.pluginId)
        assertTrue(plugin.trusted)

        // 两个量纲都要被识别：证明多指标声明可用
        assertEquals(setOf("DEMO_STEPS", "DEMO_SCORE"), plugin.metrics.keys)

        // 真正调用插件产出数据：这一步才证明 DexClassLoader 实例化成功
        val fetchContext = HostPluginContext(plugin.pluginId, context)
        val values = plugin.plugin.fetch(fetchContext, "2026-03-05")

        assertEquals(2, values.size)
        assertEquals(500.0, values.first { it.metricKey == "DEMO_STEPS" }.value, 0.001)
        assertEquals(5.0, values.first { it.metricKey == "DEMO_SCORE" }.value, 0.001)

        // 登录态也要能拿到，否则面板无法展示状态
        assertTrue(plugin.plugin.authState(fetchContext) is AuthState.LoggedIn)
    }

    @Test
    fun revokeTrust_stopsItFromBeingScheduled() = runBlocking {
        installDemoArchive()
        val archive = File(PluginInstaller.pluginsDir(context), demoArchive)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginTrustStore.trust(context, info.identity)
        val plugin = PluginLoader.scan(context).first()

        PluginLoader.markUntrusted(plugin.pluginId)

        // 撤销后登记表里的实例仍在，但不再可调度
        assertFalse(plugin.trusted)
        assertFalse(PluginLoader.isSchedulable(plugin.pluginId))

        // 适配器也必须停止产数，而不是抛异常
        val source = PluginDataSource(plugin, plugin.metrics.getValue("DEMO_STEPS"), context)
        assertTrue(source.collect("2026-03-05").isEmpty())
    }

    @Test
    fun brokenArchive_isReportedWithReason_notSilentlyDropped() {
        // 用户放了个不是 APK 的文件：必须出现在 broken 列表里，否则用户找不到原因
        File(PluginInstaller.pluginsDir(context), "garbage.ext").writeText("not an apk")

        PluginLoader.scan(context)

        val broken = PluginLoader.brokenPlugins()
        assertEquals(1, broken.size)
        assertEquals("garbage.ext", broken.first().archiveName)
        assertTrue(broken.first().reason.isNotBlank())
    }

    @Test
    fun pluginDeclaresCurrentApiVersion() {
        // 插件声明的契约版本必须与宿主一致，否则会被加载器拒绝。
        // demo 插件已改为跟随 PluginApi.VERSION，这里守住这条约定：
        // 写死数字的插件在契约升级时会静默失效（M5 就把 6 个用例一起弄红过）
        installDemoArchive()
        val archive = File(PluginInstaller.pluginsDir(context), demoArchive)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginTrustStore.trust(context, info.identity)

        val loaded = PluginLoader.scan(context)
        assertEquals(1, loaded.size)
        assertEquals(com.lifeboard.plugin.api.PluginApi.VERSION, loaded.first().plugin.apiVersion)
    }
}

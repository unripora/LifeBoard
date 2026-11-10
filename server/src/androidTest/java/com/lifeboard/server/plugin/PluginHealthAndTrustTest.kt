package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.health.SourceHealthChecker
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
 * 插件健康度落库与信任编排的验证。
 *
 * 这两块此前零覆盖：
 * - `SourceHealthChecker.persistPluginHealth` 是把插件 suspend `health()` 结果写进
 *   `source_health` 表的唯一通道；没有它，面板对插件永远显示正常
 * - `PluginManager.trust/revokeTrust` 是用户动作的入口，`revokeTrust` 里
 *   `find(pluginId) ?: return` 的静默返回需要确认不会吞掉真实撤销
 *
 * 用真实 demo 插件的 APK 装载，因为要验证的是「装进来之后健康度能不能落库」，
 * 用假对象就绕过了 [PluginLoader] 这段真正容易出错的代码。
 */
@RunWith(AndroidJUnit4::class)
class PluginHealthAndTrustTest {

    private lateinit var context: Context

    /** assets 里的文件名（`.ext` 只是分发后缀） */
    private val assetName = "demo-plugin.ext"

    /**
     * 落到插件目录时必须用 `.apk` 结尾：
     * `PluginInstaller.listArchives` 只认这个后缀，`.ext` 会被直接过滤掉。
     */
    private val demoArchive = "com.lifeboard.plugin.demo${PluginInstaller.APK_SUFFIX}"

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

    private fun installDemoArchive() {
        val apk = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        apk.use { input ->
            val target = File(PluginInstaller.pluginsDir(context), demoArchive)
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun trustDemoPlugin(): PluginIdentity {
        installDemoArchive()
        val archive = File(PluginInstaller.pluginsDir(context), demoArchive)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!
        PluginManager.trust(context, info.identity)
        return info.identity
    }

    // ---- 健康度落库 ----

    @Test
    fun persistPluginHealth_writesHealthyRowForEachMetric() = runBlocking {
        trustDemoPlugin()

        SourceHealthChecker.persist(context)

        val dao = LifeBoardDatabase.get(context).sourceHealthDao()
        val rows = dao.getAll().associateBy { it.sourceType }

        // demo 插件声明两个指标，必须各有一条独立记录——
        // 这正是「一个指标一个适配器」的目的：按量纲各自记录成败
        val steps = rows["PLUGIN_com.lifeboard.plugin.demo:DEMO_STEPS"]
        val score = rows["PLUGIN_com.lifeboard.plugin.demo:DEMO_SCORE"]

        assertNotNull("步数指标应有健康记录", steps)
        assertNotNull("次数指标应有健康记录", score)
        assertTrue("demo 插件恒为 Healthy", steps!!.healthy)
        assertTrue(steps.lastSuccessAt > 0L)
        assertTrue(score!!.healthy)
    }

    @Test
    fun persistPluginHealth_marksUnhealthy_whenPluginReportsBroken() = runBlocking {
        // 用一个恒定报 Broken 的假插件覆盖加载表，验证「不健康」也能落库。
        // 不能用 demo 插件验证这条：它永远是 Healthy。
        PluginLoader.applyLoaded(
            listOf(
                fakePlugin(
                    id = "com.test.broken",
                    metrics = listOf(MetricSpec("M1", MetricBucket.COUNT, "指标一")),
                    health = { PluginHealth.Broken("接口返回 500") },
                ),
            ),
            brokenList = emptyList(),
        )

        SourceHealthChecker.persist(context)

        val row = LifeBoardDatabase.get(context).sourceHealthDao()
            .getAll()
            .firstOrNull { it.sourceType == "PLUGIN_com.test.broken:M1" }

        assertNotNull("不健康的插件源也必须落库，否则面板看不到异常", row)
        assertFalse(row!!.healthy)
        assertTrue("原因要能显示给用户", row.message.contains("500"))
    }

    @Test
    fun persistPluginHealth_keepsPreviousSuccessTime_whenUnhealthy() = runBlocking {
        PluginLoader.applyLoaded(
            listOf(
                fakePlugin(
                    id = "com.test.regress",
                    metrics = listOf(MetricSpec("M1", MetricBucket.COUNT, "指标一")),
                    health = { PluginHealth.Broken("挂了") },
                ),
            ),
            brokenList = emptyList(),
        )
        val dao = LifeBoardDatabase.get(context).sourceHealthDao()
        val key = "PLUGIN_com.test.regress:M1"

        // 先写入一次成功时间，再让插件报不健康，旧时间应被保留
        runBlocking {
            dao.upsert(
                com.lifeboard.server.db.SourceHealthEntity(
                    sourceType = key,
                    healthy = true,
                    lastSuccessAt = 12345L,
                    message = "曾成功",
                ),
            )
        }
        SourceHealthChecker.persist(context)

        val row = dao.getAll().first { it.sourceType == key }
        assertFalse(row.healthy)
        assertEquals("失败时不能把上次成功时间冲掉", 12345L, row.lastSuccessAt)
    }

    @Test
    fun persistPluginHealth_survivesPluginThrowing() = runBlocking {
        PluginLoader.applyLoaded(
            listOf(
                fakePlugin(
                    id = "com.test.thrower",
                    metrics = listOf(MetricSpec("M1", MetricBucket.COUNT, "指标一")),
                    health = { throw IllegalStateException("插件内部崩了") },
                ),
            ),
            brokenList = emptyList(),
        )

        // 插件抛异常不能把整次 persist 打挂，否则其他源的记录全丢
        SourceHealthChecker.persist(context)

        // 异常在 PluginDataSource.health 里就被翻译成 Broken，而不是让记录缺失：
        // 面板上要能看到「健康检查异常：插件内部崩了」，
        // 而不是该源凭空消失、用户以为插件没装上
        val row = LifeBoardDatabase.get(context).sourceHealthDao()
            .getAll()
            .firstOrNull { it.sourceType == "PLUGIN_com.test.thrower:M1" }

        assertNotNull("抛异常的源也要落库，否则面板看不到异常", row)
        assertFalse(row!!.healthy)
        assertTrue("原因要能显示给用户", row.message.contains("插件内部崩了"))
    }

    // ---- 信任编排 ----

    @Test
    fun trust_makesPluginImmediatelySchedulable() = runBlocking {
        installDemoArchive()
        // 安装后未信任：扫描不加载
        assertTrue(PluginManager.refresh(context).isEmpty())

        val archive = File(PluginInstaller.pluginsDir(context), demoArchive)
        val info = PluginInstaller.readArchiveInfo(context, archive)!!

        // trust 应当连带刷新，用户点完信任立刻可用，不必等下次启动
        PluginManager.trust(context, info.identity)

        assertEquals(1, PluginManager.loaded().size)
        // demo 插件声明的 id 与包名一致
        assertTrue(PluginLoader.isSchedulable("com.lifeboard.plugin.demo"))
    }

    @Test
    fun revokeTrust_stopsScheduling() = runBlocking {
        trustDemoPlugin()
        val pluginId = PluginManager.loaded().first().pluginId
        assertTrue(PluginLoader.isSchedulable(pluginId))

        PluginManager.revokeTrust(context, pluginId)

        assertFalse(PluginLoader.isSchedulable(pluginId))
        // 实例仍在列表里，只是不可调度：面板要能显示「已撤销」而不是凭空消失
        assertEquals(1, PluginManager.loaded().size)
        assertFalse(PluginManager.loaded().first().trusted)
    }

    @Test
    fun revokeTrust_unknownPlugin_doesNotThrow() {
        // UI 可能在插件已被移除后仍触发撤销，不能因此崩溃
        PluginManager.revokeTrust(context, "com.not.installed")
    }

    @Test
    fun revokeTrust_onlyAffectsTheNamedPlugin() = runBlocking {
        trustDemoPlugin()
        PluginLoader.applyLoaded(
            PluginManager.loaded() + fakePlugin(
                id = "com.test.other",
                metrics = listOf(MetricSpec("M1", MetricBucket.COUNT, "指标一")),
                health = { PluginHealth.Healthy },
            ),
            brokenList = emptyList(),
        )
        PluginLoader.markUntrusted("com.lifeboard.plugin.demo")

        // 另一个插件不应受牵连
        assertTrue(PluginLoader.isSchedulable("com.test.other"))
    }

    // ---- 辅助 ----

    /**
     * 构造一个不经过 dex 加载的假插件，只用于健康度这类不依赖真实 APK 的断言。
     *
     * 它仍然走 [LoadedPlugin] 与 [PluginDataSource] 的正常路径，
     * 所以验证的是宿主自己的映射与落库逻辑，而不是插件的实现。
     */
    private fun fakePlugin(
        id: String,
        metrics: List<MetricSpec>,
        health: suspend () -> PluginHealth,
    ): LoadedPlugin {
        val plugin = object : SourcePlugin {
            override val id: String = id
            override val displayName: String = "假插件 $id"
            override val apiVersion: Int = com.lifeboard.plugin.api.PluginApi.VERSION
            override val credentialSpec: CredentialSpec = CredentialSpec(emptyList())
            override val metrics: List<MetricSpec> = metrics
            override suspend fun login(
                context: PluginContext,
                inputs: Map<String, String>,
            ): LoginResult = LoginResult.Failure("不支持登录")
            override suspend fun logout(context: PluginContext) = Unit
            override suspend fun authState(context: PluginContext): AuthState =
                AuthState.LoggedIn("测试账号")
            override suspend fun fetch(context: PluginContext, date: String): List<PluginMetric> =
                metrics.map { PluginMetric(it.metricKey, 1.0) }
            override suspend fun health(context: PluginContext): PluginHealth = health()
        }
        val archive = PluginArchiveInfo(
            packageName = id,
            versionCode = 1L,
            versionName = "1.0",
            signatureHashes = listOf("FAKE"),
            pluginId = id,
        )
        return LoadedPlugin(archive = archive, archiveName = "$id.ext", plugin = plugin)
    }
}

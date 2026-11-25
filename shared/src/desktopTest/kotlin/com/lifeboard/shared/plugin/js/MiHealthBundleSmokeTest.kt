package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.RawRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 把**真实的构建产物** `js-plugins/mihealth/main.js` 加载进 QuickJS 跑一遍。
 *
 * ## 为什么还要这一层
 *
 * `npm run verify:mihealth` 已经用 Node 桩验过产物能加载、能导出函数。
 * 但 Node 和 QuickJS 是两个引擎，差异恰好落在最容易出事的地方：
 *
 * - **语法支持**：esbuild 的 `--target=es2020` 是「尽量降级」，若产物里
 *   有 QuickJS 不支持的语法，Node 完全没问题，QuickJS 直接语法错误。
 *   这类失败发生在 `runtime.start`，是整包加载失败，不是某个函数报错。
 * - **全局对象**：Node 里 `URL` / `Buffer` / `TextEncoder` 都是现成的，真机上没有。
 *   TS 声明能挡住大部分，但打包后若被 esbuild 注入了辅助代码用到它们，
 *   只有真引擎能发现。
 * - **模块解析**：宿主按包内相对路径解析 ESM，与 Node 的规则不同。
 *
 * 这三条都是「本地全绿、装到手机上白屏」的典型成因。
 *
 * ## 不验证什么
 *
 * 不发真实网络请求、不验证小米接口行为——那需要有效凭据，属于手动功能验证
 * （见插件编写指南第八章）。这里只保证「产物在真引擎里能加载、能调通、
 * 返回结构合法」。
 */
class MiHealthBundleSmokeTest {

    private lateinit var tempDir: File
    private lateinit var fakeContext: MinimalPluginContext
    private var runtime: JsPluginRuntime? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mihealth-bundle").toFile()
        fakeContext = MinimalPluginContext(
            pluginId = "com.lifeboard.plugin.mihealth",
            dir = File(tempDir, "data").apply { mkdirs() },
        )
    }

    @After
    fun tearDown() {
        runtime?.close()
        tempDir.deleteRecursively()
    }

    private fun resolveBundle(): File {
        val candidates = listOf(
            "js-plugins/mihealth/main.js",
            "../js-plugins/mihealth/main.js",
            "../../js-plugins/mihealth/main.js",
        )
        candidates.forEach { if (File(it).exists()) return File(it) }

        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val f = dir?.resolve("js-plugins/mihealth/main.js")
            if (f != null && f.exists()) return f
            dir = dir?.parentFile
        }
        error(
            "找不到 js-plugins/mihealth/main.js。" +
                "请先在 js-plugins 目录执行 npm run build:mihealth。" +
                "当前工作目录=${System.getProperty("user.dir")}"
        )
    }

    private fun buildPlugin(): JsSourcePlugin {
        val rt = JsPluginRuntime.create("mihealth-smoke")
        runtime = rt
        val bridgeRef = arrayOfNulls<JsPluginContextBridge>(1)

        val started = rt.start(
            modules = mapOf("main.js" to resolveBundle().readText()),
            entry = "main.js",
        ) { _ ->
            val bridge = JsPluginContextBridge(
                contextProvider = { fakeContext },
                pluginIdProvider = { "com.lifeboard.plugin.mihealth" },
            )
            bridgeRef[0] = bridge
            CompositeHostDispatcher(listOf(bridge.functionNames() to bridge))
        }

        assertTrue(
            "产物在 QuickJS 里启动失败（语法或模块解析问题）：" +
                "${started.exceptionOrNull()?.message}",
            started.isSuccess,
        )

        return JsSourcePlugin(
            id = "com.lifeboard.plugin.mihealth",
            displayName = "小米运动健康",
            credentialSpec = CredentialSpec.NONE,
            metrics = listOf(MetricSpec("STEPS", MetricBucket.STEPS, "步数")),
            runtime = rt,
            entryModule = "main.js",
            contextBridge = bridgeRef[0]!!,
            bindContext = { },
        )
    }

    @Test
    fun `构建产物能在 QuickJS 中启动并跑通无需网络的契约方法`() {
        val plugin = buildPlugin()

        // authState 会读凭据；没有凭据时应返回 loggedOut，
        // 这条同时验证 ctx.credentials.get 在真引擎里可调用
        val auth = runBlocking { plugin.authState(fakeContext) }
        assertTrue("无凭据时 authState 应为 LoggedOut，实际：$auth", auth is AuthState.LoggedOut)

        // detailUi 走 recentMetrics + 拼装节点树，覆盖最容易在打包后出错的对象构造
        val ui = runBlocking { plugin.detailUi(fakeContext) }
        assertTrue("detailUi 不应为空，实际 $ui", ui != null)
        // 插件返回的是平铺数组，解析层会包一层不套卡片的 column
        val column = ui as? ColumnNode
        assertTrue("detailUi 根应是 ColumnNode，实际：${ui!!::class.simpleName}", column != null)
        assertTrue("detailUi 根不应为空列表", column!!.children.isNotEmpty())
        assertTrue(
            "detailUi 第一块应是状态卡，实际：${column.children.first()}",
            column.children.first().toString().contains("StatusCard"),
        )

        // health 在无凭据时返回 broken，不触网
        val health = runBlocking { plugin.health(fakeContext) }
        assertTrue(
            "无凭据时 health 应为 Broken，实际：$health",
            health.toString().contains("Broken"),
        )
    }

    @Test
    fun `构建产物在无凭据时 fetch 明确上报需要登录而不是返回空数组`() {
        val plugin = buildPlugin()

        // 无凭据时必须返回 authRequired，不能返回空数组。
        //
        // 空数组在宿主看来是「今天确实没数据」，用户会去怀疑手环没戴；
        // 而真实原因是没登录。这个区别是契约里 authRequired 存在的意义，
        // 也是线上排查「同步 0 条」时最容易误导人的地方。
        val result = runBlocking { plugin.fetch(fakeContext, "2026-11-18") }
        assertTrue(
            "无凭据时 fetch 应返回 AuthenticationRequired，实际：$result",
            result is com.lifeboard.plugin.api.FetchResult.AuthenticationRequired,
        )
    }
}

/**
 * 测试用的最小 [PluginContext]。
 *
 * 只实现本测试会走到的通道：凭据（内存）与历史指标（内存）。
 * 文件通道走真实磁盘，因为 deviceId 的读写会用到它。
 */
private class MinimalPluginContext(
    override val pluginId: String,
    val dir: File,
) : PluginContext {

    private val credentialMap = mutableMapOf<String, String>()

    override val credentials = object : CredentialStore {
        override suspend fun get(slot: String): String? = credentialMap[slot]
        override suspend fun put(slot: String, value: String) {
            credentialMap[slot] = value
        }
        override suspend fun remove(slot: String) {
            credentialMap.remove(slot)
        }
        override suspend fun clear() {
            credentialMap.clear()
        }
    }

    override val filesDirPath: String = dir.absolutePath

    override suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample> = emptyList()

    override suspend fun reportRawRecords(
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) = Unit
}

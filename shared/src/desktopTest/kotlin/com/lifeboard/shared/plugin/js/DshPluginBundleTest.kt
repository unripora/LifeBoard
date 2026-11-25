package com.lifeboard.shared.plugin.js

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 把真实产物 `js-plugins/dsh/main.js` 装进 QuickJS 跑自检。
 *
 * ## 为什么走真引擎
 *
 * Node 自带 `TextEncoder`、`Buffer`、`atob`，而这些恰好是插件需要 polyfill
 * 的部分——在 Node 里跑绿证明不了手机上能跑。这里的唯一真相是：
 * 产物能不能在 QuickJS 里加载并算对。
 *
 * ## 覆盖范围
 *
 * 换轨到 dsh-mobile 之后，插件不再有 Noise 握手、控制帧、加密 RPC。
 * 现在的协议层只有三块，各自有一条自检：
 *
 * 1. **网关地址规范化**：末尾斜杠、带路径、http、带凭据等边界。
 *    地址错一个字符就 403，而 403 本身什么也不说。
 * 2. **mux 帧解析**：`item`/`end`/`error` 三种下行帧，以及各类畸形帧必须被拒。
 *    网关用 `exactKeys` 校验，我们收帧同样严格，否则症状是消息内容错乱。
 * 3. **契约出口完整性**：宿主调用的十个导出一个都不能少。
 *
 * 会话与配对逻辑依赖真实网关，由 [GatewayLiveTest] 覆盖。
 */
class DshPluginBundleTest {

    private var runtime: JsPluginRuntime? = null

    @After
    fun tearDown() {
        runtime?.close()
    }

    private fun resolve(relative: String): File {
        listOf(".", "..", "../..").forEach {
            val f = File(it).resolve(relative)
            if (f.exists()) return f
        }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val f = dir?.resolve(relative)
            if (f != null && f.exists()) return f
            dir = dir?.parentFile
        }
        error("找不到 $relative，请先在 js-plugins 目录执行 npm run build:dsh")
    }

    private fun buildRuntime(): JsPluginRuntime {
        val rt = JsPluginRuntime.create("dsh-selftest")
        runtime = rt
        val crypto = JsCryptoBridge()

        // 三条自检都不碰网络与凭据，只装 crypto 即可：
        // 多装 HTTP / WS 会让用例看起来覆盖了实际没跑的部分
        val started = rt.start(
            modules = mapOf("main.js" to resolve("js-plugins/dsh/main.js").readText()),
            entry = "main.js",
        ) { CompositeHostDispatcher(listOf(crypto.functionNames() to crypto)) }

        assertTrue(
            "产物在 QuickJS 里启动失败（polyfill 或语法问题）：${started.exceptionOrNull()?.message}",
            started.isSuccess,
        )
        return rt
    }

    /** 调插件导出的函数；参数走临时文件，避免拼命令串时的转义问题 */
    private fun call(rt: JsPluginRuntime, function: String, args: List<String>): String {
        val file = Files.createTempFile("dsh-args", ".json").toFile()
        try {
            file.writeText(args.joinToString(",", "[", "]") { jsonString(it) })
            return rt.runOnRuntime { r ->
                r.callFunction(
                    moduleName = "main.js",
                    functionName = function,
                    argsJson = file.readText(),
                    callKey = "dsh#$function#1",
                )
            }
        } finally {
            file.delete()
        }
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.append("\"").toString()
    }

    /**
     * 网关地址规范化。
     *
     * `https://host:port` 是唯一合法形态：末尾斜杠会被去掉，但带路径、
     * 带凭据、非 https、缺 scheme 一律拒绝。
     */
    @Test
    fun `网关地址规范化自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "gatewaySelfTest", emptyList())
        assertTrue("网关地址自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * mux 下行帧解析。
     *
     * 覆盖 `item`/`end`/`error` 的正常解析，以及非 JSON、非对象、缺 `streamId`、
     * 空 `streamId`、多余字段、未知 `type` 必须被拒。
     */
    @Test
    fun `mux 帧解析自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "muxSelfTest", emptyList())
        assertTrue("mux 帧解析自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 契约出口完整性。
     *
     * 少一个导出的表现是 `undefined is not a function`，报错点在业务代码中间，
     * 离真正原因很远——所以每个导出都单独断言一次。
     */
    @Test
    fun `契约出口自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "contractSelfTest", emptyList())
        assertTrue("契约出口自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 未配对时的详情页必须给出可行动的提示，而不是空白。
     *
     * 这条走真 `detailUi()`：没有凭据槽位时应返回「还没有连接电脑」的说明，
     * 让用户知道下一步该去配对。
     */
    @Test
    fun `未配对时详情页提示去配对`() {
        val rt = buildContractRuntime()
        val out = call(rt, "detailUi", emptyList())
        assertTrue("未配对时应提示去配对，实际：$out", out.contains("还没有连接电脑"))
    }

    /** 未配对时 `authState` 必须是 `loggedOut`，不能凭空报已登录 */
    @Test
    fun `未配对时 authState 为 loggedOut`() {
        val rt = buildContractRuntime()
        val out = call(rt, "authState", emptyList())
        assertTrue("未配对应为 loggedOut，实际：$out", out.contains("loggedOut"))
    }

    /**
     * 契约出口测试用的最小上下文。
     *
     * `detailUi` / `authState` 都会读凭据槽位，没有桥时 `ctx.credentials`
     * 是 undefined，报出来的是「cannot read property 'get' of undefined」——
     * 那是脚手架的问题，不是插件的问题。
     */
    private class InMemoryCredentials : com.lifeboard.plugin.api.CredentialStore {
        private val slots = mutableMapOf<String, String>()

        override suspend fun get(slot: String): String? = slots[slot]

        override suspend fun put(slot: String, value: String) {
            slots[slot] = value
        }

        override suspend fun remove(slot: String) {
            slots.remove(slot)
        }

        override suspend fun clear() {
            slots.clear()
        }
    }

    private fun buildContractRuntime(): JsPluginRuntime {
        val rt = JsPluginRuntime.create("dsh-contract")
        runtime = rt
        val crypto = JsCryptoBridge()
        val credentials = InMemoryCredentials()
        // 只需凭据：未配对路径不碰网络，也不会走到 TLS 与 WS
        val context = object : com.lifeboard.plugin.api.PluginContext {
            override val pluginId: String = "com.lifeboard.plugin.dsh"
            override val credentials: com.lifeboard.plugin.api.CredentialStore = credentials
            override val filesDirPath: String = Files.createTempDirectory("dsh-files").toString()

            override suspend fun recentMetrics(
                metricKey: String,
                days: Int,
            ): List<com.lifeboard.plugin.api.MetricSample> = emptyList()
        }
        val contextBridge = JsPluginContextBridge(
            contextProvider = { context },
            pluginIdProvider = { context.pluginId },
        )
        val sessionBridge = JsSessionBridge { }

        val started = rt.start(
            modules = mapOf("main.js" to resolve("js-plugins/dsh/main.js").readText()),
            entry = "main.js",
        ) {
            CompositeHostDispatcher(
                listOf(
                    crypto.functionNames() to crypto,
                    contextBridge.functionNames() to contextBridge,
                    sessionBridge.functionNames() to sessionBridge,
                ),
            )
        }
        assertTrue(
            "产物在 QuickJS 里启动失败：${started.exceptionOrNull()?.message}",
            started.isSuccess,
        )
        return rt
    }
}

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
 * Node 自带 `TextEncoder`、`crypto.getRandomValues`、`Buffer`，而这些恰好是本插件
 * 需要 polyfill 的部分——在 Node 里跑绿证明不了手机上能跑。
 * 这里的 Swift 之外只有一个真相：产物能不能在 QuickJS 里加载并算对。
 *
 * ## 覆盖范围
 *
 * 1. **Noise IK 握手**：对上游官方向量逐字节比对（静态密钥、临时密钥、prologue、
 *    两条握手消息、传输帧密文）。
 * 2. **设备指纹**：与宿主 `MessageDigest.SHA-256` 现算值比对，不写死常量。
 * 3. **协议层**：帧封装往返、四类非法入站帧必须被拒、`hello.ack` 上限取小值、
 *    `secure.handshake` 的 step 校验、Server 地址规范化与刷新窗口。
 * 4. **Control 状态机**：首帧约束、心跳回 pong、帧分发、不支持类型不断连、关闭后禁写。
 * 5. **业务消息**：RPC/Event 信封编解码、入站分类、错误字段保留、`RpcPending` 挂起表。
 * 6. **会话**：`harness.remote.call` 参数层级、返回值拆壳、列表容错与标题回退。
 * 7. **流式对话**：Prompt 请求、`stream.open/close` 参数、事件帧校验、增量文本聚合。
 * 8. **审批与问答**：`$events` 帧分支、审批取值、`$events/result` 回复与本地拒绝。
 * 9. **断线恢复**：退避序列、连接代次、恢复动作顺序与未知结果处理。
 * 10. **主机与工作区**：Host 过滤与容错、`workspace/follow` 基线解析。
 * 11. **会话编排**：prompt 在途、流帧路由、审批/问答回复、断线恢复计划。
 *
 * 向量文件 `js-plugins/dsh/testdata/noise-ik.json` 原样拷自
 * `liguobao/ds-harness-remote` 的 `fixtures/crypto/v1/`（MIT，提交 eb907b2），
 * 未做任何改动，避免「本地改到能过」。
 */
class DshPluginBundleTest {

    private var runtime: JsPluginRuntime? = null

    /** 契约出口测试里插件推给界面的会话事件 */
    private val emitted = mutableListOf<com.lifeboard.plugin.api.SessionUiEvent>()

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

        // 只装密码桥：产物顶层只用到 ctx.codec 与 ctx.crypto，
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

    /** 调插件导出的函数，参数走临时文件（向量里含引号与换行，拼进命令串容易转义出错） */
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

    @Test
    fun `上游 Noise 向量逐字节一致`() {
        val rt = buildRuntime()
        val out = call(rt, "selfTest", listOf(resolve("js-plugins/dsh/testdata/noise-ik.json").readText()))
        assertTrue("自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 指纹与宿主 SHA-256 一致。
     *
     * 期望值由 Kotlin 现算：两端算法若有一处走偏（截断字节数、大小写、密钥解码方式）
     * 会立刻不一致，而不需要维护一个常量。这条直接影响配对界面「两边指纹是否相同」，
     * 错一个字符就是永远对不上。
     */
    @Test
    fun `设备指纹与宿主SHA256前六字节一致`() {
        val rt = buildRuntime()
        val publicKey = "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8"
        val out = call(rt, "fingerprintSelfTest", listOf(publicKey))

        val digest = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(java.util.Base64.getUrlDecoder().decode(publicKey))
        val expected = digest.take(6).joinToString("") { String.format("%02X", it) }

        assertTrue("指纹自检失败：$out", out.contains("\"ok\":true"))
        assertTrue("指纹应为 $expected，实际：$out", out.contains(expected))
    }

    @Test
    fun `协议层自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "protocolSelfTest", emptyList())
        assertTrue("协议层自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * Control 状态机不碰 `ctx.ws`，所以这里只装密码桥也能跑。
     *
     * 覆盖首帧必须为 `hello.ack`、心跳回 pong 且带回 nonce、各帧分发、
     * 未知类型忽略而不断连、非法 payload 抛错、关闭后禁止写帧。
     */
    @Test
    fun `Control 状态机自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "controlSelfTest", emptyList())
        assertTrue("Control 状态机自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 业务消息编解码。
     *
     * 覆盖信封校验、四种消息分类（response / error / event / 反向 request）、
     * 错误码与 retryable 保留、体积上限、挂起请求表的登记与摘取。
     */
    @Test
    fun `RPC 消息编解码自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "rpcSelfTest", emptyList())
        assertTrue("RPC 消息编解码自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 会话列表与新建。
     *
     * 覆盖 `harness.remote.call` 的 params 层级、`{ok,value}` 拆壳、
     * 失败外壳与业务错误区分、列表逐条容错与标题回退。
     */
    @Test
    fun `会话列表与新建自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "sessionsSelfTest", emptyList())
        assertTrue("会话列表与新建自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 发送 Prompt 与流式回复。
     *
     * 覆盖 `session/prompt` 的三层参数、`stream.open/close` 的扁平参数、
     * `harness.remote.frame` / `stream.closed` 的字段校验，
     * 以及助手增量聚合（文本与推理分开、忽略快照与工具调用）。
     */
    @Test
    fun `发送Prompt与流式回复自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "streamSelfTest", emptyList())
        assertTrue("发送 Prompt 与流式回复自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 审批与用户问答。
     *
     * 覆盖 `$events` 的 ready / waterfall / cancel 分支、审批与问答的字段校验，
     * 以及 `$events/result` 的三种 outcome（result / rejected）。
     */
    @Test
    fun `审批与用户问答自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "interactionsSelfTest", emptyList())
        assertTrue("审批与用户问答自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 断线重连与状态恢复。
     *
     * 覆盖指数退避与封顶、非法 attempt 归一、连接代次失效判断，
     * 以及恢复动作的顺序（失败未知请求 → 刷新列表 → 重建流 → 清审批残留）。
     */
    @Test
    fun `断线重连与状态恢复自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "recoverySelfTest", emptyList())
        assertTrue("断线重连与状态恢复自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 主机与工作区发现。
     *
     * 覆盖 `/api/v1/devices` 的 host 过滤与坏条目丢弃，
     * 以及 `workspace/follow` 基线的字段容错。
     */
    @Test
    fun `主机与工作区发现自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "discoverySelfTest", emptyList())
        assertTrue("主机与工作区发现自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 会话编排。
     *
     * 用离线文本帧驱动 `ConversationController`，覆盖 prompt 在途状态、
     * 会话流与 `$events` 流的分流、审批/问答回复与断线恢复计划。
     */
    @Test
    fun `会话编排自检全通过`() {
        val rt = buildRuntime()
        val out = call(rt, "conversationSelfTest", emptyList())
        assertTrue("会话编排自检失败：$out", out.contains("\"ok\":true"))
    }

    /**
     * 契约出口测试用的最小上下文。
     *
     * `detailUi` / `authState` / `sessionStart` 都会读凭据槽位，没有桥时
     * `ctx.credentials` 是 undefined，报出来的是「cannot read property
     * 'get' of undefined」——那是脚手架的问题，不是插件的问题。
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
        // 只需凭据与会话推送：未登录路径不碰网络，也不会走到加密与 WS
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
        val sessionBridge = JsSessionBridge { event -> emitted += event }

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

    /**
     * 插件的契约出口必须真的存在。
     *
     * 宿主调用的是**入口模块**的具名导出；`contract.ts` 里的实现如果忘了
     * 在 `main.ts` 再导出，详情页连区块都拿不到，而自检全绿也发现不了。
     * 这里逐个调用，缺一个就报出是哪个。
     */
    @Test
    fun `契约出口全部可用`() {
        val rt = buildContractRuntime()

        // 未登录时也必须返回三块里的至少「未登录提示」而不是抛错
        val blocks = call(rt, "detailUi", emptyList())
        assertTrue("detailUi 返回值无法解析：$blocks", blocks.trim().startsWith("["))

        val auth = call(rt, "authState", emptyList())
        assertTrue("authState 未导出：$auth", auth.contains("state"))

        val health = call(rt, "health", emptyList())
        assertTrue("health 未导出：$health", health.contains("state"))

        val metrics = call(rt, "fetch", emptyList())
        assertTrue("fetch 应返回空数组：$metrics", metrics.trim() == "[]")

        // 没有账号时 sessionStart 必须返回 false 而不是抛异常
        val started = call(rt, "sessionStart", emptyList())
        assertTrue("未登录时 sessionStart 应为 false：$started", started.trim() == "false")
    }

    /** 把任意文本包成 JSON 字符串字面量（含引号） */
    private fun jsonString(raw: String): String {
        val sb = StringBuilder("\"")
        raw.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

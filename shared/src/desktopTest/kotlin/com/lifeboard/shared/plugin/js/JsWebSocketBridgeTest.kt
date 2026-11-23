package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JsWebSocketBridge] 的端到端测试。
 *
 * 跑在真实 QuickJS 运行时与真实 WS 服务端（OkHttp MockWebServer）之间，
 * 覆盖：握手 → 文本/二进制帧 → 主动发送 → 对端关闭 → 终态帧 → 参数校验。
 *
 * ## 为什么不停留在「构造桥然后断言返回字符串」
 *
 * 桥的真正风险不在 `invoke` 的分支，而在**跨线程回灌**：
 * OkHttp 回调线程 → [JsPluginRuntime.post] → 插件线程。R1 结论里这条链
 * 一旦接错，表现是「帧偶尔丢」或「await 永远不醒」，静态看代码看不出来。
 * 所以断言全部从「帧真的到了插件侧」出发。
 *
 * 调用一律经 [JsPluginRuntime.runOnRuntime]：既符合「运行时只在专用线程用」
 * 的约束，也顺带给了我们一个 FIFO 屏障——上一次回灌投递的 block 一定先跑完。
 */
class JsWebSocketBridgeTest {

    private val server = MockWebServer()
    private val runtime = JsPluginRuntime.create("test-ws")
    private val seq = AtomicInteger(0)
    private lateinit var bridge: JsWebSocketBridge

    @BeforeTest
    fun setUp() {
        server.start()
        bridge = JsWebSocketBridge(
            runtime = runtime,
            tokenProvider = { "ws#${seq.incrementAndGet()}" },
        )
        val started = runtime.start(
            modules = mapOf("main.js" to "globalThis.__ready = true"),
            entry = "main.js",
        ) { rt -> CompositeHostDispatcher(listOf(bridge.functionNames() to bridge)) }
        assertTrue(started.isSuccess, "运行时启动失败：${started.exceptionOrNull()}")
    }

    @AfterTest
    fun tearDown() {
        runCatching { bridge.cancelAll() }
        runCatching { runtime.close() }
        runCatching { server.shutdown() }
    }

    @Test
    fun `文本与二进制帧按序到达，对端关闭给出终态帧`() {
        val peer = Recorder()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))

        val id = connect()
        assertEquals("open", stateOf(id).optString("state"))
        assertEquals(0, stateOf(id).optInt("queued", -1))

        // ---- 文本帧 ----
        awaitPeer(peer)
        val peerSocket = peer.socket ?: error("服务端 WebSocket 未建立")
        peerSocket.send("hello")
        waitQueued(id, 1)
        val text = PluginJsonObject(recv(id))
        assertEquals("text", text.optString("type"))
        assertEquals("hello", text.optString("data"))

        // ---- 二进制帧：字节按无符号值下发 ----
        peerSocket.send(ByteString.of(1, 2, -1))
        waitQueued(id, 1)
        val binary = PluginJsonObject(recv(id))
        assertEquals("binary", binary.optString("type"))
        val bytes = binary.optJSONArray("bytes") ?: error("二进制帧缺少 bytes 字段")
        assertEquals(3, bytes.length())
        assertEquals(1, bytes.optInt(0, -1))
        assertEquals(255, bytes.optInt(2, -1))

        // ---- 插件主动发送 ----
        assertEquals("null", call("ctx.ws.send", arrayOf<Any>(id, "ping")))
        waitUntil("服务端未收到插件发送的文本帧") { peer.texts.contains("ping") }

        // ---- 越界字节必须当场拒绝，不能悄悄发出去 ----
        assertTrue(
            call("ctx.ws.sendBinary", "[${quote(id)}, [0, 256]]").startsWith("<error:"),
            "越界字节应被拒绝",
        )

        // ---- 对端关闭：插件下一次 recv 拿到 close 帧 ----
        peerSocket.close(1000, "bye")
        waitUntil("连接未进入 closed 状态") { stateOf(id).optString("state") == "closed" }
        val closed = PluginJsonObject(recv(id))
        assertEquals("close", closed.optString("type"))
        assertEquals(1000, closed.optInt("code", -1))
        assertEquals("bye", closed.optString("reason"))
        assertEquals(true, closed.optBoolean("clean", false))

        // 终态帧消费完之后不再产出帧，且给出可读原因
        assertTrue(call("ctx.ws.recv", "[${quote(id)}]").contains("连接已关闭"))
    }

    @Test
    fun `非法关闭码与超长原因被拒绝，合法关闭码放行`() {
        val peer = Recorder()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val id = connect()
        awaitPeer(peer)

        // 1001/2999 是协议保留或不允许应用主动发的区间
        assertTrue(call("ctx.ws.close", "[${quote(id)}, 1001]").startsWith("<error:"))
        assertTrue(call("ctx.ws.close", "[${quote(id)}, 2999]").startsWith("<error:"))

        val longReason = "x".repeat(124)
        assertTrue(
            call("ctx.ws.close", arrayOf<Any>(id, 3005, longReason)).startsWith("<error:"),
            "reason 超过 123 字节应被拒绝",
        )

        assertEquals("null", call("ctx.ws.close", arrayOf<Any>(id, 3005, "ok")))
        waitUntil("主动关闭后连接未进入终态") { stateOf(id).optString("state") == "closed" }
    }

    @Test
    fun `未知连接与未就绪的发送都给出明确错误`() {
        assertTrue(call("ctx.ws.recv", "[\"nope\"]").contains("未知连接"))
        assertTrue(call("ctx.ws.send", "[\"nope\", \"x\"]").contains("未知连接"))
        assertTrue(call("ctx.ws.state", "[\"nope\"]").contains("未知连接"))
    }

    /**
     * 消费慢于推送时按连接丢最旧帧，并计数。
     *
     * 这条直接对应 M1 的上界决策：**丢帧可以，丢 Promise 不行**。
     * 端口上灌的帧数超过 [MAX_QUEUED_FRAMES] 后，`queued` 不再增长，
     * `dropped` 开始计数。
     */
    @Test
    fun `待消费帧超过上界后丢弃最旧帧并计数`() {
        val peer = Recorder()
        server.enqueue(MockResponse().withWebSocketUpgrade(peer))
        val id = connect()
        awaitPeer(peer)
        val peerSocket = peer.socket ?: error("服务端 WebSocket 未建立")

        repeat(600) { peerSocket.send("f$it") }
        waitUntil("未观察到丢帧；上界策略可能没生效", timeoutMs = 10_000) {
            stateOf(id).optInt("dropped", 0) > 0
        }
        val state = stateOf(id)
        assertTrue(state.optInt("queued", 0) <= 512, "队列长度超过上界：${state.optInt("queued", 0)}")
    }

    // ---------- 辅助 ----------

    private fun connect(): String {
        val raw = call("ctx.ws.connect", "[\"${wsUrl()}\"]")
        assertTrue(raw.startsWith("<pending:"), "connect 应返回挂起 token，实际：$raw")
        // 握手是异步的，等到状态变 open 再继续
        waitUntil("握手未完成") { rawIdOfOpenConnection() != null }
        return rawIdOfOpenConnection()!!
    }

    /**
     * connect 的 id 与 token 都由同一个 tokenProvider 产生（先是 id，后是 token），
     * 因此打开状态可从「哪条连接处于 open」反查。
     */
    private fun rawIdOfOpenConnection(): String? {
        for (i in 1..seq.get()) {
            val candidate = "ws#$i"
            val raw = runCatching { call("ctx.ws.state", "[\"$candidate\"]") }.getOrNull() ?: continue
            if (raw.startsWith("<error:")) continue
            if (PluginJsonObject(raw).optString("state") == "open") return candidate
        }
        return null
    }

    private fun stateOf(id: String): PluginJsonObject =
        PluginJsonObject(call("ctx.ws.state", "[${quote(id)}]"))

    private fun recv(id: String): String {
        val raw = call("ctx.ws.recv", "[${quote(id)}]")
        assertTrue(!raw.startsWith("<pending"), "recv 意外挂起：$raw")
        assertTrue(!raw.startsWith("<error:"), "recv 返回错误：$raw")
        return raw
    }

    private fun call(name: String, argsJson: String = "[]"): String =
        runtime.runOnRuntime { bridge.invoke(name, argsJson) }

    private fun call(name: String, args: Array<Any>): String {
        val arr = PluginJsonArray()
        args.forEach { arg ->
            when (arg) {
                is String -> arr.put(arg)
                is Int -> arr.put(arg)
                else -> error("测试辅助不支持的参数类型：${arg::class}")
            }
        }
        return call(name, arr.toString())
    }

    private fun wsUrl(): String = "ws://${server.hostName}:${server.port}/socket"

    private fun quote(value: String): String = "\"$value\""

    private fun awaitPeer(peer: Recorder) {
        waitUntil("服务端 WebSocket 未建立") { peer.socket != null }
    }

    private fun waitQueued(id: String, atLeast: Int) {
        waitUntil("队列里没有出现期待的帧") { stateOf(id).optInt("queued", 0) >= atLeast }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error(message)
    }

    /** 记录服务端视角收到的内容。 */
    private class Recorder : WebSocketListener() {
        val texts = CopyOnWriteArrayList<String>()
        val binaries = CopyOnWriteArrayList<ByteString>()

        @Volatile
        var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            texts += text
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binaries += bytes
        }

        /**
         * 回对端发来的 close 帧。
         *
         * OkHttp 的 RealWebSocket **不会自动回** close：收到对端的 close 只会回调
         * `onClosing`，必须由监听器自己再调一次 `close()`，握手才算完成。
         * 不回的话发起方会一直停在 CLOSING，直到 OkHttp 内部的
         * `cancelAfterCloseMillis`（默认 60s）超时把它 cancel 掉。
         * 真实服务端都会回，这里的桩也必须回，否则测的是桩的缺陷。
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }
}
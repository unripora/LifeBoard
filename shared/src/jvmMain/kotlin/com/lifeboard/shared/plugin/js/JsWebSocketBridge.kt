package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.jsengine.JsRuntime
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJson
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 把 WebSocket 长连接暴露给 JS 的 `ctx.ws.*`。
 *
 * ## 为什么是「拉取」而不是「回调注册」
 *
 * 宿主函数的入参和返回值都只能是 JSON 文本，JS 侧的函数没法作为参数传进来，
 * 所以插件注册不了「onMessage 回调」。改由插件主动拉：
 *
 * ```js
 * const id = await ctx.ws.connect(url)
 * for (;;) {
 *   const frame = await ctx.ws.recv(id)
 *   if (frame.type === 'close') break
 *   handle(frame)
 * }
 * ```
 *
 * 没有帧时 `recv` 返回挂起的 Promise，与 `ctx.http` 的挂起模型同构，
 * 插件线程不会空转。
 *
 * ## 线程模型
 *
 * OkHttp 的 WebSocket 回调跑在它自己的线程池里，**绝不能直接碰 runtime**。
 * 所有回灌都经 [JsPluginRuntime.post] 投到插件专用单线程，因此
 * [Conn] 上的可变状态只会在插件线程上读写，不必再加锁。
 * [cancelAll] 是唯一允许跨线程调用的入口，它只 cancel socket 并清表。
 *
 * ## 上界
 *
 * 插件消费慢于推送时，待消费帧按连接做上界（[MAX_QUEUED_FRAMES]），
 * 超出丢最旧并计数（`state().dropped`）。**不给 `JsPluginRuntime.post`
 * 加丢弃**：丢一次 `resolvePromise` 会让某个 `await` 永久挂起，
 * 比丢帧更难排查。
 */
class JsWebSocketBridge(
    private val runtime: JsPluginRuntime,
    /**
     * token / 连接 id 生成器，必须进程级唯一。
     *
     * 与 HTTP 桥同理：插件重载后若序号从头开始，旧运行时里同名 token
     * 的 Promise 会被后来的结果意外落地。
     */
    private val tokenProvider: () -> String,
    private val client: OkHttpClient = sharedClient(),
    /**
     * manifest 声明要信任的自签 CA 指纹；与 [JsHttpBridge] 用同一个值。
     *
     * 声明了它，插件就能连 `wss://` 的自签网关；否则只能连系统信任的证书。
     */
    private val pinnedCaSha256: String? = null,
) : HostFunctionDispatcher {

    /**
     * 连接表：连接 id → 连接。
     *
     * 用并发容器是因为 [cancelAll] 可能由加载器线程调用；元素内部状态
     * 仍然只在插件线程上修改。已关闭的连接**不立即移除**：插件还要靠
     * `recv` 拿到 close 帧、靠 `state` 读到终态。
     */
    private val conns = ConcurrentHashMap<String, Conn>()

    /**
     * 钉扎 CA 之后的 client；未声明 CA 的插件保持 null，走系统信任库。
     *
     * WebSocket 也要过 TLS：自签网关的 `wss://` 与 `https://` 用的是同一张证书，
     * 只给 HTTP 配信任而漏掉 WS，表现是「能登录但发不出消息」，很难联想。
     */
    @Volatile
    private var pinnedClient: OkHttpClient? = null

    /**
     * 安装自签 CA。由加载器在校验指纹与 manifest 一致之后调用。
     *
     * 对已建立的连接不生效——CA 必须在第一次 `connect` 之前装好。
     *
     * 加锁：HTTP 桥在插件线程上装完 CA 后回调进来，而 `connect` 也在插件线程，
     * 看起来天然串行；但 [cancelAll] 允许从加载器线程进入，将来若在别的线程
     * 重装 CA，裸引用赋值会让某次握手读到半成品状态。用锁把「只改一次引用」
     * 这件事说清楚，代价可以忽略。
     */
    fun installPinnedCa(caDer: ByteArray) {
        synchronized(this) {
            pinnedClient = PluginTls.pin(client, caDer)
        }
    }

    private fun baseClient(): OkHttpClient = synchronized(this) { pinnedClient ?: client }

    fun functionNames(): List<String> = JsHostFunctions.WS

    fun install(rt: JsRuntime) {
        rt.registerHostFunctions(functionNames(), this)
    }

    override fun invoke(name: String, argsJson: String): String {
        val args = runCatching { PluginJsonArray(argsJson) }.getOrElse { PluginJsonArray() }
        return when (name) {
            "ctx.ws.connect" -> connect(args)
            "ctx.ws.recv" -> recv(args)
            "ctx.ws.send" -> send(args)
            "ctx.ws.sendBinary" -> sendBinary(args)
            "ctx.ws.close" -> closeConn(args)
            "ctx.ws.state" -> state(args)
            else -> HostFunctionDispatcher.error("unknown ws function: $name")
        }
    }

    // ---------- 六个能力 ----------

    /**
     * 建立连接。
     *
     * 返回的 Promise 在握手成功时落地为连接 id；失败或被对端拒绝时 reject。
     * 握手期间的帧不会丢：OkHttp 在 `onOpen` 之前不会派发业务帧。
     */
    private fun connect(args: PluginJsonArray): String {
        val url = args.optStringOrNull(0)
            ?: return HostFunctionDispatcher.error("connect 需要 url")
        val headers = args.optJSONObject(1)?.optJSONObject("headers")

        val request = runCatching {
            val builder = Request.Builder().url(url)
            headers?.keys()?.forEach { key ->
                // empty 值不是「删除」：WebSocket 没有会话级默认头，
                // 插件显式写空串就是想要一个空头。
                builder.header(key, headers.optString(key))
            }
            builder.build()
        }.getOrElse { e ->
            return HostFunctionDispatcher.error("WebSocket 地址或请求头无效：${e.message ?: url}")
        }

        val id = tokenProvider()
        val token = tokenProvider()
        // 回调里只带 id，不持有 Conn：回调可能早于本方法返回，
        // 状态一律在插件线程上按 id 查表，避免跨线程共享对象。
        val socket = baseClient().newWebSocket(request, Listener(id))
        val conn = Conn(socket)
        conn.openToken = token
        conns[id] = conn
        return HostFunctionDispatcher.pending(token)
    }

    /**
     * 取下一帧。
     *
     * 有帧时同步返回（JS 侧 `await` 一样能拿到）；无帧时挂起，
     * 由 [deliver] / [finish] 在插件线程上落地。
     */
    private fun recv(args: PluginJsonArray): String {
        val conn = connOf(args) ?: return unknownConn(args)
        if (conn.waiter != null) {
            // 同一连接只允许一个等待者：两个挂起的 recv 会争抢帧，
            // 谁拿到哪一帧不确定，插件的分帧逻辑就会随机错乱。
            return HostFunctionDispatcher.error("该连接已有 recv 在等待")
        }
        conn.queue.removeFirstOrNull()?.let { return it.toJson() }
        if (conn.state == STATE_CLOSED) {
            return HostFunctionDispatcher.error("连接已关闭")
        }
        val token = tokenProvider()
        conn.waiter = token
        return HostFunctionDispatcher.pending(token)
    }

    private fun send(args: PluginJsonArray): String {
        val conn = connOf(args) ?: return unknownConn(args)
        if (conn.state != STATE_OPEN) {
            return HostFunctionDispatcher.error("连接状态为 ${conn.state}，不能发送")
        }
        val text = args.optStringOrNull(1)
            ?: return HostFunctionDispatcher.error("send 需要文本内容")
        return if (conn.socket.send(text)) {
            "null"
        } else {
            HostFunctionDispatcher.error("发送失败：连接正在关闭")
        }
    }

    private fun sendBinary(args: PluginJsonArray): String {
        val conn = connOf(args) ?: return unknownConn(args)
        if (conn.state != STATE_OPEN) {
            return HostFunctionDispatcher.error("连接状态为 ${conn.state}，不能发送")
        }
        val arr = args.optJSONArray(1)
            ?: return HostFunctionDispatcher.error("sendBinary 需要字节数组")
        val bytes = ByteArray(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v < 0 || v > 255) {
                return HostFunctionDispatcher.error("sendBinary 第 $i 个字节非法：$v（应在 0~255）")
            }
            bytes[i] = v.toByte()
        }
        return if (conn.socket.send(ByteString.of(*bytes))) {
            "null"
        } else {
            HostFunctionDispatcher.error("发送失败：连接正在关闭")
        }
    }

    private fun closeConn(args: PluginJsonArray): String {
        val conn = connOf(args) ?: return unknownConn(args)
        val code = if (args.length() > 1 && !args.isNull(1)) args.optInt(1, CLOSE_NORMAL) else CLOSE_NORMAL
        val reason = args.optStringOrNull(2) ?: ""
        // 先校验再交 OkHttp：它的 close() 对非法码直接抛 IllegalArgumentException，
        // 那会变成一条看不出所以然的 JS 异常，不如在这里给出明确原因。
        if (!isValidCloseCode(code)) {
            return HostFunctionDispatcher.error("关闭码非法：$code（只允许 1000 或 3000~4999）")
        }
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).size
        if (reasonBytes > MAX_CLOSE_REASON_BYTES) {
            return HostFunctionDispatcher.error("关闭原因过长：$reasonBytes 字节（上限 123）")
        }
        if (conn.state == STATE_CLOSED) return "null"
        conn.state = STATE_CLOSING
        // 返回值只表示「close 帧是否已排入发送队列」，不能当业务结果用：
        // 真正的终态由后续 close 帧承载。
        conn.socket.close(code, reason)
        return "null"
    }

    private fun state(args: PluginJsonArray): String {
        val conn = connOf(args) ?: return unknownConn(args)
        return PluginJsonObject().apply {
            put("state", conn.state)
            put("queued", conn.queue.size)
            put("dropped", conn.dropped)
        }.toString()
    }

    // ---------- 回灌（必须跑在插件线程） ----------

    /** 收到一帧：有等待者直接落地，否则入队。 */
    private fun deliver(connId: String, frame: Frame) {
        runtime.post { rt ->
            val conn = conns[connId] ?: return@post
            val waiter = conn.waiter
            if (waiter != null) {
                conn.waiter = null
                rt.resolvePromise(waiter, frame.toJson())
                rt.drainJobs()
                return@post
            }
            enqueue(conn, frame)
        }
    }

    /**
     * 连接终结：置终态，并按顺序处理三类等待方。
     *
     * 1. 还在等握手的 `connect` → reject（否则它永远挂着）
     * 2. 等帧的 `recv` → 直接给 close 帧
     * 3. 都没有 → close 帧入队，等插件下一次 `recv`
     */
    private fun finish(connId: String, code: Int, reason: String, clean: Boolean, error: String?) {
        runtime.post { rt ->
            val conn = conns[connId] ?: return@post
            conn.state = STATE_CLOSED
            val frame = Frame.Closed(code, reason, clean, error)

            conn.openToken?.let { token ->
                conn.openToken = null
                rt.resolvePromise(token, error = error ?: "连接在建立前关闭（$code）")
                rt.drainJobs()
            }
            val waiter = conn.waiter
            if (waiter != null) {
                conn.waiter = null
                rt.resolvePromise(waiter, frame.toJson())
                rt.drainJobs()
                return@post
            }
            enqueue(conn, frame)
        }
    }

    private fun enqueue(conn: Conn, frame: Frame) {
        if (conn.queue.size >= MAX_QUEUED_FRAMES) {
            conn.queue.removeFirstOrNull()
            conn.dropped++
            if (!conn.dropWarned) {
                conn.dropWarned = true
                AppLogger.warn(
                    "WebSocket 待消费帧超过 $MAX_QUEUED_FRAMES，开始丢弃最旧帧（插件消费过慢）"
                )
            }
        }
        conn.queue.addLast(frame)
    }

    /** 取消该插件全部连接。插件卸载或被撤销信任时调用，可从任意线程进入。 */
    fun cancelAll() {
        conns.values.forEach { runCatching { it.socket.cancel() } }
        conns.clear()
    }

    // ---------- 内部结构 ----------

    private fun connOf(args: PluginJsonArray): Conn? {
        val id = args.optStringOrNull(0) ?: return null
        return conns[id]
    }

    private fun unknownConn(args: PluginJsonArray): String {
        val id = args.optStringOrNull(0) ?: return HostFunctionDispatcher.error("需要连接 id")
        return HostFunctionDispatcher.error("未知连接：$id")
    }

    /** 一条连接。字段只在插件线程上读写，见类注释。 */
    private class Conn(val socket: WebSocket) {
        var state: String = STATE_CONNECTING

        /** 握手 Promise 的 token，仅握手期间非空。 */
        var openToken: String? = null

        /** 正在等帧的 `recv` token，同一时刻最多一个。 */
        var waiter: String? = null

        val queue = ArrayDeque<Frame>()

        /** 因队列上界被丢弃的帧数，供 `state()` 读取。 */
        var dropped: Int = 0

        /** 只告警一次，避免慢消费者把日志刷满。 */
        var dropWarned: Boolean = false
    }

    /** 交给 JS 的帧。`type` 字段用于插件侧分派。 */
    private sealed class Frame {
        abstract fun toJson(): String

        class Text(val data: String) : Frame() {
            override fun toJson(): String = PluginJsonObject().apply {
                put("type", "text")
                put("data", data)
            }.toString()
        }

        class Binary(val bytes: ByteArray) : Frame() {
            override fun toJson(): String {
                val arr = PluginJsonArray()
                bytes.forEach { arr.put(it.toInt() and 0xFF) }
                return PluginJsonObject().apply {
                    put("type", "binary")
                    put("bytes", arr)
                }.toString()
            }
        }

        /**
         * 终态帧，`recv` 拿到它之后连接不再产出任何帧。
         *
         * `clean` 区分「对端正常关闭」与「传输层失败」：后者 `error` 非空。
         */
        class Closed(
            val code: Int,
            val reason: String,
            val clean: Boolean,
            val error: String?,
        ) : Frame() {
            override fun toJson(): String = PluginJsonObject().apply {
                put("type", "close")
                put("code", code)
                put("reason", reason)
                put("clean", clean)
                if (error == null) putNull("error") else put("error", error)
            }.toString()
        }
    }

    /** OkHttp 回调线程 → 插件线程的唯一通路。 */
    private inner class Listener(private val connId: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            runtime.post { rt ->
                val conn = conns[connId] ?: return@post
                conn.state = STATE_OPEN
                val token = conn.openToken ?: return@post
                conn.openToken = null
                rt.resolvePromise(token, PluginJson.quote(connId))
                rt.drainJobs()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            deliver(connId, Frame.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            deliver(connId, Frame.Binary(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 对端发起关闭时必须回一个 close 帧，否则 OkHttp 会一直等到超时。
            // 个别终态码（1005 无状态、1006 异常）不允许出现在发出的帧里，
            // OkHttp 会直接抛 IllegalArgumentException，这时退化为 cancel。
            val replied = runCatching { webSocket.close(code, null) }.getOrDefault(false)
            if (!replied) webSocket.cancel()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(connId, code, reason, clean = true, error = null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 1006 是「异常关闭」的约定值，它本身不能出现在 close 帧里，
            // 只用于向插件描述终态。
            finish(
                connId,
                CLOSE_ABNORMAL,
                reason = "",
                clean = false,
                error = t.message ?: "WebSocket 连接失败",
            )
        }
    }

    companion object {
        const val STATE_CONNECTING = "connecting"
        const val STATE_OPEN = "open"
        const val STATE_CLOSING = "closing"
        const val STATE_CLOSED = "closed"

        /** 单条连接待消费帧的上界。约 512 帧足够覆盖一次突发推送。 */
        private const val MAX_QUEUED_FRAMES = 512

        /** close 帧 reason 的协议上限（UTF-8 字节数）。 */
        private const val MAX_CLOSE_REASON_BYTES = 123

        private const val CLOSE_NORMAL = 1000

        /** 异常关闭的约定码，仅用于描述终态，不会作为 close 帧发出。 */
        private const val CLOSE_ABNORMAL = 1006

        /** 协议允许应用主动发送的关闭码：1000，或 3000~4999。 */
        private fun isValidCloseCode(code: Int): Boolean =
            code == CLOSE_NORMAL || code in 3000..4999

        private fun sharedClient(): OkHttpClient = SHARED_CLIENT

        /**
         * 所有插件共用一套 WebSocket 连接池。
         *
         * `readTimeout(0)` 是长连接的硬要求：OkHttp 默认 10 秒读超时，
         * 空闲的 WebSocket 会被当成超时直接切断，表现为「静默几分钟就掉线」。
         */
        private val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
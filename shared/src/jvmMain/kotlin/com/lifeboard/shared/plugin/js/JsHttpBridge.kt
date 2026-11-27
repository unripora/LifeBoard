package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.jsengine.JsRuntime
import com.lifeboard.shared.plugin.json.PluginJson
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 把 HTTP 能力暴露给 JS 的 `ctx.http.*`。
 *
 * ## 为什么必须异步
 *
 * QuickJS 只有一个线程。如果在这里同步等 OkHttp 回调，宿主函数会一直占着
 * JS 调用栈，等待期间引擎无法推进任何微任务——`await ctx.http.get()` 永远醒不过来。
 *
 * 所以走「挂起 + 回灌」：
 * 1. JS 调 `ctx.http.get(url)`
 * 2. 宿主返回 `HostFunctionDispatcher.pending(token)`，发起 OkHttp 异步请求
 * 3. OkHttp 回调把「resolve + drain」整体投递到插件专用线程
 * 4. `await` 继续，可能又调起下一个 `ctx.http.*`，如此往复
 *
 * ## 线程约束（关键）
 *
 * QuickJS 的 `JSContext` 只能在创建它的线程上使用。OkHttp 回调跑在它自己的
 * 线程池里，**绝不能在回调里直接碰 runtime**。这里只持有 [JsPluginRuntime]，
 * 通过 [JsPluginRuntime.resolveAndDrain] 把结果投递过去。
 */
class JsHttpBridge(
    private val runtime: JsPluginRuntime,
    /**
     * token 生成器，必须全局唯一。
     *
     * 不能用「插件 ID + 序号」：同一插件重载后序号从头开始，
     * 旧运行时里同名 token 的 Promise 会被后来的结果意外落地。
     */
    private val tokenProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
    /**
     * manifest 声明要信任的自签 CA 指纹。null 表示不信任任何非系统 CA。
     *
     * 由加载器从 manifest 传进来；`ctx.http.trustCa` 拿到的证书必须与它一致，
     * 否则拒绝——插件不能自己决定信任谁。
     */
    private val pinnedCaSha256: String? = null,
    /**
     * CA 校验通过并装入 HTTP 客户端后的回调。
     *
     * WebSocket 与 HTTP 用的是同一个网关证书，却各自持有独立的
     * `OkHttpClient`。早先只给 HTTP 装了信任，于是配对那一刻的 HTTPS
     * 全部正常，详情页一建 `wss://` 就报
     * `PKIX path building failed`——证书没错，只是另一套 client 还不认识它。
     *
     * 由加载器接到同一插件的 [JsWebSocketBridge.installPinnedCa] 上，
     * 保证「信任一张 CA」对插件是**一次生效、两种通道都生效**。
     */
    private val onCaTrusted: ((ByteArray) -> Unit)? = null,
) : HostFunctionDispatcher {

    /** token → 已发出的请求，用于插件卸载时统一取消。 */
    private val inflight = ConcurrentHashMap<String, Call>()

    /**
     * 钉扎 CA 之后的 client；未声明 CA 的插件保持 null，走系统信任库。
     *
     * 懒建：多数插件不连自签服务，没必要在每个插件加载时都造一份 SSLContext。
     */
    @Volatile
    private var pinnedClient: OkHttpClient? = null

    /**
     * 安装自签 CA。
     *
     * 由加载器在**校验指纹与 manifest 一致之后**调用；桥接本身不再比对指纹，
     * 因为它拿不到 manifest，也不该有机会「自己决定信任谁」。
     *
     * 必须在第一次请求之前调用：已建好的会话 client 是派生副本，
     * 晚到的 CA 不会回溯生效。
     */
    fun installPinnedCa(caDer: ByteArray) {
        pinnedClient = PluginTls.pin(client, caDer)
        // 会话 client 是**派生副本且被缓存**（见 clientFor）：在装 CA 之前
        // 建过的会话仍指向未钉扎的基 client，之后每次请求都 PKIX 失败。
        //
        // 这不是理论问题：插件的会话通常在配对前就建好了（先建会话、
        // 再取 CA、再 trustCa），于是「日志里写着已信任网关 CA，紧接着
        // 请求仍然报 unable to find valid certification path」。
        // 清掉缓存后，下一次请求会基于新的基 client 重新派生；CookieJar
        // 引用的是同一个 jar 实例，cookie 不会丢。
        sessionClients.clear()
    }

    /**
     * `ctx.http.trustCa(caBase64[, expectedSha256])`：信任一张自签 CA。
     *
     * ## 为什么要绕一圈而不是直接用指纹建信任
     *
     * 指纹只能证明「是这张证书」，不能提供证书本身。证书要由插件从服务端
     * 取（`GET /mobile-access/ca.cer`），但取它的那次请求本身还没有可信 TLS。
     * 所以流程是：先用**不校验**的方式把证书取回来（只为了拿到公开的 CA 证书，
     * 不含私钥），再把算出的指纹与期望值比对——一致才真正装上信任。
     *
     * 不校验那一步的安全边界由后面的指纹比对兜住：中间人给的证书过不了比对。
     *
     * ## 期望指纹从哪来
     *
     * - manifest `pinnedCaSha256` 写死：用包里的值，第二个参数忽略
     * - manifest 写 `runtime`：用第二个参数。分发给多用户时必须这样——
     *   每台电脑的 dsh-mobile 各有自己的 CA，包不可能预先知道指纹
     */
    private fun trustCa(args: PluginJsonArray): String {
        val declared = pinnedCaSha256
            ?: return HostFunctionDispatcher.error(
                "本插件未在 manifest 声明 pinnedCaSha256，不能信任自签证书"
            )
        // 两种模式：
        // - manifest 写死 64 位指纹：只认那一张，第二个参数被忽略（最严格）
        // - manifest 写 `runtime`：必须由第二个参数给出期望指纹，用于分发给
        //   多个用户（每台电脑的 CA 不同，指纹只能来自各自的配对链接）
        val expected = if (declared.equals(JsPluginManifest.PINNED_CA_RUNTIME, ignoreCase = true)) {
            PluginTls.normalizeFingerprint(args.stringOrNull(1) ?: "")
                ?: return HostFunctionDispatcher.error(
                    "manifest 为 runtime 模式，trustCa 需要第二个参数：期望的 CA 指纹（64 位十六进制）"
                )
        } else {
            declared
        }
        val base64 = args.stringOrNull(0)
            ?: return HostFunctionDispatcher.error("trustCa 需要 CA 证书的 base64")
        val der = runCatching { java.util.Base64.getMimeDecoder().decode(base64) }
            .getOrElse { return HostFunctionDispatcher.error("trustCa 的 base64 无法解码") }
        if (der.isEmpty()) return HostFunctionDispatcher.error("trustCa 的证书为空")

        val actual = PluginTls.fingerprintOf(der)
        if (actual != expected) {
            // 指纹不符时绝不能装：这正是防中间人的那一步
            return HostFunctionDispatcher.error(
                "CA 指纹不符：声明 $expected，实际 $actual"
            )
        }
        return runCatching {
            installPinnedCa(der)
            // 同一份 CA 也要装进 WebSocket 客户端：缺了这一步，HTTPS 一切正常，
            // 只有 WSS 握手会 PKIX 失败，而错误会被归到「上次连接失败」里，
            // 很容易误判成配对过期或网关没起来。
            onCaTrusted?.invoke(der)
            "null"
        }.getOrElse {
            HostFunctionDispatcher.error("CA 安装失败：${it.message ?: it::class.simpleName}")
        }
    }

    /** 实际使用的基 client：钉扎过就用自己的，否则用默认的 */
    private fun baseClient(): OkHttpClient = pinnedClient ?: client

    /**
     * 抓取自签服务的 CA 证书本身（`ctx.http.caCertificate(url)`）。
     *
     * ## 为什么不复用普通请求
     *
     * 请求 CA 的那一刻，CA 还没被信任——用普通 client 会直接 SSL 失败，
     * 形成「要信任它才能取到它，要取到它才能信任它」的死循环。
     *
     * ## 为什么可以放宽校验
     *
     * CA 证书是**公开信息**（只含公钥，私钥在服务端），取错一张也不泄露什么；
     * 真正的安全边界在 [trustCa] 的指纹比对：中间人替换的证书过不了那一步。
     * 因此这里只放宽 TLS，其它一切照旧（超时、重定向、体积上限）。
     *
     * 只允许 https：http 明文取回一张「CA」毫无意义，且会掩盖配置错误。
     */
    private fun caCertificate(args: PluginJsonArray): String {
        val url = args.stringOrNull(0)
            ?: return HostFunctionDispatcher.error("caCertificate 需要 url")
        if (!url.startsWith("https://", ignoreCase = true)) {
            return HostFunctionDispatcher.error("caCertificate 只支持 https 地址")
        }
        val token = tokenProvider()
        val lenient = client.newBuilder()
            .sslSocketFactory(InsecureTls.socketFactory(), InsecureTls.trustManager())
            .hostnameVerifier { _, _ -> true }
            .build()
        val request = runCatching { Request.Builder().url(url).get().build() }
            .getOrElse { return HostFunctionDispatcher.error("caCertificate 的 url 非法：$url") }
        val call = lenient.newCall(request)
        inflight[token] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight.remove(token)
                runtime.resolveAndDrain(token, error = "取回 CA 证书失败：${e.message ?: "网络错误"}")
            }

            override fun onResponse(call: Call, response: Response) {
                inflight.remove(token)
                response.use {
                    val bytes = it.body?.bytes()
                    if (bytes == null || !it.isSuccessful) {
                        runtime.resolveAndDrain(
                            token,
                            error = "取回 CA 证书失败：HTTP ${it.code}",
                        )
                        return
                    }
                    // 直接给 base64：插件侧要把它交给 trustCa 比对指纹
                    val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    runtime.resolveAndDrain(token, PluginJson.quote(b64))
                }
            }
        })
        return HostFunctionDispatcher.pending(token)
    }

    /**
     * 本插件专属的重试调度器。
     *
     * **必须是实例级而不是 companion 级**：卸载插件时 [cancelAll] 会
     * `shutdownNow()`，如果调度器是全局共享的，卸载任意一个插件就会把
     * 调度器永久关闭——其它插件、以及之后重载的插件再也无法重试。
     * 这是纯宿主内部资源，每个插件一份代价很小。
     */
    private val retryScheduler: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "js-http-retry").apply { isDaemon = true }
        }

    /**
     * 会话表：会话名 → 会话。
     *
     * 一个插件可以有多个会话（比如同时登着两个账号），键由插件自己命名。
     * 会话的 cookie 互不可见——共用会让 A 站的登录态被带到 B 站，那是泄露。
     */
    private val sessions = ConcurrentHashMap<String, JsHttpSession>()

    /**
     * 每个会话派生一个挂了 CookieJar 的 OkHttpClient。
     *
     * 为什么不能共用一个 client：CookieJar 是 client 级别的，
     * 共用就等于所有会话共享 cookie，隔离就没了。
     * 用 `newBuilder()` 派生能复用连接池与线程池，不会每个会话开一套。
     */
    private val sessionClients = ConcurrentHashMap<String, OkHttpClient>()

    fun functionNames(): List<String> = JsHostFunctions.HTTP

    fun install(rt: JsRuntime) {
        rt.registerHostFunctions(functionNames(), this)
    }

    override fun invoke(name: String, argsJson: String): String {
        val args = runCatching { PluginJsonArray(argsJson) }.getOrElse { PluginJsonArray() }
        return when (name) {
            // 会话管理不依赖 url，先处理
            "ctx.http.session" -> createSession(args.stringOrNull(0), args.optBoolean(1, true))
            "ctx.http.setHeader" -> setSessionHeader(args)
            "ctx.http.setUserAgent" -> setSessionUserAgent(args)
            "ctx.http.setFollowRedirects" -> setSessionFollowRedirects(args)
            "ctx.http.cookieGet" -> cookieGet(args)
            "ctx.http.cookieSet" -> cookieSet(args)
            "ctx.http.cookieRemove" -> cookieRemove(args)
            "ctx.http.cookieClear" -> cookieClear(args)
            "ctx.http.cookieAll" -> cookieAll(args)
            // 上一次失败的分类，用来区分「超时」与「其它网络错误」
            "ctx.http.lastError" -> lastError()
            // 表单编码是纯字符串运算，不发起请求
            "ctx.http.form" -> form(args)
            // 声明信任一张自签 CA；指纹由 manifest 校验，插件不能自己决定信任谁
            "ctx.http.trustCa" -> trustCa(args)
            // 取回 CA 证书本身：这一刻还没信任它，必须走放宽校验的通道
            "ctx.http.caCertificate" -> caCertificate(args)
            else -> {
                val url = args.stringOrNull(0)
                    ?: return HostFunctionDispatcher.error("$name 需要 url")
                when (name) {
                    // 第四个参数是会话名，缺省落到当前默认会话（见 setDefaultSession）
                    "ctx.http.get" -> launch(
                        "GET", url, null, args.optJSONObject(1), args.stringOrNull(2),
                    )
                    "ctx.http.post" -> launch(
                        "POST", url, args.stringOrNull(1), args.optJSONObject(2),
                        args.stringOrNull(3),
                    )
                    // request(method, url, body?, options?, session?)：
                    // **method 在第 0 位、url 在第 1 位**，与 get/post 的
                    // 「url 永远第 0 位」不同（见 ctx.d.ts 的 HttpApi.request）。
                    // 早期这里复用了外层的 `url = args[0]`，于是 method 被当成
                    // url 传下去，报出「只支持 http/https：POST」，看起来像
                    // 协议不支持，实际是参数错位。
                    "ctx.http.request" -> launch(
                        method = args.stringOrNull(0) ?: "GET",
                        url = args.stringOrNull(1)
                            ?: return HostFunctionDispatcher.error(
                                "ctx.http.request 需要 (method, url, ...)：第 1 个参数应为 url"
                            ),
                        body = args.stringOrNull(2),
                        options = args.optJSONObject(3),
                        sessionName = args.stringOrNull(4),
                    )
                    // 表单 POST：args = [url, params(JSONObject), options, sessionName]
                    "ctx.http.postForm" -> {
                        // 参数类型错误必须显式报错：静默发一个空表单会让服务端
                        // 返回「缺参数」，插件作者会去怀疑接口而不是自己的入参。
                        val encoded = encodeForm(args.opt(1))
                            ?: return HostFunctionDispatcher.error(
                                "postForm 需要对象或 [[key, value]] 数组作为参数"
                            )
                        launch(
                            method = "POST",
                            url = url,
                            body = encoded,
                            options = formOptions(args.optJSONObject(2)),
                            sessionName = args.stringOrNull(3),
                        )
                    }
                    else -> HostFunctionDispatcher.error("unknown http function: $name")
                }
            }
        }
    }

    // ---------- 会话 ----------

    /**
     * 当前默认会话名。
     *
     * 插件创建会话后，不带会话名的请求都落到它上面。
     *
     * 这条规则很重要：早期实现里 `ctx.http.get/post` 一律走硬编码的
     * `__default__`，而插件 `ctx.http.session('default')` 建的是名为
     * `'default'` 的另一个会话——**种进去的 cookie 一次都用不上**。
     * 表现是服务端全程看到一个没有身份伪装的客户端：请求参数都对、
     * HTTP 200，但不认会话、不下发 Set-Cookie，最终卡在「缺少 ssecurity」。
     */
    @Volatile
    private var defaultSessionName: String = JsHttpSession.BUILT_IN_DEFAULT

    private fun createSession(name: String?, adoptAsDefault: Boolean): String {
        val key = JsHttpSession.resolveName(name, null)
        sessions.computeIfAbsent(key) { JsHttpSession(it) }
        // 提前把 client 建好：第一个请求就要用它，放在这里能提早暴露派生失败
        clientFor(key)
        // 后建的成为默认：插件通常只建一个会话，期望后续请求都走它。
        // 但「临时会话」（比如免密换票时新建的那个）不该顶掉正在用的会话，
        // 否则后续请求会莫名换到一个空会话上，表现为「刚登录完就掉线」。
        if (adoptAsDefault) defaultSessionName = key
        return PluginJson.quote(key)
    }

    private fun sessionOf(name: String?): JsHttpSession {
        val key = JsHttpSession.resolveName(name, defaultSessionName)
        return sessions.computeIfAbsent(key) { JsHttpSession(it) }
    }

    private fun clientFor(sessionName: String): OkHttpClient =
        sessionClients.computeIfAbsent(sessionName) { key ->
            val jar = sessions.getValue(key).jar
            baseClient().newBuilder()
                .cookieJar(
                    object : okhttp3.CookieJar {
                        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                            jar.saveAll(cookies)
                        }

                        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                            jar.load(url)
                    },
                )
                .build()
        }

    private fun setSessionHeader(args: PluginJsonArray): String {
        val session = sessionOf(args.stringOrNull(0))
        val headerName = args.stringOrNull(1)
            ?: return HostFunctionDispatcher.error("setHeader 需要 name")
        session.setDefaultHeader(headerName, args.stringOrNull(2))
        return "null"
    }

    private fun setSessionUserAgent(args: PluginJsonArray): String {
        sessionOf(args.stringOrNull(0)).userAgent = args.stringOrNull(1)
        return "null"
    }

    private fun setSessionFollowRedirects(args: PluginJsonArray): String {
        sessionOf(args.stringOrNull(0)).followRedirects = args.optBoolean(1, true)
        return "null"
    }

    // ---------- cookie ----------

    private fun cookieGet(args: PluginJsonArray): String {
        val value = sessionOf(args.stringOrNull(0)).jar.value(
            args.stringOrNull(1).orEmpty(),
            args.stringOrNull(2)?.takeIf { it.isNotBlank() },
            args.stringOrNull(3),
        )
        return if (value == null) "null" else PluginJson.quote(value)
    }

    private fun cookieSet(args: PluginJsonArray): String {
        val session = sessionOf(args.stringOrNull(0))
        val cookieName = args.stringOrNull(1)
        val value = args.stringOrNull(2)
        val domain = args.stringOrNull(3)
        if (cookieName.isNullOrBlank() || value == null || domain.isNullOrBlank()) {
            return HostFunctionDispatcher.error("cookieSet 需要 name/value/domain")
        }
        session.jar.put(cookieName, value, domain, args.stringOrNull(4) ?: "/")
        return "null"
    }

    private fun cookieRemove(args: PluginJsonArray): String {
        sessionOf(args.stringOrNull(0)).jar.remove(args.stringOrNull(1).orEmpty())
        return "null"
    }

    private fun cookieClear(args: PluginJsonArray): String {
        sessionOf(args.stringOrNull(0)).jar.clear()
        return "null"
    }

    private fun cookieAll(args: PluginJsonArray): String =
        sessionOf(args.stringOrNull(0)).jar.snapshot().toString()

    // ---------- 表单与错误分类 ----------

    /**
     * 上一次失败的错误分类。
     *
     * ## 为什么不能让插件自己从异常消息里猜
     *
     * `await ctx.http.get()` 失败时，插件拿到的是一个 Error，message 是
     * 「timeout」「Failed to connect to ...」这类 OkHttp 原文。插件作者只能
     * 用字符串匹配去判断是不是超时，而 OkHttp 的措辞会随版本变——
     * 这是把一个稳定的判断建在不稳定的字符串上。
     *
     * 所以宿主给出稳定的分类：`timeout` / `connect` / `dns` / `tls` / `other`。
     * 插件按分类决定「要不要重试」「要不要提示用户检查网络」。
     *
     * 只记最近一次，且只在本插件的运行时内有效——它是瞬时状态，
     * 并发请求会互相覆盖，需要精确归属的请在同一处 try/catch 里立刻读。
     */
    @Volatile
    private var lastErrorKind: String = ""

    private fun lastError(): String =
        if (lastErrorKind.isEmpty()) "null" else PluginJson.quote(lastErrorKind)

    /**
     * 把 OkHttp 的异常归类。
     *
     * 分类刻意粗：插件只需要「值不值得重试」这一个判断。
     * 细分到具体异常类型对业务没有帮助，反而会随 OkHttp 升级而失准。
     */
    private fun classify(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is java.net.ConnectException -> "connect"
        is javax.net.ssl.SSLException -> "tls"
        else -> if (e.message?.contains("timeout", ignoreCase = true) == true) "timeout" else "other"
    }

    /**
     * 把插件给的参数编成 `application/x-www-form-urlencoded`。
     *
     * ## 为什么收回宿主做
     *
     * 表单编码的规则比看上去多：键值都要百分号编码、空格按表单规则是 `+`
     * （不是 `%20`）、`null`/`undefined` 要发空串而不是字面量 "null"。
     * 每个插件都会抄一份 `formEncode`，抄错的版本在服务端看来是参数不对，
     * 而错误码通常只回一句「参数错误」，排查成本极高。
     *
     * 入参可以是对象或二元组数组：`{a: 1}` 或 `[['a', 1]]`。
     * 用数组形式是因为同名参数（`tag=a&tag=b`）在对象里表达不了。
     */
    private fun form(args: PluginJsonArray): String {
        val encoded = encodeForm(args.opt(0))
            ?: return HostFunctionDispatcher.error("form 需要对象或 [[key, value]] 数组")
        return PluginJson.quote(encoded)
    }

    /**
     * `postForm` 的参数包装：强制表单 Content-Type。
     *
     * 声明了 `ctx.http.postForm` 就不该再要求调用方写一遍 contentType，
     * 漏写会静静地上发一个 JSON 声明 + 表单内容，服务端解析出来全是空值。
     */
    private fun formOptions(options: PluginJsonObject?): PluginJsonObject =
        (options?.let { PluginJsonObject(it.toString()) } ?: PluginJsonObject())
            .apply { put("contentType", FORM_MEDIA_TYPE_STRING) }

    /**
     * 发起一次请求。
     *
     * @return 同步错误（如非法 URL）直接返回 `<error:...>`；
     *   正常情况返回 `<pending:token>`，由回调落地
     */
    private fun launch(
        method: String,
        url: String,
        body: String?,
        options: PluginJsonObject?,
        sessionName: String?,
    ): String {
        val sessionKey = JsHttpSession.resolveName(sessionName, defaultSessionName)
        val session = sessionOf(sessionKey)

        val request = try {
            buildRequest(method, url, body, options, session)
        } catch (e: IllegalArgumentException) {
            return HostFunctionDispatcher.error(e.message ?: "非法请求")
        }

        // 会话级 client 挂了 CookieJar：重定向的每一跳都会自动带上最新 cookie。
        //
        // 早先这里只能让插件自己拼 Cookie 头，于是重定向时 OkHttp 重新构造请求、
        // 把 Cookie 头剔除，跨域跳转后会话就断了。现在交给 CookieJar，
        // 行为与「浏览器 / requests.Session / OkHttp 默认」一致。
        //
        // followRedirects 仍可由单次请求或会话覆盖：拿 serviceToken 这类
        // 「只在中间跳的 Set-Cookie 里下发」的站点，必须关掉自动跟随逐跳读。
        val follow = options?.optBoolean("followRedirects", session.followRedirects)
            ?: session.followRedirects

        // 单次超时与重试。两者都必须可由插件声明：
        //
        // - 超时：默认 30 秒对「拉一天的历史数据」这种慢接口不够，
        //   对「探活请求」又太长。写死在宿主里，插件只能干等或干脆不用。
        // - 重试：移动网络下偶发的连接重置很常见，插件自己写重试要包一层
        //   Promise 循环，且很容易漏掉「非幂等方法不该重试」这条。
        var effective = clientFor(sessionKey)
        options?.optInt("timeoutMs", 0)?.takeIf { it > 0 }?.let { timeoutMs ->
            val ms = timeoutMs.toLong().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
            effective = effective.newBuilder()
                .connectTimeout(ms, TimeUnit.MILLISECONDS)
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .writeTimeout(ms, TimeUnit.MILLISECONDS)
                .build()
        }
        // 只有幂等方法才允许默认重试：重复 POST 可能让服务端执行两次
        // （多发一条短信、重复下单），这个锅不能由宿主的默认值来背
        val retryable = options?.optBoolean("retry", method.uppercase() in IDEMPOTENT_METHODS)
            ?: (method.uppercase() in IDEMPOTENT_METHODS)
        val maxAttempts = if (retryable) MAX_ATTEMPTS else 1
        if (!follow) effective = effective.newBuilder().followRedirects(false).build()

        val token = tokenProvider()
        sendAttempt(effective, request, token, attempt = 1, maxAttempts = maxAttempts)
        return HostFunctionDispatcher.pending(token)
    }

    /**
     * 发出一跳请求，失败且可重试时自行重发。
     *
     * 重试在宿主侧做而不是让插件包一层循环，是因为 token 只能落地一次：
     * 插件那边已经 `await` 着同一个 Promise，宿主内部重发对它是透明的。
     *
     * 只对**网络层失败**重试。已经拿到响应的（含 4xx/5xx）不重试——
     * 那属于业务结果，重发只会把同一份错误再拿一遍。
     */
    private fun sendAttempt(
        client: OkHttpClient,
        request: Request,
        token: String,
        attempt: Int,
        maxAttempts: Int,
    ) {
        val call = client.newCall(request)
        inflight[token] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight.remove(token)
                // 被主动取消（插件卸载、超时放弃）不算失败，也不再重试
                if (call.isCanceled()) {
                    runtime.resolveAndDrain(token, error = "请求已取消")
                    return
                }
                lastErrorKind = classify(e)

                if (attempt < maxAttempts) {
                    // 退避后再来：立刻重发在弱网下只是把同样的超时再踩一遍。
                    // 等待窗口里登记占位 Call，卸载时的 cancelAll 能覆盖到它。
                    val placeholder = placeholderFor()
                    inflight[token] = placeholder
                    val scheduled = scheduleRetry(attempt) {
                        // 等待期间被取消：撤销占位并结束 Promise，不再重发
                        if (placeholder.isCanceled()) {
                            inflight.remove(token, placeholder)
                            runtime.resolveAndDrain(token, error = "请求已取消")
                            return@scheduleRetry
                        }
                        inflight.remove(token, placeholder)
                        sendAttempt(client, request, token, attempt + 1, maxAttempts)
                    }
                    if (!scheduled) {
                        inflight.remove(token, placeholder)
                        runtime.resolveAndDrain(token, error = "请求已取消")
                    }
                    return
                }
                // 网络层失败：reject 掉 Promise，JS 侧 try/catch 能拿到原因。
                // 投递到插件线程，不在这里碰 runtime。
                runtime.resolveAndDrain(token, error = e.message ?: "网络请求失败")
            }

            override fun onResponse(call: Call, response: Response) {
                inflight.remove(token)
                response.use {
                    val result = try {
                        responseToJson(it)
                    } catch (e: Exception) {
                        lastErrorKind = "other"
                        runtime.resolveAndDrain(token, error = e.message ?: "响应读取失败")
                        return
                    }
                    runtime.resolveAndDrain(token, result)
                }
            }
        })
    }

    /**
     * 安排一次重试。
     *
     * 用一个宿主侧的调度器而不是 `Thread.sleep`：重试间隔里插件线程要继续
     * 推进其它微任务，睡死它会连带卡住整个插件。
     *
     * @return 是否成功入队；调度器已关闭（插件已卸载）时返回 false，
     *   由调用方负责把 Promise 收尾，避免它永远悬着
     */
    private fun scheduleRetry(attempt: Int, block: () -> Unit): Boolean = runCatching {
        retryScheduler.schedule(
            { runCatching { block() } },
            RETRY_BASE_DELAY_MS * attempt,
            TimeUnit.MILLISECONDS,
        )
        true
    }.getOrDefault(false)

    /**
     * 退避等待期间也算「在途」。
     *
     * [sendAttempt] 在网络回调里先把 token 从 [inflight] 摘掉，重试则是稍后
     * 才重新登记。这中间的空窗期如果插件被卸载，[cancelAll] 只能关闭调度器，
     * 那个 token 的 Promise 就永远悬着。所以在等待窗口里放一个「占位 Call」，
     * 让取消语义保持连续。
     */
    private fun placeholderFor(): Call = object : Call {
        @Volatile
        private var canceled = false

        override fun request(): Request = throw UnsupportedOperationException("placeholder")

        override fun execute(): Response = throw UnsupportedOperationException("placeholder")

        override fun enqueue(responseCallback: Callback) =
            throw UnsupportedOperationException("placeholder")

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): okio.Timeout = okio.Timeout.NONE

        override fun clone(): Call = this
    }


    internal fun buildRequest(
        method: String,
        url: String,
        body: String?,
        options: PluginJsonObject?,
    ): Request = buildHttpRequest(method, url, body, options, null)

    internal fun buildRequest(
        method: String,
        url: String,
        body: String?,
        options: PluginJsonObject?,
        session: JsHttpSession?,
    ): Request = buildHttpRequest(method, url, body, options, session)

    /**
     * 把响应转成 JS 能直接用的结构。
     *
     * 约定：
     * - `status` 状态码
     * - `ok` 是否 2xx
     * - `body` 响应体文本
     * - `bodyBase64` 响应体的 base64（二进制资源用；文本响应为 null）
     * - `json` 响应体解析成对象（解析失败为 null），省掉插件里的 JSON.parse
     * - `headers` 响应头（小写键）
     *
     * 非 2xx 不 reject：HTTP 错误码是业务信息，插件需要读到响应体
     * （比如接口用 400 返回具体错误原因），直接 reject 会让插件拿不到这些内容。
     */
    private fun responseToJson(response: Response): String {
        // 必须先取字节再决定怎么给 JS：图片这类二进制响应一旦走过 string()，
        // 字节会被按字符集解码成不可逆的乱码，插件再 base64Decode 只会得到垃圾。
        // 所以这里读 bytes()，文本按 UTF-8 解码，二进制额外给一份 base64。
        //
        // **有界读取**：不能先 `bytes()` 再判断大小——那等于先把 100MB 读进内存
        // 再拒绝，限额就失去意义了。超过上限直接抛错，由调用方翻译成 JS 异常。
        val bodyBytes = readBounded(response.body)
        val isText = isTextual(response.body?.contentType())
        val bodyText = if (isText) String(bodyBytes, Charsets.UTF_8) else ""
        val obj = PluginJsonObject()
        // status / ok 必须以**原生类型**下发，不能是字符串。
        //
        // 早先这里写的是 `response.code.toString()` / `response.isSuccessful.toString()`，
        // 于是插件拿到 `{status: "401", ok: "false"}`。JS 里非空字符串是真值，
        // `!res.ok` 恒为假——401 被当成成功，插件继续去解密那段明文错误响应，
        // 最后在 JSON.parse 处报「Unexpected end of JSON input」。
        // 用户看到的是「数据格式错误」，真实原因却是「凭据已失效」。
        obj.put("status", response.code)
        obj.put("ok", response.isSuccessful)
        obj.put("body", bodyText)
        if (isText) {
            obj.putNull("bodyBase64")
        } else {
            obj.put("bodyBase64", Base64.getEncoder().encodeToString(bodyBytes))
        }
        val parsedJsonObject = runCatching { PluginJsonObject(bodyText) }.getOrNull()
        if (parsedJsonObject != null) {
            obj.put("json", parsedJsonObject)
        } else {
            val parsedJsonArray = runCatching { PluginJsonArray(bodyText) }.getOrNull()
            if (parsedJsonArray != null) obj.put("json", parsedJsonArray) else obj.putNull("json")
        }
        val headers = PluginJsonObject()
        response.headers.names().forEach { name ->
            headers.put(name.lowercase(), response.headers.get(name).orEmpty())
        }
        obj.put("headers", headers)
        // Set-Cookie 是最常出现同名多值（一个响应下发多条 cookie）的头，
        // 只给 join 后的字符串会让插件无法可靠解析——值里可能含逗号。
        // 额外给出数组形式，插件按需取用。
        val setCookie = PluginJsonArray()
        response.headers.values("Set-Cookie").forEach { setCookie.put(it) }
        obj.put("setCookie", setCookie)
        return obj.toString()
    }

    /**
     * 有界读取响应体。
     *
     * 超限立即抛错而不是截断：截断的 JSON 会被插件当成"解析失败"，
     * 与"响应太大"是两个完全不同的故障，不能混。
     *
     * @throws PayloadTooLargeException 超过 [MAX_RESPONSE_BYTES]
     */
    private fun readBounded(body: okhttp3.ResponseBody?): ByteArray {
        if (body == null) return ByteArray(0)
        val buffer = okio.Buffer()
        var total = 0L
        val source = body.source()
        while (true) {
            val read = source.read(buffer, CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw PayloadTooLargeException(
                    "响应体超过 ${MAX_RESPONSE_BYTES / 1024 / 1024} MiB 上限，已中止读取"
                )
            }
        }
        return buffer.readByteArray()
    }

    /** 响应体超过上限 */
    class PayloadTooLargeException(message: String) : Exception(message)

    /**
     * 判断响应能否当文本交给插件。
     *
     * 只把明确的文本类型当文本；没有 Content-Type 的一律按文本处理——
     * 老接口经常不声明类型，按二进制处理会让它的 body 突然变空。
     * 图片 / 音视频 / octet-stream 明确排除。
     */
    private fun isTextual(contentType: MediaType?): Boolean {
        val type = contentType?.type ?: return true
        val subtype = contentType?.subtype ?: return true
        if (type == "text") return true
        if (type == "image" || type == "audio" || type == "video") return false
        if (subtype == "octet-stream") return false
        // application/json、application/xml、application/x-www-form-urlencoded 等
        return type == "application"
    }

    /** 取消该插件所有在途请求。插件卸载或被撤销信任时调用。 */
    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        // 停掉未执行的重试：插件都没了，重发只是往外发无主请求
        runCatching { retryScheduler.shutdownNow() }
    }

    private fun PluginJsonArray.stringOrNull(index: Int): String? {
        if (index < 0 || index >= length()) return null
        if (isNull(index)) return null
        // 不能用 ifBlank：空串和「没传」是两回事。
        // 插件明确传 '' 表示「我要一个空 body」，把它归成 null 会让 POST 变成
        // 无 body 请求，OkHttp 直接抛 `method POST must have a request body`。
        return optStringOrNull(index) ?: ""
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 表单媒体类型；`postForm` 自动套用，插件不必再写一遍 */
        private const val FORM_MEDIA_TYPE_STRING = "application/x-www-form-urlencoded"

        /**
         * 默认允许多次尝试的方法。
         *
         * 只含幂等方法：GET/HEAD 重发无副作用；POST 重发可能让服务端执行两次
         * （重复发短信、重复下单）。插件想对 POST 重试必须显式传 `retry: true`，
         * 由它自己对「这次重试是否安全」负责。
         */
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")

        /** 单次请求的最多尝试次数（含首次） */
        private const val MAX_ATTEMPTS = 2

        /** 重试退避基数，第 n 次重试等 n 倍 */
        private const val RETRY_BASE_DELAY_MS = 400L

        /** 单次超时下限：低于 1 秒基本必然失败，属于参数写错 */
        private const val MIN_TIMEOUT_MS = 1_000L

        /** 单次超时上限：再长就该怀疑接口卡死，不如让插件拿到超时错误 */
        private const val MAX_TIMEOUT_MS = 180_000L

        /** 响应体上限：4 MiB。超过即中止读取，避免大响应把宿主内存打爆 */
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

        /** 有界读取的块大小 */
        private const val CHUNK_BYTES = 8L * 1024

        /**
         * 把 JSON 值编成 `application/x-www-form-urlencoded` 字符串。
         *
         * 支持两种入参：
         * - 对象 `{"a": 1, "b": "x y"}` → `a=1&b=x+y`
         * - 二元组数组 `[["tag","a"],["tag","b"]]` → `tag=a&tag=b`
         *
         * 数组形式是必需的：同名参数在对象里表达不了，而「多选筛选」
         * 「批量上报」这类接口大量使用同名参数。
         *
         * 编码规则按 HTML 表单（`application/x-www-form-urlencoded`）：
         * 空格编成 `+` 而不是 `%20`。很多服务端严格按表单规则解码，
         * 用 `%20` 在多数情况下也能过，但少数框架会把它解成字面量 `%20`。
         *
         * @return 编码结果；入参既不是对象也不是数组时返回 null，由调用方报错
         */
        internal fun encodeForm(value: Any?): String? = when (value) {
            is PluginJsonObject -> value.keys().asSequence()
                .joinToString("&") { key ->
                    percentEncode(key) + "=" + percentEncode(formValueOf(value.opt(key)))
                }

            is PluginJsonArray -> (0 until value.length()).mapNotNull { i ->
                val pair = value.optJSONArray(i) ?: return@mapNotNull null
                // null / undefined 一律发空串：服务端期望「有这个字段但没值」，
                // 发字面量 "null" 会被当成字符串参与校验
                val key = formValueOf(pair.opt(0))
                val v = formValueOf(pair.opt(1))
                percentEncode(key) + "=" + percentEncode(v)
            }.joinToString("&")

            else -> null
        }

        /** JSON 值转成表单里的字符串；null 与 JSONObject.NULL 都算空串 */
        private fun formValueOf(value: Any?): String = when (value) {
            null, PluginJsonObject.NULL -> ""
            is String -> value
            is Double -> if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            else -> value.toString()
        }

        /**
         * 百分号编码。
         *
         * 不用 `java.net.URLEncoder`：它会按 `application/x-www-form-urlencoded`
         * 把 `+` 也编成 `%2B` 之外的东西，且对 `*` 的处理与浏览器不一致。
         * 这里按 RFC 3986 逐字节处理，只保留 unreserved 字符。
         */
        private fun percentEncode(input: String): String {
            val bytes = input.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size * 3)
            bytes.forEach { b ->
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                when {
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                        ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                    // 表单规则：空格是 '+'
                    ch == ' ' -> sb.append('+')
                    else -> {
                        sb.append('%')
                        sb.append(HEX[c ushr 4])
                        sb.append(HEX[c and 0x0f])
                    }
                }
            }
            return sb.toString()
        }

        private val HEX = "0123456789ABCDEF".toCharArray()


        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")

        /**
         * 按插件的调用参数构造请求。
         *
         * 独立成静态函数，是因为这里的判断全在参数上、与运行时无关，
         * 单独测它不需要造一个插件运行时出来。
         */
        internal fun buildHttpRequest(
            method: String,
            url: String,
            body: String?,
            options: PluginJsonObject?,
            session: JsHttpSession? = null,
        ): Request {
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "只支持 http/https：$url"
            }

            val builder = Request.Builder().url(url)

            // 会话级默认头先铺一层，单次请求的 headers 覆盖它。
            // 顺序不能反：会话是「这个插件的默认行为」，单次请求是「这一次的特例」。
            session?.defaultHeadersSnapshot()?.forEach { (key, value) ->
                builder.header(key, value)
            }
            session?.userAgent?.takeIf { it.isNotBlank() }?.let {
                builder.header("User-Agent", it)
            }

            options?.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { key ->
                    builder.header(key, headers.optString(key))
                }
            }
            options?.optString("userAgent")?.takeIf { it.isNotBlank() }?.let {
                builder.header("User-Agent", it)
            }

            val httpMethod = method.uppercase()

            // POST / PUT / PATCH 必须带 RequestBody，哪怕内容为空。
            //
            // OkHttp 的 method() 对这几个方法传 null body 会直接抛
            // `method POST must have a request body`。插件里发空表单 POST 是正常需求
            // （有些接口把参数放 query，body 留空），所以这里补一个空串，
            // 产出合法的 `Content-Length: 0` 请求。
            val requestBody = (body ?: if (httpMethod in METHODS_REQUIRING_BODY) "" else null)
                ?.toRequestBody(
                    options?.optString("contentType")
                        ?.takeIf { it.isNotBlank() }
                        ?.toMediaType()
                        ?: JSON_MEDIA_TYPE,
                )
            builder.method(httpMethod, requestBody)

            return builder.build()
        }

        /**
         * 默认客户端。
         *
         * 超时比常规短：插件的 fetch 在后台任务里跑，卡太久会拖住整轮采集。
         *
         * 这里**不挂 CookieJar**：cookie 属于会话，见 [JsHttpSession]。
         * 每个会话用 `newBuilder()` 从这个 client 派生，共享连接池与线程池，
         * 只是各自挂自己的 CookieJar，从而做到隔离。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

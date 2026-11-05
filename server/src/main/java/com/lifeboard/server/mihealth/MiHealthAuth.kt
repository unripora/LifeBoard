package com.lifeboard.server.mihealth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 小米账号登录（passport），用于无 root 获取 [MiHealthCredentials]。
 *
 * 复刻自 `external/mihealth-api/login.py`。流程：
 *
 * ```
 * ① GET  /pass/serviceLogin?sid=miothealth&_json=true      → {_sign, qs, callback}
 * ② POST /pass/serviceLoginAuth2  user + hash(MD5 单次大写)
 *        → 成功: {ssecurity, cUserId, userId, passToken, location}
 *        → 需验证码: {captchaUrl}
 *        → 需二次验证: {notificationUrl}
 * ③ GET  <location>（不跟随跳转）                            → Set-Cookie: serviceToken
 * ```
 *
 * ## 为什么处处要伪装
 * 服务端按 UA + `sdkVersion` / `deviceId` cookie 判断是不是官方 App。
 * 缺任一项都可能返回 `70016`（参数不合法），而这个错误码看不出真实原因。
 *
 * ## 二次验证
 * `identity/list` 返回的 `flag` 决定通道：**4 = 手机短信，8 = 邮箱**。
 * 必须按 flag 走对应端点，写死邮箱会拿到 `{code:2, flag:4}`（要求改用手机）。
 */
class MiHealthAuth private constructor(
    private val http: OkHttpClient,
    private val deviceId: String,
) {
    /**
     * 公开构造：不暴露 OkHttp 类型。
     *
     * [OkHttpClient] 属 server 模块的 implementation 依赖，app 模块看不到该类型。
     * 一旦它出现在公开签名上，app 侧调用会直接编译失败
     * （`Cannot access class 'okhttp3.OkHttpClient'`）。
     */
    constructor() : this(defaultHttpClient(), genDeviceId())

    /** 最近一次 passToken 换票失败的原因，用于把诊断显示到界面 */
    private var lastRefreshDiagnostic: String = ""

    /** 登录过程中的中间态，交给 UI 决策后再往下走 */
    sealed interface LoginResult {
        /** 登录完成 */
        data class Success(val credentials: MiHealthCredentials) : LoginResult

        /**
         * 需要图片验证码。
         *
         * [captchaUrl] 给 UI 展示；[pending] 必须原样传回 [loginWithCaptcha]：
         * 验证码与 [pending] 里的会话 cookie（`ick`）绑定，换会话取到的图必然对不上。
         */
        data class NeedCaptcha(
            val captchaUrl: String,
            val pending: PendingCaptcha,
        ) : LoginResult

        /** 需要二次验证码（短信/邮箱），[pending] 必须原样传回 [complete2FA] */
        data class Need2FA(
            val pending: PendingTwoFactor,
            val method: String,
            val hint: String,
        ) : LoginResult

        /** 失败，[message] 面向用户 */
        data class Failure(val message: String, val code: Int? = null) : LoginResult
    }

    /**
     * 第一步：账号密码登录。
     *
     * 调用方拿到 [LoginResult.NeedCaptcha] 或 [LoginResult.Need2FA] 时，
     * 应向用户要输入，然后调用对应的 `loginWithCaptcha` / `complete2FA`。
     *
     * @param captcha 上次返回 NeedCaptcha 时用户输入的验证码，首次传 null
     */
    suspend fun login(
        account: String,
        password: String,
        captcha: String? = null,
    ): LoginResult = withContext(Dispatchers.IO) {
        val client = newSessionClient()
        Log.i(TAG, "login start, account=${account.take(3)}***")
        runCatching { doLogin(client, account, password, captcha) }
            .onSuccess { Log.i(TAG, "login result=${it::class.simpleName}") }
            .getOrElse { e ->
                Log.w(TAG, "login failed", e)
                LoginResult.Failure("登录异常：${e.message ?: e::class.simpleName}")
            }
    }

    /**
     * 新建一个带伪装 cookie 的登录会话。
     *
     * 伪装 cookie 必须塞进 [CookieJar]，**不能**用 `Request.header("Cookie", ...)`：
     * 手写的 Cookie header 会把 CookieJar 里的内容整个覆盖掉，
     * 而验证码就绑在服务端下发的 `ick` cookie 上——一覆盖就永远取不到对的图。
     */
    private fun newSessionClient(): OkHttpClient {
        val jar = MemoryCookieJar()
        // 域写成 xiaomi.com 与 mi.com 两份：passport 两处都会读
        listOf("xiaomi.com", "mi.com").forEach { domain ->
            jar.seed(cookie(domain, "sdkVersion", SDK_VERSION))
            jar.seed(cookie(domain, "deviceId", deviceId))
            jar.seed(cookie(domain, "_locale", "zh_CN"))
        }
        return http.newBuilder().cookieJar(jar).build()
    }

    private fun cookie(domain: String, name: String, value: String): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain)
            .path("/")
            .build()

    /**
     * 提交图片验证码。
     *
     * 复用 [pending] 里的会话，不重跑 [doLogin]——重跑会重新请求 `serviceLogin`
     * 拿新的 `_sign`，服务端那边的验证码上下文就断了，表现为「提交后反而退回无图状态」。
     */
    suspend fun loginWithCaptcha(pending: PendingCaptcha, captcha: String): LoginResult =
        withContext(Dispatchers.IO) {
            runCatching { submitForm(pending, captcha) }
                .getOrElse { e ->
                    Log.w(TAG, "loginWithCaptcha failed", e)
                    LoginResult.Failure("验证码提交异常：${e.message ?: e::class.simpleName}")
                }
        }

    /**
     * 用 [pending] 的会话换一张新图。
     *
     * 每次都用新的随机数请求：[PendingCaptcha.captchaPath] 里的随机数会命中缓存，
     * 重放旧地址拿不到新图。
     */
    suspend fun refreshCaptcha(pending: PendingCaptcha): String =
        withContext(Dispatchers.IO) {
            fetchCaptchaDataUri(pending.httpClient())
        }

    /**
     * 登录主流程。
     *
     * 小米 passport 的真实行为（实测，与旧文档不一致）：
     *
     * 1. 不带 `captCode` 提交只会回 `70016 登录验证失败`，且 `captchaUrl` 为 null。
     * 2. 带任意非空 `captCode` 提交才回 `87001` 并给出 `captchaUrl`。
     * 3. **`/pass/getCode` 是公开端点**：不需要会话、不需要先触发，
     *    随便请求一次就是一张新图。
     *
     * 所以正确流程是：密码提交 → 若判定需要验证码，**直接去 getCode 取图**，
     * 不必先带占位码空提交一次（那条「先空提交」的路径会让用户觉得
     * 「必须先随便输点东西图才出来」）。
     */
    private fun doLogin(
        client: OkHttpClient,
        account: String,
        password: String,
        captcha: String?,
    ): LoginResult {
        // ① 拿 _sign / qs / callback
        val step1 = getJson(
            client, "$ACCOUNT/pass/serviceLogin",
            mapOf("sid" to SID, "_json" to "true"),
        ) ?: return LoginResult.Failure("无法连接小米登录服务，请检查网络")

        val sign = step1.optString("_sign")
        if (sign.isBlank()) {
            return LoginResult.Failure("登录握手失败：未拿到 _sign")
        }

        val baseForm = LinkedHashMap<String, String>().apply {
            put("user", account)
            put("hash", MiHealthCredentials.hashPassword(password))
            put("sid", SID)
            put("jsondata", "")
            put("_json", "true")
            put("_sign", sign)
            put("qs", step1.optString("qs"))
            put("callback", step1.optString("callback"))
            put("locale", "zh_CN")
        }
        val pending = PendingCaptcha(client, account, password, baseForm)

        return submitForm(pending, captcha)
    }

    /**
     * 用 [pending] 的会话提交一次（验证码可为空）。
     *
     * 抽出来是因为「首次登录」和「填完验证码再提交」除了 `captCode` 之外完全一样，
     * 而后者绝不能重新走 [doLogin]（会换掉 `_sign` 与验证码上下文）。
     */
    private fun submitForm(pending: PendingCaptcha, captcha: String?): LoginResult {
        val client = pending.httpClient()
        val form = LinkedHashMap(pending.baseForm).apply {
            if (!captcha.isNullOrBlank()) put("captCode", captcha)
        }

        val step2 = postJson(
            client, "$ACCOUNT/pass/serviceLoginAuth2", form,
            extraHeaders = mapOf("Referer" to "$ACCOUNT/"),
        ) ?: return LoginResult.Failure("登录请求失败")

        val code = step2.optInt("code", -1)

        // 需要二次验证（短信 / 邮箱）
        val notification = step2.optString("notificationUrl")
        if (notification.isNotBlank()) {
            return start2FA(client, notification, step2.optString("ssecurity"), pending.account)
        }

        if (code == 0) {
            // ③ 跟随 STS 回调，拿 serviceToken
            return completeLogin(client, step2, pending.account)
        }

        // 需要图片验证码：
        // 87001 = 验证码错误/缺失，70016 = 参数被拒（小米在要求验证码时也用这个码）
        if (code == 87001 || code == 70016 || step2.optString("captchaUrl").isNotBlank()) {
            Log.i(TAG, "captcha required, code=$code, fetching image from getCode")
            val image = fetchCaptchaDataUri(client)
            return LoginResult.NeedCaptcha(image, pending)
        }

        val desc = step2.optString("desc").ifBlank { "未知错误" }
        return LoginResult.Failure(friendlyError(code, desc), code)
    }

    /**
     * 取验证码图，返回可直接交给 Image 的数据 URI。
     *
     * 实测：`/pass/getCode?icodeType=login&<随机数>` 是**公开端点**，
     * 不需要登录会话、也不需要先触发什么，任何一次 GET 都是一张新图。
     * 所以这里不依赖 `auth2` 返回的 `captchaUrl`——那条路径要求先带一个
     * 占位 `captCode` 提交，用户表现为「必须先随便输点东西图才出来」。
     *
     * 随机数必须每次重新生成：服务端/中间层会按 query 缓存，
     * 沿用旧地址要么拿到同一张图，要么拿到空响应。
     */
    private fun fetchCaptchaDataUri(client: OkHttpClient): String {
        val url = "$ACCOUNT/pass/getCode?icodeType=login&${Math.random()}"
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua())
                .header("Referer", "$ACCOUNT/")
                .build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "getCode empty body, http=${resp.code}")
                    return@runCatching ""
                }
                if (!isJpeg(bytes) && !isPng(bytes)) {
                    Log.w(
                        TAG,
                        "getCode not an image, http=${resp.code} " +
                            "head=${String(bytes, 0, minOf(bytes.size, 120))}",
                    )
                    return@runCatching ""
                }
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                    bytes, android.util.Base64.NO_WRAP,
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "getCode failed", e)
            ""
        }
    }

    /** JPEG 魔数 FFD8，PNG 魔数 89504E47 */
    private fun isJpeg(b: ByteArray): Boolean =
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    /**
     * 第二步：提交二次验证码完成登录。
     *
     * 注意：2FA 必须在**同一个会话**里续着走，而 [login] 每次都会新建 CookieJar，
     * 所以这里要求调用方把 [Need2FA] 和它所属的会话一起传回来。
     * 为简化实现，[session] 由 [PendingTwoFactor] 携带。
     */
    suspend fun complete2FA(pending: PendingTwoFactor, code: String): LoginResult =
        withContext(Dispatchers.IO) {
            var lastLoginDiagnostic = ""
            runCatching {
                val client = pending.httpClient()
                val flag = pending.flag
                val method = if (flag == 8) "Email" else "Phone"
                val gate = "$IDENTITY/auth/verify$method"

                // 提交验证码
                val vresp = postJson(
                    client,
                    gate,
                    mapOf(
                        "_flag" to flag.toString(),
                        "ticket" to code.trim(),
                        "trust" to "false",
                        "_json" to "true",
                    ),
                    extraQuery = mapOf(
                        "_dc" to System.currentTimeMillis().toString(),
                        "_flag" to flag.toString(),
                        "_json" to "true",
                        "sid" to SID,
                        "context" to pending.context,
                    ),
                ) ?: return@withContext LoginResult.Failure("验证码提交失败")

                val vcode = vresp.optInt("code", 0)
                val loc = vresp.optString("location")
                Log.i(
                    TAG,
                    "complete2FA verify$method: code=$vcode loc=${loc.take(80)} raw=${vresp.toString().take(300)}",
                )
                if (vcode != 0 && loc.isBlank()) {
                    Log.e(TAG, "complete2FA verify$method rejected: code=$vcode raw=$vresp")
                    return@withContext LoginResult.Failure(
                        "验证码校验未通过（code=$vcode " +
                            "${vresp.optString("desc")}）",
                        vcode,
                    )
                }
                if (loc.isBlank()) {
                    return@withContext LoginResult.Failure("验证码通过但未拿到跳转地址")
                }

                // 访问 location 建立已认证会话。
                //
                // 这里**必须跟随重定向**：客户端的 followRedirects 是关的
                // （passport 的第一步要求不跟随），但这一步恰恰要靠跳转链把
                // 认证 cookie 一层层种下去。只发一个不跟随的请求，会话不算建立，
                // 后面重做 serviceLogin 就还是未认证状态，返回 70016。
                //
                // 也不要在这里覆盖 UA：跳转链后半段落在 identity / sts 域上，
                // 那些端点认的是米家 App 形态的 UA，套用 passport 的 UA 会被拒。
                runCatching {
                    val noRedirect = client.newBuilder().followRedirects(true).build()
                    noRedirect.newCall(
                        Request.Builder().url(absUrl(loc)).build(),
                    ).execute().use { it.body?.string() }
                }.onFailure { Log.w(TAG, "complete2FA: visit location failed", it) }

                // 到这里 2FA 已经真正通过：服务端在这次会话里下发了
                // passToken / serviceToken / cUserId / userId（见 cookieSummary）。
                //
                // 原先这里会重做一次 serviceLogin 去换 ssecurity，但那一步在
                // 已认证会话上会返回 70016——token 明明已经拿到，却非要再问一次，
                // 反而被拒。认证凭据直接从会话里取。
                val jar = pending.cookieJar()
                val serviceToken = jar?.value("serviceToken").orEmpty()
                val passInfo = jar?.value("passInfo").orEmpty()

                // ssecurity 有三个可能来源，按可靠性依次尝试：
                // 1. auth2 响应（服务端亲手给的，最可靠）
                // 2. passInfo cookie（实测经常为空）
                // 3. 重做 serviceLogin——只有在 location 已经访问过、会话真正认证后
                //    才会返回 code=0；之前失败是因为顺序反了（先问再访问）
                var ssecurity = pending.auth2Ssecurity.ifBlank { extractSsecurity(passInfo) }
                var cUserId = jar?.value("cUserid") ?: jar?.value("cUserId").orEmpty()
                var passToken = jar?.value("passToken").orEmpty()
                var finalToken = serviceToken

                if (ssecurity.isBlank() || finalToken.isBlank()) {
                    val again = getJson(
                        client, "$ACCOUNT/pass/serviceLogin",
                        mapOf("sid" to SID, "_json" to "true"),
                    )
                    if (again != null) {
                        lastLoginDiagnostic = "code=${again.optInt("code", -1)} " +
                            "desc=${again.optString("desc")} " +
                            "hasSsec=${again.optString("ssecurity").isNotBlank()} " +
                            "hasLoc=${again.optString("location").isNotBlank()}"
                        if (ssecurity.isBlank()) {
                            ssecurity = again.optString("ssecurity")
                        }
                        if (cUserId.isBlank()) cUserId = again.optString("cUserId")
                        if (passToken.isBlank()) passToken = again.optString("passToken")
                        if (finalToken.isBlank()) {
                            val loc2 = again.optString("location")
                            if (loc2.isNotBlank()) {
                                finalToken = fetchServiceToken(client, loc2).orEmpty()
                            }
                        }
                    } else {
                        lastLoginDiagnostic = "serviceLogin 返回空"
                    }
                }

                if (ssecurity.isBlank()) {
                    // 最后一条路：用拿到的 passToken 独立换一套完整凭据。
                    // 这条路径是给「日常续期」用的，逻辑最简单也最稳，
                    // 不依赖当前这个已经被 2FA 搅过的会话。
                    val refreshed = if (passToken.isNotBlank()) {
                        refreshWithPassToken(
                            passToken = passToken,
                            cUserId = cUserId,
                            userId = jar?.value("userid").orEmpty(),
                        )?.copy(account = pending.account)
                    } else {
                        null
                    }
                    if (refreshed != null && refreshed.isValid()) {
                        return@withContext LoginResult.Success(refreshed)
                    }
                    // 拿不到 ssecurity 就无法解密任何后续响应，必须让用户看到原因
                    return@withContext LoginResult.Failure(
                        "登录已通过但缺少 ssecurity。retry=[$lastLoginDiagnostic] " +
                            "refresh=[$lastRefreshDiagnostic] " +
                            "passToken=${passToken.length}",
                    )
                }

                LoginResult.Success(
                    MiHealthCredentials(
                        ssecurity = ssecurity,
                        serviceToken = finalToken,
                        cUserId = cUserId,
                        passToken = passToken,
                        account = pending.account,
                    ),
                )
            }.getOrElse { e ->
                Log.e(TAG, "complete2FA failed", e)
                LoginResult.Failure("二次验证异常：${e.message ?: e::class.simpleName}")
            }
        }

    /**
     * 用 passToken 换一套新凭据。
     *
     * 免密续期：passToken 有效期约 1~2 个月，日常刷新靠它。
     * 失败（返回 null）说明 passToken 也过期了，必须重新输账号密码。
     */
    suspend fun refreshWithPassToken(
        passToken: String,
        cUserId: String = "",
        userId: String = "",
        account: String = "",
    ): MiHealthCredentials? =
        withContext(Dispatchers.IO) {
            if (passToken.isBlank()) return@withContext null
            val jar = MemoryCookieJar()
            val client = http.newBuilder().cookieJar(jar).build()
            try {
                // 先种 passToken，伪装 cookie 同样走 CookieJar 而不是手写 header
                listOf("xiaomi.com", "mi.com").forEach { domain ->
                    jar.seed(cookie(domain, "sdkVersion", SDK_VERSION))
                    jar.seed(cookie(domain, "deviceId", deviceId))
                    jar.seed(cookie(domain, "_locale", "zh_CN"))
                }
                jar.seed(cookie("xiaomi.com", "passToken", passToken))
                // 参考实现明确要求：换票时必须带 cUserId 与 userId，
                // 只带 passToken 服务端会当作未认证
                if (cUserId.isNotBlank()) jar.seed(cookie("xiaomi.com", "cUserId", cUserId))
                if (userId.isNotBlank()) jar.seed(cookie("xiaomi.com", "userId", userId))

                val again = getJson(
                    client, "$ACCOUNT/pass/serviceLogin",
                    mapOf("sid" to SID, "_json" to "true"),
                )
                if (again == null || again.optString("ssecurity").isBlank()) {
                    Log.i(TAG, "refreshWithPassToken: passToken rejected")
                    lastRefreshDiagnostic = if (again == null) {
                        "serviceLogin 空响应"
                    } else {
                        "code=${again.optInt("code", -1)} " +
                            "desc=${again.optString("desc")} " +
                            "hasSsec=${again.optString("ssecurity").isNotBlank()} " +
                            "hasLoc=${again.optString("location").isNotBlank()}"
                    }
                    return@withContext null
                }
                val loc = again.optString("location")
                if (loc.isBlank()) {
                    lastRefreshDiagnostic = "无 location"
                    return@withContext null
                }

                val token = fetchServiceToken(client, loc)
                if (token.isNullOrBlank()) {
                    lastRefreshDiagnostic = "STS 无 token"
                    return@withContext null
                }
                MiHealthCredentials(
                    ssecurity = again.optString("ssecurity"),
                    serviceToken = token,
                    cUserId = again.optString("cUserId").ifBlank { cUserId },
                    passToken = again.optString("passToken").ifBlank { passToken },
                    account = account,
                )
            } catch (e: Exception) {
                Log.w(TAG, "refreshWithPassToken failed", e)
                null
            }
        }

    /**
     * 触发二次验证码下发，返回 {context, flag, method, hint}。
     *
     * flag 语义：4 = 手机短信，8 = 邮箱。必须按 flag 选端点，
     * 写死邮箱会得到 `{code:2, flag:4}`（要求改用手机）。
     */
    private fun start2FA(
        client: OkHttpClient,
        notificationUrlRaw: String,
        auth2Ssecurity: String,
        account: String,
    ): LoginResult {
        val notificationUrl = absUrl(notificationUrlRaw)
        // 同 complete2FA：authStart 要靠跳转链建立验证会话，必须跟随重定向
        runCatching {
            val follow = client.newBuilder().followRedirects(true).build()
            follow.newCall(
                Request.Builder().url(notificationUrl).header("User-Agent", ua()).build(),
            ).execute().use { it.body?.string() }
        }.onFailure { Log.w(TAG, "start2FA: authStart failed", it) }

        val ctx = notificationUrl.substringAfter("context=", "")
            .substringBefore("&").let { if (it.isBlank()) "" else it }

        val listJson = getJson(
            client, "$IDENTITY/list",
            mapOf("sid" to SID, "supportedMask" to "0", "_locale" to "zh_CN", "context" to ctx),
        ) ?: return LoginResult.Failure("无法获取二次验证方式")

        val flag = listJson.optInt("flag", 4)
        val method = if (flag == 8) "Email" else "Phone"

        // 触发下发
        getJson(
            client, "$IDENTITY/auth/verify$method",
            mapOf("_flag" to flag.toString(), "_json" to "true"),
        )
        if (method == "Phone") {
            postJson(
                client, "$IDENTITY/auth/sendPhoneTicket",
                mapOf("retry" to "0", "icode" to "", "_json" to "true"),
            )
        }

        val hint = listOf("mask", "notification", "email", "phone", "address")
            .firstNotNullOfOrNull { listJson.optString(it).takeIf { v -> v.isNotBlank() } }
            .orEmpty()

        return LoginResult.Need2FA(
            pending = PendingTwoFactor(
                client = client,
                context = ctx,
                flag = flag,
                method = method,
                hint = hint,
                auth2Ssecurity = auth2Ssecurity,
                account = account,
            ),
            method = method,
            hint = hint,
        )
    }

    /** ③ 走 STS 回调拿 serviceToken（不跟随跳转，从 Set-Cookie 里取） */
    private fun completeLogin(client: OkHttpClient, j: JSONObject, account: String): LoginResult {
        val loc = j.optString("location")
        if (loc.isBlank()) {
            return LoginResult.Failure("登录成功但缺少 STS 回调地址")
        }
        val token = fetchServiceToken(client, loc)
        if (token.isNullOrBlank()) {
            return LoginResult.Failure("未拿到 serviceToken，可能被风控拦截")
        }
        return LoginResult.Success(
            MiHealthCredentials(
                ssecurity = j.optString("ssecurity"),
                serviceToken = token,
                cUserId = j.optString("cUserId"),
                passToken = j.optString("passToken"),
                account = account,
            ),
        )
    }

    /**
     * 从 `passInfo` cookie 里解析 `ssecurity`。
     *
     * 2FA 走完时服务端不再返回 auth2 响应体，`ssecurity` 只藏在 `passInfo` 里。
     * 它是一个 URL-encoded 的 JSON（形如 `{"ssecurity":"...","cUserId":"..."}`），
     * 也有可能是 base64 过的，两种都试一遍。
     */
    private fun extractSsecurity(passInfo: String?): String {
        if (passInfo.isNullOrBlank()) return ""
        val candidates = linkedSetOf<String>()
        candidates += passInfo
        runCatching { candidates += java.net.URLDecoder.decode(passInfo, "UTF-8") }
        runCatching {
            candidates += String(android.util.Base64.decode(passInfo, android.util.Base64.DEFAULT))
        }

        // 命中判定：JSON 字段 → 正则 → k=v 形式，三种都试
        candidates.forEach { text ->
            runCatching {
                val o = JSONObject(text)
                o.optString("ssecurity").takeIf { it.isNotBlank() }?.let { return it }
            }
            Regex("\"ssecurity\"\\s*:\\s*\"([^\"]+)\"").find(text)?.let {
                return it.groupValues[1]
            }
            // passInfo 也可能是 "ssecurity=xxx&cUserId=yyy" 这种 query 串
            Regex("(?:^|[&;\\s])ssecurity=([^&;\\s\"']+)").find(text)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
        }
        return ""
    }

    private fun fetchServiceToken(client: OkHttpClient, location: String): String? {
        // STS 这一步不能跟随跳转：跳转后 token 会落到下一跳的域上，反而取不到
        val req = Request.Builder()
            .url(absUrl(location))
            .header("User-Agent", ua())
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                // 三种来源依次尝试：响应头、CookieJar、响应体
                val fromHeader = resp.headers.values("Set-Cookie")
                    .firstNotNullOfOrNull { c ->
                        Regex("serviceToken=([^;]+)").find(c)?.groupValues?.get(1)
                    }
                if (!fromHeader.isNullOrBlank() && fromHeader != "EXPIRED") {
                    return fromHeader
                }
                val fromJar = (client.cookieJar as? MemoryCookieJar)?.value("serviceToken")
                if (!fromJar.isNullOrBlank() && fromJar != "EXPIRED") {
                    return fromJar
                }
                val body = resp.body?.string().orEmpty()
                Regex("serviceToken=([^;&\"'\\s]+)").find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchServiceToken failed", e)
            null
        }
    }

    // ---- HTTP 基础 ----

    private fun getJson(
        client: OkHttpClient,
        url: String,
        query: Map<String, String>,
    ): JSONObject? = exec(client, "GET", url, query, null)

    private fun postJson(
        client: OkHttpClient,
        url: String,
        form: Map<String, String>,
        extraQuery: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject? = exec(client, "POST", url, extraQuery, form, extraHeaders)

    private fun exec(
        client: OkHttpClient,
        method: String,
        url: String,
        query: Map<String, String>,
        form: Map<String, String>?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val base = url.toHttpUrlOrNull() ?: return null
        val full = base.newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        // identity 系列是「App 侧」端点，UA 必须是米家 App 那种带 DeviceId/UserId 段的格式，
        // 用 passport 那套 UA 会拿到异常响应。这一点是参考实现明确踩过的坑。
        val isIdentity = url.contains("/identity/")
        val builder = Request.Builder()
            .url(full)
            .header("User-Agent", if (isIdentity) identityUa() else ua())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "$ACCOUNT/")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        when (method) {
            "GET" -> builder.get()
            else -> {
                val body = (form ?: emptyMap()).entries.joinToString("&") { (k, v) ->
                    "${enc(k)}=${enc(v)}"
                }
                builder.post(body.toRequestBody(FORM_MEDIA))
            }
        }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty().trim()
                if (text.isEmpty()) {
                    Log.w(TAG, "exec($url) empty body, http=${resp.code}")
                    return null
                }
                Log.d(TAG, "exec($url) http=${resp.code} body=${text.take(300)}")
                parseMiapiJson(text)
            }
        } catch (e: Exception) {
            // 原来是只 catch IOException：JsonEncodingException、SSL 握手失败、
            // 证书异常等都会被漏掉，最后只看到一个没有日志的 null 返回
            Log.w(TAG, "exec($url) failed", e)
            null
        }
    }

    /**
     * 小米 passport 的 JSON 响应常带 `&&&START&&&` 前缀，不能直接当 JSON 解析。
     */
    private fun parseMiapiJson(text: String): JSONObject? {
        val cleaned = text.substringAfter("&&&START&&&", text).trim()
        return runCatching { JSONObject(cleaned) }.getOrElse { e ->
            Log.w(TAG, "parseMiapiJson failed: ${cleaned.take(120)}", e)
            null
        }
    }

    private fun ua(): String =
        "MiFit/3.59.0 (Android 14; en_US) Resolution/1080x2400 " +
            "Version/3.59.0 Stage/Production DeviceId/${deviceId} UserId/0"

    /**
     * `identity` 系列端点要求的 UA 形态。
     *
     * 必须像米家 App：带 `DeviceId/` 与 `UserId/` 段，且带 `Platform/Region`。
     * 用上面 passport 那套 UA 请求 identity，服务端会返回异常或空响应，
     * 表现为「验证码发出去了，但提交后拿不到跳转地址」。
     */
    private fun identityUa(): String =
        "MiHome/11.3.203 (com.xiaomi.mihome; build:11.3.203; Android 9) " +
            "APP/com.xiaomi.mihome APPV/11.3.203 DeviceId/${deviceId} UserId/0 " +
            "Platform/Android Region/CN L/zh_CN"

    private fun absUrl(path: String): String =
        if (path.startsWith("http")) path else "$ACCOUNT$path"

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * 承载验证码阶段的中间态。
     *
     * 持有登录会话和已经算好的表单（含 `_sign` / `qs` / `callback`）：
     * 提交验证码时只是往这份表单里补一个 `captCode`，**不能**重新走一遍
     * `serviceLogin`——那会拿到新的 `_sign`，服务端的验证码上下文随之失效，
     * 表现为「填了正确的码反而退回无图状态」。
     */
    class PendingCaptcha internal constructor(
        private val client: OkHttpClient,
        val account: String,
        val password: String,
        internal val baseForm: Map<String, String>,
    ) {
        internal fun httpClient(): OkHttpClient = client
    }

    /**
     * 承载 2FA 会话的中间态：必须复用同一个 CookieJar，否则会话断了。
     *
     * [client] 是私有字段：它是 OkHttp 类型，属本模块 implementation 依赖。
     * 一旦对其它模块可见（哪怕只暴露在方法返回类型上），
     * app 侧就会因为看不到该类型而编译失败。
     */
    class PendingTwoFactor internal constructor(
        private val client: OkHttpClient,
        val context: String,
        val flag: Int,
        val method: String,
        val hint: String,
        /**
         * auth2 响应里带的 ssecurity。
         *
         * 2FA 完成后服务端不再返回 auth2 响应体，这是唯一可靠拿到的来源；
         * 从 `passInfo` cookie 解析是兜底（实测常常为空）。
         */
        internal val auth2Ssecurity: String = "",
        /** 登录用的账号，仅用于界面显示「当前登录的是谁」 */
        val account: String = "",
    ) {
        /** 诊断用：列出当前会话持有的 cookie 名，判断 passToken / cUserId 有没有拿到 */
        internal fun cookieSummary(): String = cookieJar()?.names()?.joinToString(",").orEmpty()

        /** 取出承载会话的 CookieJar，用于直接读取服务端下发的凭据 */
        internal fun cookieJar(): MemoryCookieJar? = client.cookieJar as? MemoryCookieJar

        /**
         * 仅内部取用。
         *
         * Kotlin 的嵌套类私有成员**外部类也访问不到**（与 Java 不同），
         * 所以必须留一个访问器；用 internal 而不是 public，
         * 避免 OkHttp 类型出现在跨模块可见的签名上。
         */
        internal fun httpClient(): OkHttpClient = client
    }

    private fun friendlyError(code: Int, desc: String): String = when (code) {
        70016 -> "登录验证失败（70016）：小米拒绝了本次请求，通常是账号触发了风控或需要先在官方 App 完成一次验证"
        70002 -> "账号或密码错误"
        70003 -> "账号不存在"
        87001 -> "验证码输入错误，请重新填写"
        else -> "登录失败（$code）：$desc"
    }

    // ---- 辅助 ----

    /** 内存 CookieJar：登录是多步会话，必须保持 cookie */
    internal class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        /**
         * 种入一条伪装 cookie。
         *
         * 按 `name + domain` 去重，不能只按 name：
         * `userId` / `cUserId` / `serviceToken` 会在
         * `.account.xiaomi.com` 与 `.sts-hlth.io.mi.com` 各存一份，
         * 只按 name 删会把另一个域的合法值一起抹掉。
         */
        fun seed(cookie: Cookie) {
            store.removeAll { it.name == cookie.name && it.domain == cookie.domain }
            store.add(cookie)
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c ->
                store.removeAll { it.name == c.name && it.domain == c.domain }
                store.add(c)
            }
        }

        /**
         * 按域匹配。
         *
         * `domain` 可能是 `.xiaomi.com`（带前导点，表示含子域），也可能是不带点的
         * `xiaomi.com`。两种情况都要能匹配上 `account.xiaomi.com`，
         * 否则服务端下发的 `ick` 存得进去、发不出来，验证码就永远对不上。
         */
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            return store.filter { c ->
                val d = c.domain.trimStart('.')
                host == d || host.endsWith(".$d")
            }
        }

        /** 诊断用：当前持有的 cookie 名（去重） */
        fun names(): List<String> = store.map { it.name }.distinct()

        /**
         * 按名取 cookie 值。
         *
         * 取值规则：过滤掉 `EXPIRED`，并优先取服务端真正下发过的域
         * （`.account.xiaomi.com` / `.sts-hlth.io.mi.com`），最后才回退到
         * 我们自己种的伪装 cookie。否则可能读到种子值而不是服务端给的真值。
         */
        fun value(name: String): String? {
            val alive = store.filter { it.name == name && it.value != "EXPIRED" && it.value.isNotBlank() }
            if (alive.isEmpty()) return null
            val fromServer = alive.filter { c ->
                val d = c.domain.trimStart('.')
                d == "account.xiaomi.com" || d == "sts-hlth.io.mi.com"
            }
            return (fromServer.ifEmpty { alive }).last().value
        }
    }

    companion object {
        private const val TAG = "LifeBoardMiHealth"

        private const val ACCOUNT = "https://account.xiaomi.com"
        private const val IDENTITY = "$ACCOUNT/identity"
        private const val SID = "miothealth"

        /** passport 要求客户端自称的 SDK 版本，缺了会被判 70016 */
        private const val SDK_VERSION = "accountsdk-18.8.15"

        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        private fun genDeviceId(): String {
            val chars = ('A'..'Z') + ('0'..'9')
            return (1..16).map { chars.random() }.joinToString("")
        }

        /** 私有：返回 OkHttp 类型，不能出现在跨模块可见的签名上 */
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}

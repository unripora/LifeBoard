package com.lifeboard.plugin.mihealth

import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.SourcePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 小米运动健康插件。
 *
 * 数据来自云端接口（`hlth.io.mi.com`）而不是本地数据库：本地库需要 root，
 * 普通应用读不到 App 私有目录。云端只要登录一次，之后 passToken 自动续期。
 *
 * ## 这个插件是契约的验证对象
 *
 * 它必须证明契约能表达真实站点的三种复杂度：
 * - **多轮登录**：密码 → 可能的图片验证码 → 可能的短信/邮箱二次验证
 * - **免密续期**：passToken 换新 serviceToken，全程自己在 `fetch` 内完成
 * - **分页 + 多量纲**：步数与睡眠是两套不同结构的接口，各自分页
 *
 * 如果其中任何一项做不到，应该改契约，而不是在宿主里给它开特例。
 */
class MiHealthPlugin : SourcePlugin {

    override val id: String = "com.lifeboard.plugin.mihealth"

    override val displayName: String = "小米健康"

    override val apiVersion: Int = PluginApi.VERSION

    /**
     * 只声明「账号密码」两项要保存的凭据。
     *
     * 其余四个 token（ssecurity / serviceToken / cUserId / passToken）都是登录产物，
     * 不向用户展示——用户不需要知道它们，也不该被要求填写。
     * 它们仍存在 [PluginContext.credentials] 里，只是不出现在 [credentialSpec]。
     */
    override val credentialSpec: CredentialSpec = CredentialSpec(
        slots = listOf(
            CredentialSpec.Slot(
                name = SLOT_ACCOUNT,
                displayName = "小米账号",
                required = true,
                secret = false,
            ),
            CredentialSpec.Slot(
                name = SLOT_PASSWORD,
                displayName = "密码",
                required = true,
                // 密码必须掩码：登录时旁边可能有人，明文输入是实打实的泄露
                secret = true,
            ),
        ),
    )

    /**
     * 两个指标复用 [MetricBucket] 的枚举名。
     *
     * 这不是巧合：`metric.bucket` 列存的一直是枚举名，
     * 复用同一取值让插件数据与历史内置数据在同一张表里语义一致，不需要额外映射。
     */
    override val metrics: List<MetricSpec> = listOf(
        MetricSpec.of(MetricBucket.STEPS, "步数"),
        MetricSpec.of(MetricBucket.SLEEP_MINUTES, "睡眠"),
    )

    // ---- 登录 ----

    /**
     * 多轮登录。
     *
     * 小米 passport 的流程是**递进**的：提交密码后才知道要不要验证码，
     * 验证码过了才知道要不要二次验证。所以这里用「轮到哪一步」来决定读哪些输入，
     * 而不是一开始就要齐所有字段。
     *
     * 中间态放在内存里的 [pending]：验证码与那次会话的 cookie 绑定，
     * 换会话取到的图必然对不上，所以必须把 OkHttp 会话跨轮次保留住。
     *
     * ## 为什么要串行
     *
     * [pendingLogin] 是「当前登录走到哪一步」的唯一真相。用户连点提交时，
     * 两个协程可能同时读到 null 各自发起密码登录，后完成的那个把前一个的
     * 会话覆盖掉——验证码图与 cookie 就对不上了。
     * 宿主侧的 `busy` 只能减少连点，不能当并发保证，插件必须自己扛住。
     *
     * 用 [Mutex] 而不是 `synchronized`：本方法中间有网络挂起点，
     * 前者挂起时释放线程，后者会一直占着。
     */
    override suspend fun login(
        context: PluginContext,
        inputs: Map<String, String>,
    ): LoginResult = loginMutex.withLock {
        withContext(Dispatchers.IO) {
            // 已有中间态：说明这是在回答上一轮的提问
            val pending = pendingLogin

            if (pending == null) {
                // 第一轮：要账号密码。调用方第一次调用通常不带 inputs，
                // 这时先问，不该把空账号密码拿去请求
                val account = inputs[SLOT_ACCOUNT]
                val password = inputs[SLOT_PASSWORD]
                if (account.isNullOrBlank() || password.isNullOrBlank()) {
                    return@withContext LoginResult.NeedInput(
                        prompt = "请输入小米账号和密码",
                        fields = listOf(
                            InputField(SLOT_ACCOUNT, "小米账号"),
                            InputField(SLOT_PASSWORD, "密码", secret = true),
                        ),
                    )
                }
                startLogin(context, account, password)
            } else when (pending.stage) {
                Stage.CAPTCHA -> {
                    val code = inputs[SLOT_CAPTCHA]
                    if (code.isNullOrBlank()) {
                        return@withContext needCaptcha(pending)
                    }
                    finish(auth.loginWithCaptcha(pending.captcha!!, code), context)
                }

                Stage.TWO_FACTOR -> {
                    val code = inputs[SLOT_OTP]
                    if (code.isNullOrBlank()) {
                        return@withContext needOtp(pending)
                    }
                    finish(auth.complete2FA(pending.twoFactor!!, code), context)
                }

                Stage.PASSWORD -> startLogin(
                    context,
                    inputs[SLOT_ACCOUNT] ?: pending.account,
                    inputs[SLOT_PASSWORD] ?: pending.password,
                )
            }
        }
    }

    /** 第一轮：拿账号密码去小米 passport 换结果 */
    private suspend fun startLogin(
        context: PluginContext,
        account: String,
        password: String,
    ): LoginResult {
        val result = auth.login(account, password)
        return finish(result, context, account, password)
    }

    /**
     * 把 [MiHealthAuth.LoginResult] 翻译成契约的 [LoginResult]。
     *
     * 两个 `LoginResult` 同名但语义不同：前者是站点私有的中间态（还带着 OkHttp 会话），
     * 后者是给宿主看的纯数据。翻译在这一层收口，契约里就不会出现任何 OkHttp 类型。
     */
    private suspend fun finish(
        result: MiHealthAuth.LoginResult,
        context: PluginContext,
        account: String = "",
        password: String = "",
    ): LoginResult = when (result) {
        is MiHealthAuth.LoginResult.Success -> {
            saveCredentials(context, result.credentials)
            pendingLogin = null
            LoginResult.Success
        }

        is MiHealthAuth.LoginResult.NeedCaptcha -> {
            pendingLogin = PendingLogin(
                stage = Stage.CAPTCHA,
                captcha = result.pending,
                account = account.ifBlank { pendingLogin?.account.orEmpty() },
                password = password.ifBlank { pendingLogin?.password.orEmpty() },
            )
            needCaptcha(pendingLogin!!)
        }

        is MiHealthAuth.LoginResult.Need2FA -> {
            pendingLogin = PendingLogin(
                stage = Stage.TWO_FACTOR,
                twoFactor = result.pending,
                account = account.ifBlank { pendingLogin?.account.orEmpty() },
                password = password.ifBlank { pendingLogin?.password.orEmpty() },
            )
            needOtp(pendingLogin!!, result.hint)
        }

        is MiHealthAuth.LoginResult.Failure -> {
            // 失败要清掉中间态，否则用户重试时会直接跳到验证码步骤、拿不到新会话
            pendingLogin = null
            LoginResult.Failure(result.message)
        }
    }

    private suspend fun needCaptcha(pending: PendingLogin): LoginResult {
        val image = pending.captcha?.let {
            runCatching { auth.refreshCaptcha(it) }.getOrNull()
        }
        return LoginResult.NeedInput(
            prompt = "需要输入图片验证码",
            // 验证码不掩码：用户要照着图抄，掩码只会让他抄错。
            // 掩码该留给密码这种长期秘密，一次性短码明文显示更安全也更省事
            fields = listOf(InputField(SLOT_CAPTCHA, "验证码", secret = false)),
            captchaImage = image?.takeIf { it.isNotBlank() },
        )
    }

    private fun needOtp(pending: PendingLogin, hint: String = ""): LoginResult =
        LoginResult.NeedInput(
            prompt = if (hint.isBlank()) {
                "需要二次验证码，请查看手机短信或邮箱"
            } else {
                "验证码已发送至 $hint"
            },
            fields = listOf(InputField(SLOT_OTP, "验证码", secret = false, hint = "6 位数字")),
        )

    override suspend fun logout(context: PluginContext) = withContext(Dispatchers.IO) {
        // 不取登录锁：登出是用户点了就要立刻生效的动作，
        // 排在一次可能等半分钟的登录后面没有任何道理。
        // 这里只把中间态置空，正在跑的那次 login 会自行结束并覆盖它，
        // 但那次的结果也不会再被用户看到（表单已经关了）
        pendingLogin = null
        context.credentials.clear()
    }

    override suspend fun authState(context: PluginContext): AuthState =
        withContext(Dispatchers.IO) {
            val creds = loadCredentials(context) ?: return@withContext AuthState.LoggedOut
            if (!creds.isValid()) return@withContext AuthState.LoggedOut

            // 有 passToken 说明凭据即将或已经过期，但还能自动续期。
            // 这正是契约要区分 Expired 与 LoggedOut 的理由：
            // 此时报「已登录」会让用户看到拉不到数，报「未登录」又会在下次拉数时自己恢复
            if (creds.passToken.isNotBlank()) {
                AuthState.LoggedIn(accountName = creds.account.ifBlank { null })
            } else {
                AuthState.Expired("登录即将过期，需要重新输入密码")
            }
        }

    // ---- 拉数 ----

    override suspend fun fetch(context: PluginContext, date: String): List<PluginMetric> =
        withContext(Dispatchers.IO) {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@withContext emptyList()
            val (start, end) = dayRange(day)
            val client = clientFor(context) ?: return@withContext emptyList()

            val out = mutableListOf<PluginMetric>()

            // 两个量纲各自独立：一个失败不该让另一个也丢
            runCatching {
                collectSteps(client, start, end)?.let {
                    out += PluginMetric(MetricBucket.STEPS.name, it)
                }
            }

            runCatching {
                collectSleepMinutes(client, start, end)?.let {
                    out += PluginMetric(MetricBucket.SLEEP_MINUTES.name, it)
                }
            }

            out
        }

    /**
     * 当日步数。
     *
     * 步数接口返回的是**采样点**（通常按小时间隔），需要累加。
     */
    private suspend fun collectSteps(
        client: MiHealthClient,
        start: Long,
        end: Long,
    ): Double? {
        val points = client.getFitnessDataByTime("steps", start, end)
        if (points.isEmpty()) return null
        val sum = points.sumOf { pointValue(it) }
        return if (sum > 0) sum else null
    }

    /**
     * 当日睡眠分钟数。
     *
     * 睡眠结构与步数不同：每个点是 `{startTime, endTime, ...}` 的一段，
     * 时长要按 end - start 算，而不是取某个 value 字段。
     */
    private suspend fun collectSleepMinutes(
        client: MiHealthClient,
        start: Long,
        end: Long,
    ): Double? {
        val points = client.getFitnessDataByTime("sleep", start, end)
        if (points.isEmpty()) return null

        var totalMillis = 0L
        points.forEach { p ->
            val s = p.optLong("startTime", 0L)
            val e = p.optLong("endTime", 0L)
            if (s > 0 && e > s) {
                // 只统计与当日窗口重叠的部分，避免跨天睡眠把前后两天都算满
                val overlapStart = maxOf(s, start)
                val overlapEnd = minOf(e, end)
                if (overlapEnd > overlapStart) totalMillis += overlapEnd - overlapStart
            }
        }
        return if (totalMillis > 0) totalMillis / 60_000.0 else null
    }

    /** 采样点的数值：不同 key 的字段名不一样，按优先级取第一个存在的 */
    private fun pointValue(p: JSONObject): Double {
        for (name in VALUE_FIELDS) {
            if (p.has(name)) {
                val v = p.optDouble(name, Double.NaN)
                if (!v.isNaN()) return v
            }
        }
        return 0.0
    }

    override suspend fun health(context: PluginContext): PluginHealth =
        withContext(Dispatchers.IO) {
            val creds = loadCredentials(context)
            if (creds == null || !creds.isValid()) {
                return@withContext PluginHealth.Broken("未登录小米账号")
            }

            val client = clientFor(context)
                ?: return@withContext PluginHealth.Broken("未登录小米账号")

            // 用最近一天的数据探一次，能拿到就算健康
            val now = System.currentTimeMillis()
            val points = runCatching {
                client.getFitnessDataByTime("steps", now - DAY_MILLIS, now, maxPages = 1)
            }.getOrNull()

            when {
                points != null -> PluginHealth.Healthy
                // 凭据过期且续期失败：这不是网络抖动，用户必须重新登录
                loadCredentials(context)?.isValid() == false ->
                    PluginHealth.Broken("登录已失效，请重新登录")
                else -> PluginHealth.Degraded("云端接口暂时不可用")
            }
        }

    // ---- 凭据与客户端 ----

    /**
     * 构造带自愈能力的客户端。
     *
     * 401 时用 passToken 换一套新凭据再重试，[MiHealthClient] 只负责「什么时候该续期」，
     * 续期本身在这里做——这样插件把续期逻辑完全握在自己手里，
     * 宿主不需要知道 passToken 是什么。
     */
    private suspend fun clientFor(context: PluginContext): MiHealthClient? {
        val creds = loadCredentials(context) ?: return null
        if (!creds.isValid()) return null

        // 客户端需要在同步 lambda 里取凭据，而 CredentialStore 是 suspend 的，
        // 所以用一个可变单元格记住「当前这套」：续期成功后立刻换掉
        var current = creds

        return MiHealthClient(
            credentialsProvider = { current },
            onCredentialsExpired = {
                // 续期成功就写回，失败返回 null 让调用方感知已掉登录。
                // 这一段就是原内置实现里的 onCredentialsExpired → refreshWithPassToken，
                // 现在完全跑在插件内部，宿主不知道 passToken 的存在
                val fresh = auth.refreshWithPassToken(
                    passToken = current.passToken,
                    cUserId = current.cUserId,
                    account = current.account,
                )
                if (fresh != null) {
                    current = fresh
                    saveCredentials(context, fresh)
                }
                fresh
            },
        )
    }

    private suspend fun loadCredentials(context: PluginContext): MiHealthCredentials? {
        val ssecurity = context.credentials.get(SLOT_SSECURITY) ?: return null
        val serviceToken = context.credentials.get(SLOT_SERVICE_TOKEN) ?: return null
        return MiHealthCredentials(
            ssecurity = ssecurity,
            serviceToken = serviceToken,
            cUserId = context.credentials.get(SLOT_CUSER_ID).orEmpty(),
            passToken = context.credentials.get(SLOT_PASS_TOKEN).orEmpty(),
            account = context.credentials.get(SLOT_ACCOUNT).orEmpty(),
        )
    }

    private suspend fun saveCredentials(context: PluginContext, creds: MiHealthCredentials) {
        context.credentials.put(SLOT_SSECURITY, creds.ssecurity)
        context.credentials.put(SLOT_SERVICE_TOKEN, creds.serviceToken)
        context.credentials.put(SLOT_CUSER_ID, creds.cUserId)
        context.credentials.put(SLOT_PASS_TOKEN, creds.passToken)
        if (creds.account.isNotBlank()) {
            context.credentials.put(SLOT_ACCOUNT, creds.account)
        }
    }

    private fun dayRange(day: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        // 当天还没结束时，截止到现在，避免向服务端要未来时间段
        return start to minOf(end, System.currentTimeMillis())
    }

    /** 跨轮次保留的登录中间态 */
    private class PendingLogin(
        val stage: Stage,
        val captcha: MiHealthAuth.PendingCaptcha? = null,
        val twoFactor: MiHealthAuth.PendingTwoFactor? = null,
        val account: String = "",
        val password: String = "",
    )

    private enum class Stage { PASSWORD, CAPTCHA, TWO_FACTOR }

    private val auth = MiHealthAuth()

    /**
     * 登录串行锁。
     *
     * 登录是「读中间态 → 发请求 → 写中间态」的复合动作，中间有网络挂起点。
     * 不串行的话，两次并发的 `login` 会各自基于同一份旧中间态发请求，
     * 后完成的那次把先完成的结果覆盖掉。
     */
    private val loginMutex = Mutex()

    /** 当前登录流程的中间态；成功或失败后清空 */
    @Volatile
    private var pendingLogin: PendingLogin? = null

    companion object {
        private const val SLOT_ACCOUNT = "account"
        private const val SLOT_PASSWORD = "password"
        private const val SLOT_SSECURITY = "ssecurity"
        private const val SLOT_SERVICE_TOKEN = "service_token"
        private const val SLOT_CUSER_ID = "cuser_id"
        private const val SLOT_PASS_TOKEN = "pass_token"

        /** 一次性字段，不入 credentialSpec，只在当次登录中使用 */
        private const val SLOT_CAPTCHA = "captcha"
        private const val SLOT_OTP = "otp"

        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        private val VALUE_FIELDS = listOf("value", "step", "count", "data")
    }
}

package com.lifeboard.plugin.mihealth

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ButtonSpec
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.ChartPoint
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UiBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        // 下面这些统一是「一串采样点」的结构：`{time, <数值字段>}`。
        // 它们的差异只在 key、数值字段名和单位，所以用一个表驱动（见 SAMPLED），
        // 不再为每个指标各写一遍采集逻辑。
        MetricSpec(OBS_HEART_RATE, MetricBucket.COUNT, "心率", "bpm"),
        MetricSpec(OBS_SPO2, MetricBucket.COUNT, "血氧", "%"),
        MetricSpec(OBS_STRESS, MetricBucket.COUNT, "压力", ""),
        MetricSpec(OBS_CALORIES, MetricBucket.COUNT, "热量", "kcal"),
        MetricSpec(OBS_DISTANCE, MetricBucket.COUNT, "距离", "米"),
        MetricSpec(OBS_STAND, MetricBucket.COUNT, "站立", "次"),
        MetricSpec(OBS_INTENSITY, MetricBucket.COUNT, "中高强度活动", "分钟"),
        MetricSpec(OBS_WEIGHT, MetricBucket.COUNT, "体重", "kg"),
        MetricSpec(OBS_VO2_MAX, MetricBucket.COUNT, "VO2Max", "ml/kg/min"),
        MetricSpec(OBS_PAI, MetricBucket.COUNT, "PAI", ""),
        MetricSpec(OBS_BLOOD_SYSTOLIC, MetricBucket.COUNT, "收缩压", "mmHg"),
        MetricSpec(OBS_BLOOD_DIASTOLIC, MetricBucket.COUNT, "舒张压", "mmHg"),
        MetricSpec(OBS_BLOOD_SUGAR, MetricBucket.COUNT, "血糖", "mmol/L"),
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

            // 步数与睡眠在没数据时也要让详情页显示「暂无」，所以先登记再采。
            // 采样类指标在 collectSampled 内部登记
            context.reportMetricQueried(MetricBucket.STEPS.name, date)
            context.reportMetricQueried(MetricBucket.SLEEP_MINUTES.name, date)

            // 两个量纲各自独立：一个失败不该让另一个也丢
            runCatching {
                collectSteps(context, client, start, end, date)?.let {
                    out += PluginMetric(MetricBucket.STEPS.name, it)
                }
            }.onFailure {
                log("步数采集失败（$date）：${it.message ?: it::class.java.simpleName}", it)
            }

            runCatching {
                collectSleepMinutes(context, client, start, end, date)?.let {
                    out += PluginMetric(MetricBucket.SLEEP_MINUTES.name, it)
                }
            }.onFailure {
                log("睡眠采集失败（$date）：${it.message ?: it::class.java.simpleName}", it)
            }

            // 采样类指标：每个指标各自 try，一个接口 502 不该让其余的也拿不到。
            // 按 cloudKey 分组是为了复用网络请求——血压的收缩压/舒张压来自同一份数据
            SAMPLED.groupBy { it.cloudKey }.forEach { (cloudKey, specs) ->
                runCatching {
                    out += collectSampled(context, client, cloudKey, specs, start, end, date)
                }.onFailure {
                    log("$cloudKey 采集失败（$date）：${it.message ?: it::class.java.simpleName}", it)
                }
            }

            out
        }

    /**
     * 采集一组共用同一个小米 key 的采样类指标。
     *
     * 为什么按 `cloudKey` 聚合而不是按指标逐个查：`blood_pressure` 一次返回
     * 收缩压和舒张压，按指标各查一次等于把同一份数据请求两遍，
     * 在小小米的限流下这很容易把一次采集拖成几十秒。
     *
     * 原始条目全部上报给宿主，日值按 [SampledSpec.aggregate] 折算：
     * 心率取均值（累加心率没有意义），热量取累加（一天烧掉多少是有意义的量）。
     */
    private suspend fun collectSampled(
        context: PluginContext,
        client: MiHealthClient,
        cloudKey: String,
        specs: List<SampledSpec>,
        start: Long,
        end: Long,
        date: String,
    ): List<PluginMetric> {
        val points = client.getFitnessDataByTime(cloudKey, start, end)

        // 先统一登记「这些指标查过了」：即使 points 为空也要登记，
        // 详情页才能把「查了但没数据」的指标列出来并显示「暂无」。
        // 放在任何 return 之前是必须的——一 return 就会漏掉这一批
        specs.forEach { spec -> context.reportMetricQueried(spec.metricKey, date) }

        if (points.isEmpty()) return emptyList()

        val out = mutableListOf<PluginMetric>()

        specs.forEach { spec ->
            val raw = points.mapNotNull { point ->
                val at = MiHealthParser.timestamp(point, "time", "timestamp", "start_time")
                    ?: return@mapNotNull null
                // 事件型（fields 为空）：记录存在本身就是「发生过」，按 1 计。
                // 这类数据没有数值字段，用 number() 找任何名字都会得到 null
                val value = if (spec.fields.isEmpty()) {
                    1.0
                } else {
                    MiHealthParser.number(point, *spec.fields) ?: return@mapNotNull null
                }
                RawRecord(timestamp = at, value = value)
            }
            if (raw.isEmpty()) {
                log(
                    "$cloudKey 的 ${spec.fields.joinToString("/")} 解析不出值；" +
                        "首个点字段：${points.firstOrNull()?.let { p ->
                            MiHealthParser.payload(p).keys().asSequence().toList()
                        }}",
                )
                return@forEach
            }

            context.reportRawRecords(spec.metricKey, raw.sortedBy { it.timestamp }, date)

            val daily = when (spec.aggregate) {
                Aggregate.SUM -> raw.sumOf { it.value }
                Aggregate.MEAN -> raw.map { it.value }.average()
            }
            // 0 不算「有效日值」：接口对没有测量的日子会返回 0 值点，
            // 当成真实的 0 会把均值拉到地上，卡片的趋势图也会跟着失真
            if (daily > 0.0) {
                out += PluginMetric(spec.metricKey, daily)
            }
        }

        return out
    }

    /**
     * 当日步数。
     *
     * 步数接口返回的是**采样点**（通常按小时间隔），需要累加。
     * 数值在采样点的内层 `value` JSON 里，字段名是 `steps`，见 [MiHealthParser]。
     *
     * 聚合前把每个采样点原样上报给宿主：用户想核对「1253 步是怎么来的」时，
     * 需要的正是这些点，聚合之后就再也拿不到了。
     */
    private suspend fun collectSteps(
        context: PluginContext,
        client: MiHealthClient,
        start: Long,
        end: Long,
        date: String,
    ): Double? {
        val points = client.getFitnessDataByTime("steps", start, end)
        if (points.isEmpty()) {
            log("collectSteps($date) 接口返回 0 个采样点")
            return null
        }

        val raw = points.mapNotNull { point ->
            val at = MiHealthParser.timestamp(point, "time", "timestamp") ?: return@mapNotNull null
            val steps = MiHealthParser.number(point, *STEP_FIELDS) ?: return@mapNotNull null
            RawRecord(timestamp = at, value = steps)
        }
        log(
            "collectSteps($date) 采样点 ${points.size} 个，可解析 ${raw.size} 个；" +
                "首个点的字段：${points.firstOrNull()?.keys()?.asSequence()?.toList()}",
        )
        if (raw.isNotEmpty()) {
            context.reportRawRecords(
                MetricBucket.STEPS.name,
                raw.sortedBy { it.timestamp },
                date,
            )
        }

        // 没有有效数值的点按 0 处理：它们代表「这个时刻没记步」，不是数据缺失
        val sum = points.sumOf { MiHealthParser.number(it, *STEP_FIELDS) ?: 0.0 }
        return if (sum > 0) sum else null
    }

    /**
     * 当日睡眠分钟数。
     *
     * 睡眠结构与步数不同：每个点是一段 `{bedtime, wake_up_time}` 区间，
     * 时长按区间与自然日窗口的重叠部分累加。
     *
     * 口径是「**这一自然日里睡着的分钟数**」，不是「昨夜总睡眠」：
     * 23:00 睡到次日 07:00 会被拆成前一天 60 分钟 + 当天 420 分钟。
     * 这样两天不重复计数，详情页文案也必须如实写成「每日睡眠时长」。
     *
     * 查询窗口向前多取一天：前一晚入睡、当天凌晨结束的睡眠段，
     * 起点落在目标日之前，不向前取就会被窗口过滤掉。
     *
     * 每段区间的起止时间都上报给宿主：睡眠是「区间」而不是「单点」，
     * 只看一个 15.8 小时的总数根本看不出是哪几段睡出来的。
     */
    private suspend fun collectSleepMinutes(
        context: PluginContext,
        client: MiHealthClient,
        start: Long,
        end: Long,
        date: String,
    ): Double? {
        val points = client.getFitnessDataByTime("sleep", start - DAY_MILLIS, end)
        if (points.isEmpty()) return null

        var totalMillis = 0L
        val raw = mutableListOf<RawRecord>()

        points.forEach { p ->
            val s = MiHealthParser.timestamp(p, *SLEEP_START_FIELDS) ?: return@forEach
            val e = MiHealthParser.timestamp(p, *SLEEP_END_FIELDS) ?: return@forEach
            if (e > s) {
                // 只统计与当日窗口重叠的部分，避免跨天睡眠把前后两天都算满
                val overlapStart = maxOf(s, start)
                val overlapEnd = minOf(e, end)
                if (overlapEnd > overlapStart) {
                    totalMillis += overlapEnd - overlapStart
                    raw += RawRecord(
                        timestamp = s,
                        value = (overlapEnd - overlapStart) / 60_000.0,
                        // 起止时间都写进附注：拆到当天的那一段到底从几点到几点，
                        // 光看时长是推不出来的
                        detail = "${formatClock(s)} → ${formatClock(e)}",
                    )
                }
            }
        }

        if (raw.isNotEmpty()) {
            context.reportRawRecords(
                MetricBucket.SLEEP_MINUTES.name,
                raw.sortedBy { it.timestamp },
                date,
            )
        }

        return if (totalMillis > 0) totalMillis / 60_000.0 else null
    }

    /** 时间戳转本地 `HH:mm`，用于睡眠区间附注 */
    private fun formatClock(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

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

    // ---- 详情页 ----

    /**
     * 详情页内容：登录状态 + 步数 + 睡眠 + 手动同步按钮。
     *
     * 这里**不发网络请求**：详情页可能被频繁打开，每次进来都拉一次接口
     * 既慢又浪费。要更新数据请由用户点「立即同步」，走 [ActionResult.TriggerSync]。
     *
     * 数据读自宿主已落库的指标表——插件拿不到数据库入口，所以这里只能通过
     * [PluginContext] 提供的只读查询接口取数（见 [PluginContext.recentMetrics]）。
     *
     * 步数与睡眠各自独立成块：一个指标没数据只显示自己的空态，
     * 不会连带把另一个指标也藏起来。
     */
    override suspend fun detailUi(context: PluginContext): List<UiBlock> = withContext(Dispatchers.IO) {
        val auth = authState(context)
        val status = when (auth) {
            is AuthState.LoggedIn -> Triple("已登录", Tone.OK, auth.accountName.orEmpty())
            is AuthState.Expired -> Triple("登录即将过期", Tone.WARN, auth.message.orEmpty())
            AuthState.LoggedOut -> Triple("未登录", Tone.ERROR, "请回插件列表页登录")
        }

        val recentSteps = context.recentMetrics(STEPS_KEY, DAYS).takeLast(DAYS)
        val recentSleep = context.recentMetrics(SLEEP_KEY, DAYS).takeLast(DAYS)

        buildList {
            add(
                StatusCardBlock(
                    label = "登录状态",
                    value = status.first,
                    tone = status.second,
                    detail = status.third.ifBlank { null },
                ),
            )

            if (recentSteps.isNotEmpty()) {
                add(
                    StatusCardBlock(
                        label = "最近一次步数",
                        value = "${formatSteps(recentSteps.last().value)} 步",
                        tone = Tone.NEUTRAL,
                        detail = recentSteps.last().date,
                    ),
                )
                add(
                    ChartBlock(
                        title = "近 ${recentSteps.size} 日步数",
                        chart = BarChartSpec(
                            bars = recentSteps.map { ChartPoint(it.date.takeLast(5), it.value) },
                            unit = " 步",
                            // 最后一根通常是今天，高亮它让用户一眼找到当前位置
                            highlightIndex = recentSteps.lastIndex,
                        ),
                    ),
                )
            } else {
                add(
                    TextBlock(
                        title = "还没有步数数据",
                        body = "点下面的「立即同步」拉取今天的数据。",
                    ),
                )
            }

            if (recentSleep.isNotEmpty()) {
                add(
                    StatusCardBlock(
                        label = "最近一次睡眠",
                        value = formatHours(recentSleep.last().value),
                        tone = Tone.NEUTRAL,
                        detail = recentSleep.last().date,
                    ),
                )
                add(
                    ChartBlock(
                        title = "近 ${recentSleep.size} 日睡眠",
                        chart = LineChartSpec(
                            // 库里存的是分钟，图上换成小时才符合日常表达
                            points = recentSleep.map {
                                ChartPoint(it.date.takeLast(5), it.value / 60.0)
                            },
                            unit = " 小时",
                        ),
                    ),
                )
                add(
                    TextBlock(
                        title = "统计口径",
                        body = "按自然日累计睡眠时长，跨夜睡眠会拆到前后两天，" +
                            "所以这里显示的不是「昨夜总睡眠」。",
                    ),
                )
            } else {
                add(
                    TextBlock(
                        title = "还没有睡眠数据",
                        body = "戴手环睡觉后点「立即同步」，睡眠才会被同步过来。",
                    ),
                )
            }

            add(
                ButtonRowBlock(
                    buttons = listOf(
                        ButtonSpec(
                            label = "立即同步",
                            actionId = ACTION_SYNC,
                            primary = true,
                            // 没登录时同步必然失败，直接禁用比让用户点了再看报错好
                            enabled = auth is AuthState.LoggedIn,
                        ),
                        ButtonSpec(
                            label = "回补近 $BACKFILL_DAYS 天",
                            actionId = ACTION_BACKFILL,
                            // 回补只补原始条目（供查看核对），不改历史聚合值与得分
                            enabled = auth is AuthState.LoggedIn,
                        ),
                    ),
                ),
            )
        }
    }

    override suspend fun onAction(context: PluginContext, actionId: String): ActionResult =
        when (actionId) {
            // 交给宿主跑采集链路：插件不碰数据库，否则健康度与量纲校验都会被绕过
            ACTION_SYNC -> ActionResult.TriggerSync
            ACTION_BACKFILL -> backfillHistory(context)
            else -> ActionResult.NoOp
        }

    /**
     * 回补最近 [BACKFILL_DAYS] 天的**原始数据**。
     *
     * ## 为什么单独做一个动作
     *
     * 常规采集只跑「昨天 + 今天」，更早的日期从来没有原始条目——
     * 用户想在详情页看上周的步数采样点时，库里的数据是不存在的。
     * 服务端本身支持按任意日期查询（见 [MiHealthClient.getFitnessDataByTime]），
     * 所以这里按天逐段补齐。
     *
     * ## 为什么不一次拉一个大窗口
     *
     * 窗口越宽，服务端游标越容易退化成遍历全部历史（慢且可能卡死）。
     * 按天分段每次只有一个自然日的窗口，稳定得多；代价是多几次请求。
     *
     * ## 为什么只补原始条目、不补 metric
     *
     * `metric` 是判定与计分用的聚合值，补历史会改动已定稿的历史得分，
     * 那不是用户点这个按钮的本意。这里只把原始条目补齐，供核对与查看。
     *
     * 失败的那一天跳过并继续：一天拉不到不该让整轮回补失败。
     */
    private suspend fun backfillHistory(context: PluginContext): ActionResult =
        withContext(Dispatchers.IO) {
            val client = clientFor(context)
                ?: return@withContext ActionResult.ShowMessage("未登录小米账号，请先在插件页登录")

            val today = LocalDate.now()
            var filled = 0
            var failed = 0

            // 从今天往前逐天补：今天/昨天本来就有，但重拉一次能保证是最新值
            (0 until BACKFILL_DAYS).forEach { offset ->
                val day = today.minusDays(offset.toLong())
                val result = runCatching { fetchRawForDay(context, client, day) }
                if (result.isSuccess) filled++ else failed++
            }

            ActionResult.ShowMessage(
                if (failed == 0) {
                    "已回补最近 $filled 天的原始数据"
                } else {
                    "已回补 $filled 天，$failed 天没取到数据"
                },
            )
        }

    /**
     * 拉取某一天的原始条目并上报（不产出 [PluginMetric]）。
     *
     * 与 [fetch] 共用同一套采集与解析逻辑，只是不返回聚合值——
     * 两边口径必须一致，否则「回补出来的原始条目」和「卡片上的数字」对不上。
     */
    private suspend fun fetchRawForDay(
        context: PluginContext,
        client: MiHealthClient,
        day: LocalDate,
    ) {
        val (start, end) = dayRange(day)
        val date = day.toString()
        // 两个指标各自独立：一个失败不该让另一个也丢
        runCatching { collectSteps(context, client, start, end, date) }
        runCatching { collectSleepMinutes(context, client, start, end, date) }
    }

    private fun formatSteps(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.0f", value)

    /** 分钟转小时，保留一位小数：`445.0` → `7.4 小时` */
    private fun formatHours(minutes: Double): String = String.format("%.1f 小时", minutes / 60.0)

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

    /**
     * 插件自身的诊断日志。
     *
     * 只落到 logcat，不走 `PluginContext`：契约里没有日志通道，
     * 而这些信息只在排查采集问题时有用，进宿主日志会污染用户能看到的记录。
     */
    private fun log(message: String, error: Throwable? = null) {
        android.util.Log.w("LifeBoardMiHealth", message, error)
    }

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

        /** 详情页趋势图的回看天数：一周足够看出趋势，再多柱子就挤了 */
        private const val DAYS = 7

        /** 步数指标的 key，与 [metrics] 里声明的保持一致 */
        private val STEPS_KEY = MetricBucket.STEPS.name

        /** 睡眠指标的 key */
        private val SLEEP_KEY = MetricBucket.SLEEP_MINUTES.name

        // ---- 采样类指标 ----
        //
        // key 用 `OBS_` 前缀而不是直接叫 `HEART_RATE`：metricKey 会落进
        // `metric.sourceType`（`PLUGIN_{id}:{key}`），与内置源同表共存时
        // 加个前缀能一眼看出这是插件采的观测值，不和内置指标撞名。

        private const val OBS_HEART_RATE = "OBS_HEART_RATE"
        private const val OBS_SPO2 = "OBS_SPO2"
        private const val OBS_STRESS = "OBS_STRESS"
        private const val OBS_CALORIES = "OBS_CALORIES"
        private const val OBS_DISTANCE = "OBS_DISTANCE"
        private const val OBS_STAND = "OBS_STAND"
        private const val OBS_INTENSITY = "OBS_INTENSITY"
        private const val OBS_WEIGHT = "OBS_WEIGHT"
        private const val OBS_VO2_MAX = "OBS_VO2_MAX"
        private const val OBS_PAI = "OBS_PAI"
        private const val OBS_BLOOD_SYSTOLIC = "OBS_BLOOD_SYSTOLIC"
        private const val OBS_BLOOD_DIASTOLIC = "OBS_BLOOD_DIASTOLIC"
        private const val OBS_BLOOD_SUGAR = "OBS_BLOOD_SUGAR"

        /**
         * 一个「采样点」型指标：小米接口里一条记录就是一个时间点上的一个数值。
         *
         * @param cloudKey 小米接口的 key
         * @param metricKey 落库用的指标键
         * @param fields 数值字段候选名，按顺序取第一个有效的
         * @param aggregate 一天多条时怎么折算成一个日值：取均值还是累加
         */
        private data class SampledSpec(
            val cloudKey: String,
            val metricKey: String,
            val fields: Array<String>,
            val aggregate: Aggregate,
        )

        private enum class Aggregate {
            /** 累加：步数、热量、距离这类「一整天累计多少」的量 */
            SUM,

            /** 取均值：心率、血氧、压力这类「某个时刻的读数」，累加没有物理意义 */
            MEAN,
        }

        /**
         * 采样类指标的采集清单。
         *
         * 血压被拆成收缩压/舒张压两行：接口把它们放在同一条记录里，
         * 但两者的量纲与正常范围完全不同，合成一个指标就没法分别算趋势。
         * 拆开后共用一次网络请求（见 [collectSampled] 的 points 复用）。
         *
         * [SampledSpec.fields] 留空表示「事件型」：这类记录本身没有数值字段
         * （实测 `valid_stand` 只有 `{start_time,end_time}`、`intensity` 只有 `{time}`），
         * 一条记录就代表「这一刻发生过」，按 1 计。
         *
         * 没有列进这里的 key（如 `menstruation`、`headset`）是结构不同的数据，
         * 硬按 `{time, value}` 解析只会得到一堆空值。
         */
        private val SAMPLED: List<SampledSpec> = listOf(
            SampledSpec("heart_rate", OBS_HEART_RATE, arrayOf("bpm", "heart_rate"), Aggregate.MEAN),
            SampledSpec("spo2", OBS_SPO2, arrayOf("spo2"), Aggregate.MEAN),
            SampledSpec("stress", OBS_STRESS, arrayOf("stress_value", "stress"), Aggregate.MEAN),
            SampledSpec("calories", OBS_CALORIES, arrayOf("calories"), Aggregate.SUM),
            // steps 记录里也带 distance，但单独查 distance 更准：
            // 不是每次记步都会写距离，混在一起会把距离算少
            SampledSpec("distance", OBS_DISTANCE, arrayOf("distance"), Aggregate.SUM),
            // 事件型：有记录即站过一小时，没有数值字段可读
            SampledSpec("valid_stand", OBS_STAND, emptyArray(), Aggregate.SUM),
            // 事件型：记录存在即代表该时刻处于中高强度活动
            SampledSpec("intensity", OBS_INTENSITY, emptyArray(), Aggregate.SUM),
            SampledSpec("weight", OBS_WEIGHT, arrayOf("weight"), Aggregate.MEAN),
            SampledSpec("vo2_max", OBS_VO2_MAX, arrayOf("vo2_max"), Aggregate.MEAN),
            SampledSpec("pai", OBS_PAI, arrayOf("pai", "value"), Aggregate.MEAN),
            SampledSpec("blood_pressure", OBS_BLOOD_SYSTOLIC, arrayOf("systolic"), Aggregate.MEAN),
            SampledSpec("blood_pressure", OBS_BLOOD_DIASTOLIC, arrayOf("diastolic"), Aggregate.MEAN),
            SampledSpec("blood_sugar", OBS_BLOOD_SUGAR, arrayOf("value", "blood_sugar"), Aggregate.MEAN),
        )

        /** 详情页「立即同步」按钮的标识 */
        private const val ACTION_SYNC = "sync_now"

        /** 详情页「回补历史」按钮的标识 */
        private const val ACTION_BACKFILL = "backfill"

        /**
         * 回补历史的天数。
         *
         * 30 天是取舍：再短看不出「这个月步数趋势」（详情页的时间段筛选会没东西可选），
         * 再长每天一次请求会让回补要等很久。用户点一次按钮就能覆盖一个月的核对需求。
         */
        private const val BACKFILL_DAYS = 30

        /**
         * 步数采样点的候选数值字段。
         *
         * `steps` 是参考实现实际使用的字段名；其余是历史兼容，
         * 按顺序取第一个有效值，避免宽泛字段盖掉真实步数。
         */
        private val STEP_FIELDS = arrayOf("steps", "step", "count", "value")

        /** 睡眠区间的开始时间候选字段，参考宿主 `MiHealthOverviewReader.summarizeSleep` */
        private val SLEEP_START_FIELDS = arrayOf(
            "bedtime",
            "device_bedtime",
            "start_time",
            "startTime",
        )

        /** 睡眠区间的结束时间候选字段 */
        private val SLEEP_END_FIELDS = arrayOf(
            "device_wake_up_time",
            "wake_up_time",
            "end_time",
            "endTime",
        )
    }
}

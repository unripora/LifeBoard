package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.plugin.api.Align
import com.lifeboard.plugin.api.AudioNode
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.ChartNode
import com.lifeboard.plugin.api.ChartPoint
import com.lifeboard.plugin.api.ChartSpec
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatNode
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.CodeNode
import com.lifeboard.plugin.api.CollapsibleNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.DividerNode
import com.lifeboard.plugin.api.Emphasis
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.ImageNode
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.KeyValue
import com.lifeboard.plugin.api.KeyValueNode
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricAggregate
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginError
import com.lifeboard.plugin.api.PluginErrorCode
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.PickerCandidate
import com.lifeboard.plugin.api.PickerMode
import com.lifeboard.plugin.api.PickerNode
import com.lifeboard.plugin.api.ProgressNode
import com.lifeboard.plugin.api.ProgressRingSpec
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SelectOption
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.Spacing
import com.lifeboard.plugin.api.SpacerNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TableColumn
import com.lifeboard.plugin.api.TableNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.ToggleNode
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.plugin.api.VideoNode
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * JS 侧 JSON 与插件契约类型之间的转换。
 *
 * 设计取舍：JS 与 Kotlin 之间只走 JSON 文本，不尝试做通用反射映射。
 * 原因有三：
 * 1. 契约类型都是封闭的（sealed / data class），逐个显式转换比反射更可控，
 *    字段改名时编译期就能发现；
 * 2. 插件是外部写的，不能假设它一定给出合法结构，必须能对每个字段做兜底；
 * 3. 以后要加字段时只改这一个文件，不会散落到各处。
 *
 * 转换方向以 JS → Kotlin 为主（插件返回值）；少数需要 Kotlin → JS 的
 * （如把凭据槽位交给插件）在别处处理。
 *
 * 解析失败一律不抛异常，而是返回安全的默认值：插件返回畸形数据时，
 * 用户看到的是「没有内容」而不是整个插件崩掉。
 *
 * ## 迁移说明
 *
 * 本文件从 Android 模块原样搬到 `jvmMain`，唯一改动是
 * `org.json.JSONObject/JSONArray` → [PluginJsonObject]/[PluginJsonArray]。
 * 两者的 `opt*` 语义已逐条对齐（见 `PluginJson.kt` 的语义约定与两端测试），
 * 所以这里**一行判断逻辑都没动**——那五百多行兜底是插件的容错边界，
 * 重写等于把已跑通的行为推倒重来。
 */
object JsTypeConverter {

    // ---------- 登录 ----------

    /**
     * 解析 login 返回值。
     *
     * 约定：
     * - `{"type":"success"}` → [LoginResult.Success]
     * - `{"type":"needInput","prompt":..,"fields":[..],"captchaImage":..}` → [LoginResult.NeedInput]
     * - `{"type":"failure","message":..}` → [LoginResult.Failure]
     */
    fun toLoginResult(json: String): LoginResult {
        val obj = jsonObjectOrNull(json) ?: return LoginResult.Failure("插件返回了非法结果")
        return when (obj.optString("type")) {
            "success" -> LoginResult.Success
            "needInput" -> LoginResult.NeedInput(
                prompt = obj.optString("prompt", "需要补充信息"),
                fields = obj.optJSONArray("fields").toInputFields(),
                captchaImage = obj.optStringOrNull("captchaImage"),
            )
            // failure 与未知类型都按失败处理：宁可让用户看到原因，也不要静默成功
            else -> LoginResult.Failure(
                obj.optString("message").ifBlank { "登录失败（插件未说明原因）" }
            )
        }
    }

    private fun PluginJsonArray?.toInputFields(): List<InputField> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { return@mapNotNull null }
            InputField(
                name = name,
                displayName = o.optString("displayName").ifBlank { name },
                secret = o.optBoolean("secret", false),
                hint = o.optString("hint"),
            )
        }
    }

    // ---------- 登录态 ----------

    /**
     * 约定：
     * - `{"state":"loggedOut"}` → [AuthState.LoggedOut]
     * - `{"state":"loggedIn","accountName":..}` → [AuthState.LoggedIn]
     * - `{"state":"expired","message":..}` → [AuthState.Expired]
     */
    fun toAuthState(json: String): AuthState {
        val obj = jsonObjectOrNull(json) ?: return AuthState.LoggedOut
        return when (obj.optString("state")) {
            "loggedIn" -> AuthState.LoggedIn(obj.optStringOrNull("accountName"))
            "expired" -> AuthState.Expired(obj.optStringOrNull("message"))
            else -> AuthState.LoggedOut
        }
    }

    // ---------- 采集结果 ----------

    /**
     * 解析 fetch 返回值。
     *
     * ## 兼容两种写法
     *
     * - **新写法（推荐）**：带 `status` 的对象，明确表达失败类别
     * - **裸数组**：`[{metricKey, value}, ...]`，等同于 `status: 'success'`。
     *   大量就地写的小脚本都是这样返回的，逼它们改写只会平添迁移成本。
     *
     * ## 不认识的 status 按 failure 处理而不是当成成功
     *
     * 把未知状态当成功，会让「插件写错了字段名」表现成「今天没有数据」，
     * 而这正是本契约要消灭的歧义。
     */
    fun toFetchResult(json: String): FetchResult {
        val trimmed = json.trim()
        return when {
            trimmed.startsWith("[") -> {
                val arr = jsonArrayOrNull(trimmed)
                    ?: return FetchResult.Failure("fetch 返回的数组无法解析")
                FetchResult.Success(toPluginMetrics(arr))
            }
            trimmed.startsWith("{") -> {
                val obj = jsonObjectOrNull(trimmed)
                    ?: return FetchResult.Failure("fetch 返回的对象无法解析")
                obj.toFetchResult()
            }
            else -> FetchResult.Failure("fetch 返回值既不是数组也不是对象")
        }
    }

    private fun PluginJsonObject.toFetchResult(): FetchResult = when (optString("status")) {
        "success" ->
            FetchResult.Success(toPluginMetrics(optJSONArray("metrics") ?: PluginJsonArray()))

        "empty" ->
            FetchResult.Empty(optString("reason", "当日无记录"))

        "temporary" ->
            FetchResult.TemporaryFailure(
                message = optString("message", "暂时取不到数据"),
                // 0 与缺省都表示「没给建议」，交给宿主决定退避间隔
                retryAfterSeconds = optLong("retryAfterSeconds", 0L).takeIf { it > 0 },
                // 插件给了结构化错误就用它；旧插件不给则保持 null，
                // 由上层按 UPSTREAM_ERROR 兜底（见 errorOf）
                error = optError(),
            )

        "authRequired" ->
            FetchResult.AuthenticationRequired(
                message = optString("message", "登录已过期，请重新登录"),
                error = optError(),
            )

        "failure" ->
            FetchResult.Failure(
                message = optString("message", "采集失败"),
                error = optError(),
            )

        else -> FetchResult.Failure(
            "fetch 返回了无法识别的 status：\"${optString("status")}\"，" +
                "应为 success/empty/temporary/authRequired/failure"
        )
    }

    /**
     * 解析可选的 `error` 对象。
     *
     * 插件没给、或给的不是对象时返回 `null`——调用方据此按旧语义推断错误码，
     * 不能把「没给错误」当成「没有错误」。
     */
    private fun PluginJsonObject.optError(): PluginError? {
        val o = optJSONObject("error") ?: return null
        return PluginError(
            code = PluginErrorCode.fromString(o.optString("code")),
            message = o.optString("message").ifBlank { optString("message") },
            retryAfterSec = o.optLong("retryAfterSec", 0L).takeIf { it > 0 },
            operation = o.optString("operation"),
            reason = o.optStringOrNull("reason"),
            limitKind = o.optStringOrNull("limitKind"),
        )
    }

    /**
     * 取一次采集结果对应的结构化错误。
     *
     * 旧插件只给 status 时按契约推断：`authRequired` → `AUTH_REQUIRED`、
     * `temporary` → `UPSTREAM_ERROR`、`failure` → `INTERNAL_ERROR`。
     * 成功/无数据的调用方不应调用本方法。
     */
    fun errorOf(result: FetchResult): PluginError? = when (result) {
        is FetchResult.Success, is FetchResult.Empty -> null
        is FetchResult.TemporaryFailure ->
            result.error ?: PluginError(PluginErrorCode.UPSTREAM_ERROR, result.message, result.retryAfterSeconds)
        is FetchResult.AuthenticationRequired ->
            result.error ?: PluginError(PluginErrorCode.AUTH_REQUIRED, result.message)
        is FetchResult.Failure ->
            result.error ?: PluginError(PluginErrorCode.INTERNAL_ERROR, result.message)
    }

    // ---------- 指标 ----------

    /**
     * 解析指标数组。约定是数组：`[{"metricKey":"steps","value":5000}, ...]`
     *
     * 缺 metricKey 或 value 的条目直接丢弃：宿主后续会按 declaredKeys 校验，
     * 这里先挡掉结构不完整的数据，避免把 0 当成真实值写进库。
     */
    fun toPluginMetrics(arr: PluginJsonArray): List<PluginMetric> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("metricKey")
            if (key.isBlank() || !o.has("value")) return@mapNotNull null
            PluginMetric(metricKey = key, value = o.optDouble("value", Double.NaN))
                .takeIf { !it.value.isNaN() }
        }

    // ---------- 健康度 ----------

    /**
     * 约定：
     * - `{"status":"healthy"}` → [PluginHealth.Healthy]
     * - `{"status":"degraded","message":..}` → [PluginHealth.Degraded]
     * - `{"status":"broken","message":..}` → [PluginHealth.Broken]
     */
    fun toPluginHealth(json: String): PluginHealth {
        val obj = jsonObjectOrNull(json)
            ?: return PluginHealth.Broken("插件健康检查返回了非法结果")
        return when (obj.optString("status")) {
            "healthy" -> PluginHealth.Healthy
            "degraded" -> PluginHealth.Degraded(obj.optString("message", "存在隐患"))
            "broken" -> PluginHealth.Broken(obj.optString("message", "插件不可用"))
            else -> PluginHealth.Degraded("未知的健康状态")
        }
    }

    // ---------- 会话事件 ----------

    /**
     * 解析插件对会话事件的处置。
     *
     * 只认 `{"action":"stop","reason":"..."}`；其余一切（含未实现、返回空）
     * 都按「会话继续」处理——终止会话会让用户丢掉当前对话，
     * 必须是插件显式要求才做。
     */
    fun toSessionAction(json: String): SessionAction {
        val obj = jsonObjectOrNull(json) ?: return SessionAction.Continue
        return when (obj.optString("action")) {
            "stop" -> SessionAction.Stop(obj.optString("reason", "会话已结束"))
            else -> SessionAction.Continue
        }
    }

    // ---------- 按钮动作 ----------

    /**
     * 约定：
     * - `{"type":"refreshUi","root":{...}}` → [ActionResult.RefreshUi]
     * - `{"type":"refreshUi","nodes":[...]}` → 同上（数组包成一行）
     * - `{"type":"showMessage","text":..}` → [ActionResult.ShowMessage]
     * - `{"type":"triggerSync"}` → [ActionResult.TriggerSync]
     * - 其它 → [ActionResult.NoOp]
     */
    fun toActionResult(json: String): ActionResult {
        val obj = jsonObjectOrNull(json) ?: return ActionResult.NoOp
        return when (obj.optString("type")) {
            "refreshUi" -> {
                // 优先读 root（单个节点）；兼容 nodes 数组写法，
                // 但两者都不是旧区块格式——旧 type 会被降级成 UnsupportedNode
                val rootJson = obj.optJSONObject("root")?.toString()
                    ?: obj.optJSONArray("nodes")?.toString()
                    ?: "null"
                ActionResult.RefreshUi(root = toUiTree(rootJson).root)
            }
            "showMessage" -> ActionResult.ShowMessage(
                obj.optString("text").ifBlank { "已完成" }
            )
            "triggerSync" -> ActionResult.TriggerSync
            else -> ActionResult.NoOp
        }
    }

    // ---------- 声明信息（manifest → 契约类型） ----------

    fun toMetricSpecs(arr: PluginJsonArray?): List<MetricSpec> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("metricKey").ifBlank {
                o.optString("key").ifBlank { return@mapNotNull null }
            }
            // bucket 缺失或拼错时按 key 反查，再不行用 COUNT 兜底，
            // 保证不会因为一个字段拼错就丢掉整个指标的声明
            val bucket = o.optString("bucket").toBucketOrNull()
                ?: MetricSpec.bucketOf(key)
                ?: MetricBucket.COUNT
            MetricSpec(
                metricKey = key,
                bucket = bucket,
                displayName = o.optString("displayName").ifBlank {
                    o.optString("name").ifBlank { key }
                },
                unit = o.optString("unit").ifBlank { bucket.unit },
                // 认不出就按累加处理：与历史行为一致，不会让老插件的数据变少
                aggregate = MetricAggregate.of(o.optString("aggregate"))
                    ?: MetricAggregate.SUM,
            )
        }
    }

    fun toCredentialSpec(arr: PluginJsonArray?): CredentialSpec {
        if (arr == null) return CredentialSpec.NONE
        val slots = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifBlank {
                o.optString("slot").ifBlank { return@mapNotNull null }
            }
            CredentialSpec.Slot(
                name = name,
                displayName = o.optString("displayName").ifBlank {
                    o.optString("label").ifBlank { name }
                },
                required = o.optBoolean("required", true),
                secret = o.optBoolean("secret", false),
            )
        }
        return CredentialSpec(slots)
    }

    // ---------- 原始记录 ----------

    /** 解析 reportRawRecords 的参数：`[{"timestamp":..,"value":..,"detail":..}, ...]` */
    fun toRawRecords(arr: PluginJsonArray?): List<RawRecord> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("timestamp")) return@mapNotNull null
            RawRecord(
                timestamp = o.optLong("timestamp"),
                value = o.optDouble("value", 0.0),
                detail = o.optString("detail"),
            )
        }
    }

    // ---------- 工具 ----------
    //
    // jsonObjectOrNull / jsonArrayOrNull / optStringOrNull / toTone / utf8Size /
    // truncateDisplay / optMachineString 都在 JsUiParseSupport.kt 里（同包
    // internal），节点树解析与这里共用同一份实现，避免两边悄悄分叉。

    private fun String.toBucketOrNull(): MetricBucket? =
        MetricBucket.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    // ---------- 详情页 UI ----------

    /**
     * 解析一棵节点树时的预算。
     *
     * 单独一个可变对象而不是把计数散在递归参数里：递归返回后父层还要知道
     * 「总共用了多少」，用返回值传来传去会让每个分支都得拼一次。
     *
     * 定义留在本对象里、实现搬到 [JsUiNodeParser]：调用方用的仍是
     * `JsTypeConverter.UiTreeParseResult` 这个既有名字，不必关心拆分。
     */
    class UiParseBudget {
        var nodeCount: Int = 0
    }

    /**
     * 节点树解析结果。
     *
     * @property root 根节点；`null` 表示插件没有自有内容（或 JSON 完全不可用）
     * @property budget 实际用掉的节点数，用于日志与「是否被截断」的判定
     * @property rootCount 根层解析出的节点数（数组根时是元素个数）
     */
    data class UiTreeParseResult(
        val root: PluginNode?,
        val budget: UiParseBudget = UiParseBudget(),
        val rootCount: Int = 0,
    ) {
        /** 是否因超出 [MAX_NODES] 被截断——上层应给用户一句提示，而不是静默少画 */
        val truncated: Boolean get() = budget.nodeCount >= MAX_NODES
    }

    /**
     * 解析 `detailUi` 返回值，得到一棵可组合节点树。
     *
     * 实现在 [JsUiNodeParser]（节点树解析本身五百多行，与其余转换混在一起会远超
     * 单文件上限）。这里只留一个转调入口，调用方不需要知道它在哪个文件。
     */
    fun toUiTree(json: String): UiTreeParseResult = JsUiNodeParser.parse(json)

    // ---------- 解析上限 ----------
    //
    // 这些数字不是拍脑袋：传入的 JSON 来自沙箱里的插件，不能假设它有多克制。
    //
    // - MAX_DEPTH 12：正常 UI 嵌套极限是 4~5 层（卡片 > 列 > 行 > 文本）。
    //   12 留足余量，同时保证递归解析与 Compose 递归组合都不会爆栈。
    // - MAX_NODES 600：详情页不是无限滚动列表，600 个节点一帧能画完。
    //   超过就是在组装一个「应用」而不是一个详情页。
    // - MAX_CHILDREN_PER_CONTAINER 100：限制单层宽度，避免一个行里塞几千个图标
    //   把测量阶段卡死。
    // - MAX_LIST_ITEMS / MAX_TABLE_ROWS 200：长列表应由宿主虚拟化，
    //   而不是让插件一次传几万项。
    //
    // 这些常量对 [JsUiNodeParser] 可见（internal），因为上限必须是「一处定义、
    // 两边同一份」：拆文件时若各写一份，迟早会不一致。

    internal const val MAX_DEPTH = 12
    internal const val MAX_NODES = 600
    internal const val MAX_CHILDREN_PER_CONTAINER = 100
    internal const val MAX_LIST_ITEMS = 200
    internal const val MAX_TABLE_ROWS = 200

    /**
     * 对话节点一次最多接受的消息条数。
     *
     * 与 [MAX_LIST_ITEMS] 分开：对话是本轮的核心场景，200 条对「真实聊天记录」
     * 太紧（一个会话很容易超过），但也不能无限——渲染层是 LazyColumn，
     * 1000 条已足够覆盖日常会话，再多应由插件分页而不是一次塞进来。
     */
    internal const val MAX_CHAT_MESSAGES = 1000

    /** 字符串数组类字段（selectedValues / selectedIds / itemPayloads）的长度上限 */
    internal const val MAX_STRING_LIST_SIZE = 500

    // ---------- 单字段限额 ----------
    //
    // 机器字段（id / actionId / binding / submitTo）**绝不截断**：截断后的 id
    // 会指向一个不存在的目标，比丢弃更难排查。超限就丢弃该节点或该交互。
    // 展示文本则相反：截断只是少显示几个字，语义不坏，配一条告警即可。

    /** 机器字段上限：4 KiB 足够容纳任何正常 id/binding */
    internal const val MAX_MACHINE_FIELD_BYTES = 4 * 1024

    /** 展示文本上限：64 KiB（一屏文字远小于这个量级） */
    internal const val MAX_DISPLAY_TEXT_BYTES = 64 * 1024

    /** 代码正文上限：256 KiB */
    internal const val MAX_CODE_TEXT_BYTES = 256 * 1024

    /** 交互载荷上限：64 KiB；超限丢弃该交互，避免把超长文本当命令发出 */
    internal const val MAX_INTERACTION_PAYLOAD_BYTES = 64 * 1024
}

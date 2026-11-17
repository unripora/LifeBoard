package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ButtonSpec
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.ChartPoint
import com.lifeboard.plugin.api.ChartSpec
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.CustomImageBlock
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.ProgressRingSpec
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UiBlock
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
            )

        "authRequired" ->
            FetchResult.AuthenticationRequired(optString("message", "登录已过期，请重新登录"))

        "failure" ->
            FetchResult.Failure(optString("message", "采集失败"))

        else -> FetchResult.Failure(
            "fetch 返回了无法识别的 status：\"${optString("status")}\"，" +
                "应为 success/empty/temporary/authRequired/failure"
        )
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

    // ---------- 详情页 UI ----------

    /**
     * 解析 detailUi 返回值。每个元素带 `type` 字段区分区块种类。
     *
     * 不认识的类型直接跳过：旧宿主遇到新插件的区块时不该整个页面报错，
     * 只是少显示一块内容。
     */
    fun toUiBlocks(json: String): List<UiBlock> {
        val arr = jsonArrayOrNull(json) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { toUiBlock(it) }
        }
    }

    private fun toUiBlock(o: PluginJsonObject): UiBlock? = when (o.optString("type")) {
        "text" -> TextBlock(
            title = o.optString("title"),
            body = o.optString("body"),
            tone = o.optString("tone").toTone(),
        )
        "statusCard" -> StatusCardBlock(
            label = o.optString("label"),
            value = o.optString("value"),
            tone = o.optString("tone").toTone(),
            detail = o.optStringOrNull("detail"),
        )
        "buttonRow" -> ButtonRowBlock(
            buttons = o.optJSONArray("buttons").toButtonSpecs(),
        )
        "chart" -> o.optJSONObject("chart")?.let { c ->
            ChartBlock(
                title = o.optString("title"),
                chart = toChartSpec(c) ?: return null,
            )
        }
        "customImage" -> CustomImageBlock(
            dataUri = o.optString("dataUri"),
            aspectRatio = o.optDouble("aspectRatio", 1.6).toFloat(),
        )
        else -> null
    }

    private fun PluginJsonArray?.toButtonSpecs(): List<ButtonSpec> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val actionId = o.optString("actionId").ifBlank { return@mapNotNull null }
            ButtonSpec(
                label = o.optString("label").ifBlank { actionId },
                actionId = actionId,
                primary = o.optBoolean("primary", false),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }

    private fun toChartSpec(o: PluginJsonObject): ChartSpec? = when (o.optString("kind")) {
        "line" -> LineChartSpec(
            points = o.optJSONArray("points").toChartPoints(),
            unit = o.optString("unit"),
        )
        "bar" -> BarChartSpec(
            bars = o.optJSONArray("bars").toChartPoints(),
            unit = o.optString("unit"),
            highlightIndex = o.optInt("highlightIndex", -1),
        )
        "progressRing" -> ProgressRingSpec(
            fraction = o.optDouble("fraction", 0.0).toFloat(),
            centerText = o.optString("centerText"),
            subtitle = o.optStringOrNull("subtitle"),
        )
        else -> null
    }

    private fun PluginJsonArray?.toChartPoints(): List<ChartPoint> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            ChartPoint(label = o.optString("label"), value = o.optDouble("value", 0.0))
        }
    }

    // ---------- 按钮动作 ----------

    /**
     * 约定：
     * - `{"type":"refreshUi","blocks":[..]}` → [ActionResult.RefreshUi]
     * - `{"type":"showMessage","text":..}` → [ActionResult.ShowMessage]
     * - `{"type":"triggerSync"}` → [ActionResult.TriggerSync]
     * - 其它 → [ActionResult.NoOp]
     */
    fun toActionResult(json: String): ActionResult {
        val obj = jsonObjectOrNull(json) ?: return ActionResult.NoOp
        return when (obj.optString("type")) {
            "refreshUi" -> ActionResult.RefreshUi(
                blocks = obj.optJSONArray("blocks").let { arr ->
                    if (arr == null) emptyList() else toUiBlocks(arr.toString())
                },
            )
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

    private fun jsonObjectOrNull(json: String): PluginJsonObject? =
        runCatching { PluginJsonObject(json) }.getOrNull()

    private fun jsonArrayOrNull(json: String): PluginJsonArray? =
        runCatching { PluginJsonArray(json) }.getOrNull()

    private fun PluginJsonObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }

    private fun String.toTone(): Tone =
        Tone.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: Tone.NEUTRAL

    private fun String.toBucketOrNull(): MetricBucket? =
        MetricBucket.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
}

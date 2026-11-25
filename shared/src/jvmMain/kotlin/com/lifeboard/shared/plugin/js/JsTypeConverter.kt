package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ActionResult
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
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetric
import com.lifeboard.plugin.api.PluginNode
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
     * 解析 detailUi 返回值，得到一棵可组合节点树。
     *
     * ## 接受三种根形态
     *
     * - 单个节点对象：`{"type":"column","children":[...]}`
     * - 节点数组：`[{...},{...}]` → 自动包成一层 [ColumnNode]
     * - 空/invalid：返回 [UiTreeParseResult.root] = null
     *
     * 数组形态是给「只想平铺几个卡片」的插件留的便利入口。它不构成兼容层——
     * 数组里的每个元素仍必须是**新节点**，旧 `type:'conversation'` 之类
     * 会被转成 [UnsupportedNode]，不会静默丢弃。
     *
     * ## 为什么返回结果对象而不是裸树
     *
     * 解析过程可能因超出安全上限而截断，也可能遇到宿主不认识的 type。
     * 这两种情况都需要让上层记日志/提示用户，而不是悄悄少画几块。
     */
    fun toUiTree(json: String): UiTreeParseResult {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return UiTreeParseResult(null)

        val budget = UiParseBudget()

        // 数组根：包一层不套卡片的 column，让插件可以只返回平铺的几块
        val arr = jsonArrayOrNull(trimmed)
        if (arr != null) {
            val children = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { toNode(it, 1, budget) }
            }
            val root = if (children.isEmpty()) {
                null
            } else {
                ColumnNode(children = children, card = false, spacing = Spacing.NORMAL)
            }
            return UiTreeParseResult(root, budget, children.size)
        }

        val obj = jsonObjectOrNull(trimmed) ?: return UiTreeParseResult(null)
        val root = toNode(obj, 0, budget)
        return UiTreeParseResult(root, budget, if (root == null) 0 else 1)
    }

    /**
     * 递归解析单个节点。
     *
     * ## 三层防御
     *
     * 1. **节点总数上限**：超过 [MAX_NODES] 后直接返回 null，不再构造。
     *    传入的 JSON 来自沙箱里的插件，不能假设它有多克制。
     * 2. **嵌套深度上限**：超过 [MAX_DEPTH] 时把这一支换成
     *    [UnsupportedNode]（占位）而不是丢弃——兄弟节点不受影响，
     *    用户还能看到「这里本来有东西」。深度必须在**构造前**判断，
     *    否则深嵌套 JSON 在 `PluginJsonObject` 构造阶段就吃掉栈了。
     * 3. **未知 type**：转成 [UnsupportedNode]，不再像旧实现那样静默丢弃。
     */
    private fun toNode(o: PluginJsonObject, depth: Int, budget: UiParseBudget): PluginNode? {
        if (budget.nodeCount >= MAX_NODES) return null
        if (depth > MAX_DEPTH) return UnsupportedNode(type = "嵌套过深", id = o.optStringOrNull("id"))

        val type = o.optString("type")
        // 没有 type 的对象不是一个节点：`{}` 这类内容当作「什么都没说」，
        // 而不是造一个「暂不支持 ?」的占位去占版面
        if (type.isBlank()) return null
        val id = o.optStringOrNull("id")
        val locked = o.optBoolean("locked", false)

        val node = when (type) {
            // ---- 容器 ----
            "column" -> ColumnNode(
                children = o.optNodes("children", depth, budget),
                spacing = o.optString("spacing").toSpacing(),
                card = o.optBoolean("card", true),
                title = o.optString("title"),
            )
            "row" -> RowNode(
                children = o.optNodes("children", depth, budget),
                spacing = o.optString("spacing").toSpacing(),
                wrap = o.optBoolean("wrap", true),
            )
            "collapsible" -> CollapsibleNode(
                title = o.optString("title"),
                children = o.optNodes("children", depth, budget),
                collapsed = o.optBoolean("collapsed", true),
                summary = o.optString("summary"),
            )
            "spacer" -> SpacerNode(size = o.optString("size").toSpacing())
            "divider" -> DividerNode()

            // ---- 内容 ----
            "text" -> TextNode(
                title = o.optString("title"),
                body = o.optBoundString("body"),
                tone = o.optString("tone").toTone(),
                emphasis = o.optString("emphasis").toEmphasis(),
            )
            "statusCard" -> StatusCardNode(
                label = o.optString("label"),
                value = o.optBoundString("value"),
                tone = o.optString("tone").toTone(),
                detail = o.optString("detail"),
            )
            "chart" -> {
                // 认不出 kind 时保留节点并降级，旧实现是整块丢掉——
                // 用户至少该知道「这里本来有个图」
                val spec = o.optJSONObject("chart")?.let { toChartSpec(it) }
                if (spec == null) {
                    UnsupportedNode(type = "chart:${o.optJSONObject("chart")?.optString("kind") ?: "?"}", id = id)
                } else {
                    ChartNode(chart = spec, title = o.optString("title"))
                }
            }
            "image" -> ImageNode(
                dataUri = o.optString("dataUri"),
                aspectRatio = o.optDouble("aspectRatio", 1.6).toFloat(),
                contentDescription = o.optString("contentDescription"),
            )
            "progress" -> ProgressNode(
                fraction = o.optDouble("fraction", 0.0).toFloat(),
                label = o.optString("label"),
                valueText = o.optString("valueText"),
                tone = o.optString("tone").toTone(),
            )
            "code" -> CodeNode(
                code = o.optString("code"),
                language = o.optString("language"),
            )

            // ---- 集合 ----
            "list" -> ListNode(
                items = o.optBoundItems(depth, budget),
                dense = o.optBoolean("dense", false),
                separator = o.optBoolean("separator", true),
                maxHeightDp = o.optInt("maxHeightDp", 0),
                emptyHint = o.optString("emptyHint").ifBlank { "暂无内容" },
            )
            "table" -> TableNode(
                columns = o.optJSONArray("columns").toTableColumns(),
                rows = o.optJSONArray("rows").toTableRows(),
            )
            "keyValue" -> KeyValueNode(entries = o.optJSONArray("entries").toKeyValues())

            // ---- 交互 ----
            "button" -> ButtonNode(
                label = o.optString("label"),
                actionId = o.optStringOrNull("actionId"),
                primary = o.optBoolean("primary", false),
                enabled = o.optBoolean("enabled", true),
                payload = o.optStringOrNull("payload"),
            )
            "input" -> o.optString("actionId").takeIf { it.isNotBlank() }?.let { actionId ->
                InputNode(
                    actionId = actionId,
                    placeholder = o.optString("placeholder").ifBlank { "输入内容" },
                    submitLabel = o.optString("submitLabel").ifBlank { "提交" },
                    multiline = o.optBoolean("multiline", true),
                    enabled = o.optBoundBoolean("enabled", true),
                    submitTo = o.optString("submitTo").takeIf { it.isNotBlank() }?.let { BindingRef.parseTarget(it) },
                )
            }
            "select" -> o.optString("actionId").takeIf { it.isNotBlank() }?.let { actionId ->
                val options = o.optJSONArray("options").toSelectOptions()
                // 一个选项都没有的下拉框点了没反应，不如不渲染
                if (options.isEmpty()) null else SelectNode(
                    actionId = actionId,
                    options = options,
                    label = o.optString("label"),
                    multiple = o.optBoolean("multiple", false),
                    submitLabel = o.optString("submitLabel"),
                    enabled = o.optBoolean("enabled", true),
                )
            }
            "toggle" -> o.optString("actionId").takeIf { it.isNotBlank() }?.let { actionId ->
                ToggleNode(
                    actionId = actionId,
                    label = o.optString("label"),
                    checked = o.optBoolean("checked", false),
                    enabled = o.optBoolean("enabled", true),
                )
            }

            // ---- 媒体（本期只降级）----
            "audio" -> o.optMediaSource()?.let { src ->
                AudioNode(
                    source = src,
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle"),
                    durationSeconds = o.optInt("durationSeconds", 0),
                )
            } ?: UnsupportedNode(type = "audio（地址不安全）", id = id)
            "video" -> o.optMediaSource()?.let { src ->
                VideoNode(
                    source = src,
                    posterDataUri = o.optString("posterDataUri"),
                    aspectRatio = o.optDouble("aspectRatio", 1.778).toFloat(),
                    title = o.optString("title"),
                    autoplay = o.optBoolean("autoplay", false),
                )
            } ?: UnsupportedNode(type = "video（地址不安全）", id = id)

            else -> UnsupportedNode(type = type, id = id)
        } ?: return null

        return node.withHostFields(id = id, locked = locked).also { budget.nodeCount++ }
    }

    /**
     * 把插件声明的 id / locked 写到节点上。
     *
     * 旧实现是在 9 个解析分支里各自 `copy(id = ...)`，新增第 10 个分支时
     * 一定会漏，而漏了的表现是「排序偏好不生效」——离原因很远。
     * 收在一处后，新增节点时编译器会提示 `when` 不穷尽。
     */
    private fun PluginNode.withHostFields(id: String?, locked: Boolean): PluginNode = when (this) {
        is ColumnNode -> copy(id = id, locked = locked)
        is RowNode -> copy(id = id, locked = locked)
        is CollapsibleNode -> copy(id = id, locked = locked)
        is SpacerNode -> copy(id = id, locked = locked)
        is DividerNode -> copy(id = id, locked = locked)
        is TextNode -> copy(id = id, locked = locked)
        is StatusCardNode -> copy(id = id, locked = locked)
        is ChartNode -> copy(id = id, locked = locked)
        is ImageNode -> copy(id = id, locked = locked)
        is ProgressNode -> copy(id = id, locked = locked)
        is CodeNode -> copy(id = id, locked = locked)
        is UnsupportedNode -> copy(id = id, locked = locked)
        is ListNode -> copy(id = id, locked = locked)
        is TableNode -> copy(id = id, locked = locked)
        is KeyValueNode -> copy(id = id, locked = locked)
        is ButtonNode -> copy(id = id, locked = locked)
        is InputNode -> copy(id = id, locked = locked)
        is SelectNode -> copy(id = id, locked = locked)
        is ToggleNode -> copy(id = id, locked = locked)
        is AudioNode -> copy(id = id, locked = locked)
        is VideoNode -> copy(id = id, locked = locked)
    }

    /** 递归解析 children；单层子节点数超限时截断，避免一个 row 里塞几千个节点 */
    private fun PluginJsonObject.optNodes(
        key: String,
        depth: Int,
        budget: UiParseBudget,
    ): List<PluginNode> {
        val arr = optJSONArray(key) ?: return emptyList()
        val limit = minOf(arr.length(), MAX_CHILDREN_PER_CONTAINER)
        return (0 until limit).mapNotNull { i ->
            arr.optJSONObject(i)?.let { toNode(it, depth + 1, budget) }
        }
    }

    // ---- 绑定点解析 ----

    /**
     * 解析一个可绑定字符串字段。
     *
     * 三种形态：缺失 → 静态默认空串；字符串 → 静态值；对象且有 `binding`
     * → 绑定引用（格式错则退回静态默认值）。
     *
     * 绑定的语法在这里校验，**语义**（source 认不认识、字段在不在）留给
     * 求值阶段——解析层不知道插件声明了什么数据源。
     */
    private fun PluginJsonObject.optBoundString(key: String): Bound<String> {
        val obj = optJSONObject(key)
        if (obj != null) {
            val ref = obj.optString("binding").takeIf { it.isNotBlank() }
            val parsed = ref?.let { BindingRef.parse(it) }
            return if (parsed == null) Bound.Static("") else Bound.Ref<String>(parsed.canonical())
        }
        return Bound.Static(optString(key))
    }

    /** 解析可绑定布尔字段（输入框 enabled） */
    private fun PluginJsonObject.optBoundBoolean(key: String, default: Boolean): Bound<Boolean> {
        val obj = optJSONObject(key)
        if (obj != null) {
            val ref = obj.optString("binding").takeIf { it.isNotBlank() }
            val parsed = ref?.let { BindingRef.parse(it) }
            return if (parsed == null) Bound.Static(default) else Bound.Ref<Boolean>(parsed.canonical())
        }
        return Bound.Static(optBoolean(key, default))
    }

    /**
     * 解析可绑定条目列表。
     *
     * 静态分支是 `List<List<PluginNode>>`（两层：条目 → 条目内的节点），
     * 让宿主能给每项插入分隔线与稳定 key。绑定分支指向会话消息源。
     */
    private fun PluginJsonObject.optBoundItems(depth: Int, budget: UiParseBudget): Bound<List<List<PluginNode>>> {
        val obj = optJSONObject("items")
        if (obj != null) {
            val ref = obj.optString("binding").takeIf { it.isNotBlank() }
            val parsed = ref?.let { BindingRef.parse(it) }
            return if (parsed == null) {
                Bound.Static(emptyList())
            } else {
                Bound.Ref<List<List<PluginNode>>>(parsed.canonical())
            }
        }
        val arr = optJSONArray("items") ?: return Bound.Static(emptyList())
        val limit = minOf(arr.length(), MAX_LIST_ITEMS)
        val items = (0 until limit).mapNotNull { i ->
            val item = arr.optJSONArray(i) ?: return@mapNotNull null
            val nodes = (0 until minOf(item.length(), MAX_CHILDREN_PER_CONTAINER)).mapNotNull { j ->
                item.optJSONObject(j)?.let { toNode(it, depth + 1, budget) }
            }
            nodes.takeIf { it.isNotEmpty() }
        }
        return Bound.Static(items)
    }

    /**
     * 媒体地址校验：只允许 `https://` 或 `data:`。
     *
     * 必须在这里拦：Android 9+ 默认禁止明文 HTTP，放着不管等于将来实现播放器
     * 时带着一个已知漏洞上线。返回 null 时上层降级为 [UnsupportedNode]。
     */
    private fun PluginJsonObject.optMediaSource(): String? {
        val src = optString("source").trim()
        if (src.isEmpty()) return null
        val lower = src.lowercase()
        return when {
            lower.startsWith("https://") -> src
            lower.startsWith("data:") -> src
            else -> null
        }
    }

    // ---- 小类型解析 ----

    private fun PluginJsonArray?.toTableColumns(): List<TableColumn> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            TableColumn(
                title = o.optString("title"),
                weight = o.optDouble("weight", 0.0).toFloat(),
                align = o.optString("align").toAlign(),
            )
        }
    }

    private fun PluginJsonArray?.toTableRows(): List<List<String>> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), MAX_TABLE_ROWS)).mapNotNull { i ->
            val row = optJSONArray(i) ?: return@mapNotNull null
            (0 until row.length()).map { j -> row.optStringOrNull(j).orEmpty() }
        }
    }

    private fun PluginJsonArray?.toKeyValues(): List<KeyValue> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            KeyValue(
                key = o.optString("key"),
                value = o.optString("value"),
                tone = o.optString("tone").toTone(),
            )
        }
    }

    private fun PluginJsonArray?.toSelectOptions(): List<SelectOption> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val value = o.optString("value").ifBlank { return@mapNotNull null }
            SelectOption(value = value, label = o.optString("label"))
        }
    }

    /** 解析图表的 kind；认不出返回 null，由调用方降级为 [UnsupportedNode] */
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

    private fun String.toSpacing(): Spacing = when (lowercase()) {
        "none" -> Spacing.NONE
        "compact" -> Spacing.COMPACT
        "loose" -> Spacing.LOOSE
        else -> Spacing.NORMAL
    }

    private fun String.toEmphasis(): Emphasis = when (lowercase()) {
        "heading" -> Emphasis.HEADING
        "caption" -> Emphasis.CAPTION
        else -> Emphasis.BODY
    }

    private fun String.toAlign(): Align = when (lowercase()) {
        "center" -> Align.CENTER
        "end" -> Align.END
        else -> Align.START
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

    /**
     * 解析一棵节点树时的预算。
     *
     * 单独一个可变对象而不是把计数散在递归参数里：递归返回后父层还要知道
     * 「总共用了多少」，用返回值传来传去会让每个分支都得拼一次。
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

    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 600
    private const val MAX_CHILDREN_PER_CONTAINER = 100
    private const val MAX_LIST_ITEMS = 200
    private const val MAX_TABLE_ROWS = 200
}

package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.Align
import com.lifeboard.plugin.api.AudioNode
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
import com.lifeboard.plugin.api.DividerNode
import com.lifeboard.plugin.api.Emphasis
import com.lifeboard.plugin.api.ImageNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.KeyValue
import com.lifeboard.plugin.api.KeyValueNode
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PickerCandidate
import com.lifeboard.plugin.api.PickerMode
import com.lifeboard.plugin.api.PickerNode
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.ProgressNode
import com.lifeboard.plugin.api.ProgressRingSpec
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SelectOption
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
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * 详情页节点树的解析：把插件返回的 JSON 变成封闭的 [PluginNode] 树。
 *
 * ## 为什么从 [JsTypeConverter] 拆出来
 *
 * 节点树这一块本身就有五百多行（容器 / 内容 / 对话 / 选择 / 集合 / 交互 / 媒体的
 * 全部分支，加上每个字段的兜底），与「登录 / 采集 / 健康度」那些转换混在一个文件
 * 里会远超单文件上限。拆开后两边都能独立阅读：这个文件只关心「一棵树怎么解析」。
 *
 * ## 三层防御（与拆分前完全一致）
 *
 * 1. **节点总数上限**：超过 `JsTypeConverter.MAX_NODES` 后不再构造；
 * 2. **嵌套深度上限**：超过 `JsTypeConverter.MAX_DEPTH` 时把这一支换成占位，
 *    兄弟节点不受影响；
 * 3. **未知 type**：转成 [UnsupportedNode]，不静默丢弃。
 *
 * 解析失败一律不抛异常：插件返回畸形数据时，用户看到的是「没有内容」，
 * 而不是整个插件崩掉。
 */
internal object JsUiNodeParser {
    // ---------- 详情页 UI ----------

    /**
     * 解析 detailUi 返回值，得到一棵可组合节点树。
     *
     * ## 接受三种根形态
     *
     * - 单个节点对象：`{"type":"column","children":[...]}`
     * - 节点数组：`[{...},{...}]` → 自动包成一层 [ColumnNode]
     * - 空/invalid：返回 `root = null`
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
    fun parse(json: String): JsTypeConverter.UiTreeParseResult {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JsTypeConverter.UiTreeParseResult(null)

        val budget = JsTypeConverter.UiParseBudget()

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
            return JsTypeConverter.UiTreeParseResult(root, budget, children.size)
        }

        val obj = jsonObjectOrNull(trimmed) ?: return JsTypeConverter.UiTreeParseResult(null)
        val root = toNode(obj, 0, budget)
        return JsTypeConverter.UiTreeParseResult(root, budget, if (root == null) 0 else 1)
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
    private fun toNode(o: PluginJsonObject, depth: Int, budget: JsTypeConverter.UiParseBudget): PluginNode? {
        if (budget.nodeCount >= JsTypeConverter.MAX_NODES) return null
        if (depth > JsTypeConverter.MAX_DEPTH) return UnsupportedNode(type = "嵌套过深", id = o.optStringOrNull("id"))

        val type = o.optString("type")
        // 没有 type 的对象不是一个节点：`{}` 这类内容当作「什么都没说」，
        // 而不是造一个「暂不支持 ?」的占位去占版面
        if (type.isBlank()) return null
        val id = o.optStringOrNull("id")

        // 机器字段不能截断（截断会产生指向不存在目标的 id），超限只能丢弃该节点。
        // 这与展示文本的处理相反：展示文本截断只是少显示几个字，语义不坏。
        if (id != null && id.utf8Size() > JsTypeConverter.MAX_MACHINE_FIELD_BYTES) {
            AppLogger.warn("插件节点 id 超过 ${JsTypeConverter.MAX_MACHINE_FIELD_BYTES} 字节，已丢弃该节点")
            return null
        }
        val locked = o.optBoolean("locked", false)

        val node = when (type) {
            // ---- 容器 ----
            "column" -> ColumnNode(
                children = o.optNodes("children", depth, budget),
                spacing = JsNodePayloadParser.run { o.optString("spacing").toSpacing() },
                card = o.optBoolean("card", true),
                title = o.optString("title"),
            )
            "row" -> RowNode(
                children = o.optNodes("children", depth, budget),
                spacing = JsNodePayloadParser.run { o.optString("spacing").toSpacing() },
                wrap = o.optBoolean("wrap", true),
            )
            "collapsible" -> CollapsibleNode(
                title = o.optString("title"),
                children = o.optNodes("children", depth, budget),
                collapsed = o.optBoolean("collapsed", true),
                summary = o.optString("summary"),
            )
            "spacer" -> SpacerNode(size = JsNodePayloadParser.run { o.optString("size").toSpacing() })
            "divider" -> DividerNode()

            // ---- 内容 ----
            "text" -> TextNode(
                title = o.optString("title"),
                body = o.optBoundString("body").truncated(),
                tone = o.optString("tone").toTone(),
                emphasis = JsNodePayloadParser.run { o.optString("emphasis").toEmphasis() },
                card = o.optBoolean("card", true),
            )
            "statusCard" -> StatusCardNode(
                label = o.optString("label"),
                value = o.optBoundString("value").truncated(),
                tone = o.optString("tone").toTone(),
                detail = truncateDisplay(o.optString("detail"), JsTypeConverter.MAX_DISPLAY_TEXT_BYTES),
            )
            "chart" -> {
                // 认不出 kind 时保留节点并降级，旧实现是整块丢掉——
                // 用户至少该知道「这里本来有个图」
                val spec = o.optJSONObject("chart")?.let { JsNodePayloadParser.toChartSpec(it) }
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
                code = truncateDisplay(o.optString("code"), JsTypeConverter.MAX_CODE_TEXT_BYTES),
            )

            // ---- 集合 ----
            "list" -> ListNode(
                items = o.optBoundItems(depth, budget),
                dense = o.optBoolean("dense", false),
                separator = o.optBoolean("separator", true),
                maxHeightDp = o.optInt("maxHeightDp", 0),
                emptyHint = o.optString("emptyHint").ifBlank { "暂无内容" },
                itemActionId = o.optMachineString("itemActionId")?.takeIf { it.isNotBlank() },
                itemPayloads = o.optStringList("itemPayloads"),
            )
            "table" -> TableNode(
                columns = JsNodePayloadParser.run { o.optJSONArray("columns").toTableColumns() },
                rows = JsNodePayloadParser.run { o.optJSONArray("rows").toTableRows() },
            )
            "keyValue" -> KeyValueNode(entries = JsNodePayloadParser.run { o.optJSONArray("entries").toKeyValues() })

            // ---- 交互 ----
            "button" -> o.optMachineString("actionId")?.let { actionId ->
                val payload = o.optStringOrNull("payload")?.let { p ->
                    if (p.utf8Size() > JsTypeConverter.MAX_INTERACTION_PAYLOAD_BYTES) {
                        AppLogger.warn("按钮 payload 超过 ${JsTypeConverter.MAX_INTERACTION_PAYLOAD_BYTES} 字节，已丢弃该按钮")
                        return@let null
                    }
                    p
                }
                ButtonNode(
                    label = o.optString("label"),
                    actionId = actionId,
                    primary = o.optBoolean("primary", false),
                    enabled = o.optBoolean("enabled", true),
                    payload = payload,
                )
            } ?: UnsupportedNode(type = "button:actionId 非法", id = id)
            "input" -> o.optMachineString("actionId")?.takeIf { it.isNotBlank() }?.let { actionId ->
                InputNode(
                    actionId = actionId,
                    placeholder = o.optString("placeholder").ifBlank { "输入内容" },
                    submitLabel = o.optString("submitLabel").ifBlank { "提交" },
                    multiline = o.optBoolean("multiline", true),
                    enabled = o.optBoundBoolean("enabled", true),
                    submitTo = o.optString("submitTo").takeIf { it.isNotBlank() }?.let { BindingRef.parseTarget(it) },
                )
            }
            "select" -> o.optMachineString("actionId")?.takeIf { it.isNotBlank() }?.let { actionId ->
                val options = JsNodePayloadParser.run { o.optJSONArray("options").toSelectOptions() }
                // 一个选项都没有的下拉框点了没反应，不如不渲染
                if (options.isEmpty()) null else SelectNode(
                    actionId = actionId,
                    options = options,
                    label = o.optString("label"),
                    multiple = o.optBoolean("multiple", false),
                    submitLabel = o.optString("submitLabel"),
                    enabled = o.optBoolean("enabled", true),
                    selectedValues = o.optStringList("selectedValues"),
                )
            }
            "toggle" -> o.optMachineString("actionId")?.takeIf { it.isNotBlank() }?.let { actionId ->
                ToggleNode(
                    actionId = actionId,
                    label = o.optString("label"),
                    checked = o.optBoolean("checked", false),
                    enabled = o.optBoolean("enabled", true),
                )
            }
            "picker" -> o.optMachineString("actionId")?.takeIf { it.isNotBlank() }?.let { actionId ->
                PickerNode(
                    actionId = actionId,
                    title = o.optString("title"),
                    candidates = JsChatPickerParser.run { o.parseCandidates(budget) },
                    mode = JsChatPickerParser.run { o.optString("mode").toPickerMode() },
                    multiple = o.optBoolean("multiple", false),
                    selectedIds = o.optStringList("selectedIds"),
                    submitLabel = o.optString("submitLabel"),
                    emptyHint = o.optString("emptyHint").ifBlank { "暂无可选项" },
                )
            }

            // ---- 对话 ----
            "chat" -> ChatNode(
                messages = JsChatPickerParser.run { o.parseMessages(budget) },
                selfName = truncateDisplay(o.optString("selfName"), JsTypeConverter.MAX_DISPLAY_TEXT_BYTES),
                peerName = truncateDisplay(o.optString("peerName"), JsTypeConverter.MAX_DISPLAY_TEXT_BYTES),
                maxHeightDp = o.optInt("maxHeightDp", 0),
                showTime = o.optBoolean("showTime", true),
                emptyHint = o.optString("emptyHint").ifBlank { "暂无对话" },
            )

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
        is ChatNode -> copy(id = id, locked = locked)
        is PickerNode -> copy(id = id, locked = locked)
        is AudioNode -> copy(id = id, locked = locked)
        is VideoNode -> copy(id = id, locked = locked)
    }

    /** 递归解析 children；单层子节点数超限时截断，避免一个 row 里塞几千个节点 */
    private fun PluginJsonObject.optNodes(
        key: String,
        depth: Int,
        budget: JsTypeConverter.UiParseBudget,
    ): List<PluginNode> {
        val arr = optJSONArray(key) ?: return emptyList()
        val limit = minOf(arr.length(), JsTypeConverter.MAX_CHILDREN_PER_CONTAINER)
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

    /** 展示文本按字节截断；绑定引用是机器字段，原样保留 */
    private fun Bound<String>.truncated(): Bound<String> = when (this) {
        is Bound.Static -> Bound.Static(truncateDisplay(value, JsTypeConverter.MAX_DISPLAY_TEXT_BYTES))
        is Bound.Ref -> this
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
    private fun PluginJsonObject.optBoundItems(depth: Int, budget: JsTypeConverter.UiParseBudget): Bound<List<List<PluginNode>>> {
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
        val limit = minOf(arr.length(), JsTypeConverter.MAX_LIST_ITEMS)
        val items = (0 until limit).mapNotNull { i ->
            val item = arr.optJSONArray(i) ?: return@mapNotNull null
            val nodes = (0 until minOf(item.length(), JsTypeConverter.MAX_CHILDREN_PER_CONTAINER)).mapNotNull { j ->
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

    /**
     * 解析一个「字符串数组」字段（`selectedValues` / `selectedIds` / `itemPayloads`）。
     *
     * 缺失或不是数组时返回空列表而不是报错：这几个字段都是**可选增强**，
     * 插件写错类型只会退回「没有预选」，不该连带整个节点消失。
     */
    private fun PluginJsonObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until minOf(arr.length(), JsTypeConverter.MAX_STRING_LIST_SIZE))
            .map { i -> arr.optStringOrNull(i).orEmpty() }
    }
}

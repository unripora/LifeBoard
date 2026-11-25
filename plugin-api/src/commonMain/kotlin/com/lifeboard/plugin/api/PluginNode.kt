package com.lifeboard.plugin.api

/**
 * 详情页 UI 的可组合原语树。
 *
 * ## 为什么从「固定区块列表」改成「树」
 *
 * 旧模型是 9 个封闭区块（`TextBlock` / `StatusCardBlock` / `ButtonRowBlock` /
 * `ChartBlock` / `CustomImageBlock` / `MessageListBlock` / `InputBoxBlock` /
 * `ApprovalBlock` / `ConversationBlock`），插件只能把它们平铺——不能两列并排、
 * 不能在卡片里放一个列表加一个按钮、不能把次要信息折叠起来。
 *
 * 后果是每来一种新的**组合**需求，就只能由宿主新增一个区块类型。旧模型里
 * 已经有三个区块本身就是「组合」的产物（`buttonRow` = 一排按钮、`approval` =
 * 标题 + 一排按钮、`conversation` = 消息列表 + 输入框），这正是组合能力缺失的症状。
 *
 * 树把「组合」收进宿主：节点只有有限的原语，组合方式由插件用 [NodeContainer.children]
 * 表达。新增插件时只有**全新能力类别**（如音视频播放）才需要动宿主。
 *
 * ## 插件仍然不控制视觉
 *
 * 节点里没有一个像素、颜色、字号字段。间距是档位（[Spacing]）、色调是语义
 * （[Tone]）、字号是层级（[Emphasis]），具体映射全部由宿主按主题决定。
 * 这样所有插件的详情页看起来仍是同一个应用。
 *
 * ## 分类为什么按「渲染时的布局约束」而不是按功能
 *
 * [NodeContainer] / [NodeContent] / [NodeInteractive] 回答的是「宿主渲染这一层
 * 需要什么约束」：容器要有 children 并决定排列；内容是叶子；交互要被 busy 禁用。
 * 若按功能分组（「媒体节点」「会话节点」），同一个 `when` 的分支会跨越多个接口，
 * Kotlin 的穷尽性检查就用不上了——而那个检查正是新增节点时防漏的最后一道防线。
 */

/**
 * 树上的一个节点。
 *
 * @property id 插件声明的稳定标识。只用于宿主侧定位（用户自定义排序、诊断），
 *   不参与视觉。缺省时宿主按「类型 + 位置」兜底。
 * @property locked 插件显式要求「本节点不参与拖动排序」。用于紧贴图表的说明文字
 *   这类与上下文强绑定的卡片：被单独拖走会让说明与图表分离。
 */
sealed interface PluginNode {
    val id: String?
    val locked: Boolean
}

/**
 * 容器：接受子节点并决定排列方式。
 *
 * 只有根节点的直接子节点参与拖动排序；容器内部的 children 一律不参与
 * （嵌套排序需要为每个容器维护独立顺序偏好，设置项会指数膨胀，
 * 且插件每次调整结构都会让保存的顺序错位）。
 */
sealed interface NodeContainer : PluginNode {
    val children: List<PluginNode>
}

/** 叶子内容：自己就是一个可渲染单元，不再有子节点 */
sealed interface NodeContent : PluginNode

/**
 * 交互：会产生 actionId + value 回给插件。
 *
 * 与 [NodeContent] 分成两个接口，是因为宿主对它们的处理不同：
 * 交互节点在 [PluginAction] 执行期间（busy）必须禁用，内容节点不受影响。
 */
sealed interface NodeInteractive : PluginNode {
    /** 回传给 [SourcePlugin.onAction] 的标识；为 null 时宿主渲染成禁用态 */
    val actionId: String?
}

// ---------------------------------------------------------------------------
// 容器节点
// ---------------------------------------------------------------------------

/**
 * 纵向排列。
 *
 * @property spacing 间距档位，不是像素值
 * @property card 是否套一层卡片外壳。**这是整棵树里唯一决定「套不套卡片」的字段**，
 *   嵌套容器默认 `false`，从机制上避免卡片套娃（旧实现对每个区块各写一次卡片，
 *   嵌套后两层边框会叠在一起，看起来像表格）
 * @property title 卡片标题；为空则不显示标题行
 */
data class ColumnNode(
    override val children: List<PluginNode>,
    val spacing: Spacing = Spacing.NORMAL,
    val card: Boolean = true,
    val title: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContainer

/**
 * 横向排列。
 *
 * @property wrap 为 `true` 时窄屏自动折行（一排按钮在手机上会挤爆，折行比压缩字号好）
 */
data class RowNode(
    override val children: List<PluginNode>,
    val spacing: Spacing = Spacing.NORMAL,
    val wrap: Boolean = true,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContainer

/** 纵向留白。高度档位化，插件不能写具体 dp */
data class SpacerNode(
    val size: Spacing = Spacing.NORMAL,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/** 分隔线。用于卡片内部划分组，替代「卡片里再套一张卡片」 */
data class DividerNode(
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 可折叠区块。
 *
 * @property collapsed 初始是否折叠。展开状态由宿主持有，插件每次刷新可重置
 * @property summary 折叠时标题右侧的一行摘要（如「3 项」），让用户知道里面有什么
 */
data class CollapsibleNode(
    val title: String,
    override val children: List<PluginNode>,
    val collapsed: Boolean = true,
    val summary: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContainer

// ---------------------------------------------------------------------------
// 内容节点
// ---------------------------------------------------------------------------

/**
 * 一段文字。
 *
 * @property body 支持绑定到宿主数据源（如会话状态文案），见 [Bound]
 * @property emphasis 文字层级；宿主持有三级到实际字号/字重的映射
 */
data class TextNode(
    val title: String = "",
    val body: Bound<String> = Bound.Static(""),
    val tone: Tone = Tone.NEUTRAL,
    val emphasis: Emphasis = Emphasis.BODY,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 一张状态卡：左边标签，右边值。
 *
 * 用于「同步状态」「上次成功时间」这类一眼要看到结果的信息，不适合放长文本
 * （长文本用 [TextNode]，否则卡片会被撑成一段话）。
 */
data class StatusCardNode(
    val label: String,
    val value: Bound<String>,
    val tone: Tone = Tone.NEUTRAL,
    val detail: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 图表。内置模板优先于 [ImageNode] 自绘：一次实现所有插件受益，且自动跟随深浅色。
 *
 * @property title 图表标题；为空则不显示标题行
 */
data class ChartNode(
    val chart: ChartSpec,
    val title: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 图片，以 `data:image/...;base64,...` 形式给出。
 *
 * @property aspectRatio 宽高比，宿主按它占位，避免图片加载完成后页面跳动
 */
data class ImageNode(
    val dataUri: String,
    val aspectRatio: Float = 1.6f,
    val contentDescription: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 线性进度。
 *
 * 与 [ProgressRingSpec] 并存而不是合并：环形用于「一张卡的中心数据」，
 * 线性用于「这一项做到多少了」这种窄条场景，两者占的版面形状不同，
 * 不是同一件事的两种画法。
 *
 * @property fraction 0..1，越界由宿主收敛
 */
data class ProgressNode(
    val fraction: Float,
    val label: String = "",
    val valueText: String = "",
    val tone: Tone = Tone.NEUTRAL,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/** 代码块。等宽字体、横向滚动，背景色由宿主决定 */
data class CodeNode(
    val code: String,
    val language: String = "",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 宿主不认识的节点。
 *
 * 旧实现对未知 `type` 是**静默丢弃**，用户看到的是「插件说的东西少了一块」，
 * 没有任何线索。改为渲染一个弱化占位（「此端暂不支持：xxx」）：用户能明白
 * 「这里本来有东西，是 App 太旧」，而不是以为插件坏了。
 *
 * @property type 插件声明的原始 type，仅用于诊断文案
 */
data class UnsupportedNode(
    val type: String,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

// ---------------------------------------------------------------------------
// 集合类节点
// ---------------------------------------------------------------------------

/**
 * 列表：一组条目，每条由插件自己给内容。
 *
 * ## 为什么 items 是「子节点」而不是预定义字段
 *
 * 预定义字段（`{title, subtitle, trailing}`）在第二个插件就不够用：健康插件要
 * 「日期 + 数值 + 单位」，对话插件要「角色 + 正文 + 时间」，第三种需求一来就得
 * 加字段，而加字段要同时动契约、宿主与 TS 声明三处。
 *
 * 子节点让列表项复用所有内容节点，宿主只负责「项间距 + 分隔线 + 滚动」。
 *
 * ## 为什么不是 `List<PluginNode>` 里放 [RowNode]
 *
 * 两层结构（`List<List<PluginNode>>`）让宿主能插入分隔线与稳定的「第 i 项」key。
 * 若压成一层，列表项的 key 就变成 RowNode 的 id，插件不给 id 时滚动会错位。
 *
 * @property items 静态条目，或绑定到宿主数据源（如会话消息）。**二选一**
 * @property maxHeightDp 超过后内部滚动；0 表示不限制
 * @property emptyHint 无条目时的提示文案
 */
data class ListNode(
    val items: Bound<List<List<PluginNode>>> = Bound.Static(emptyList()),
    val dense: Boolean = false,
    val separator: Boolean = true,
    val maxHeightDp: Int = 0,
    val emptyHint: String = "暂无内容",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/** 表格。单元格是纯文本——表格里放富内容会立刻超出「表格」的表达力 */
data class TableNode(
    val columns: List<TableColumn>,
    val rows: List<List<String>>,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/** @property weight 列宽权重，`<= 0` 表示按内容自适应 */
data class TableColumn(
    val title: String,
    val weight: Float = 0f,
    val align: Align = Align.START,
)

/** 一组键值对（「包名 / 版本 / 签名」这类）。比一堆状态卡更紧凑 */
data class KeyValueNode(
    val entries: List<KeyValue>,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

data class KeyValue(
    val key: String,
    val value: String,
    val tone: Tone = Tone.NEUTRAL,
)

// ---------------------------------------------------------------------------
// 交互节点
// ---------------------------------------------------------------------------

/**
 * 按钮。
 *
 * [actionId] 为空时宿主渲染成**禁用态**而不是丢弃：丢弃会让插件「少了一个按钮」
 * 却不自知，禁用至少看得见。
 *
 * @property payload 点击时回传给 [SourcePlugin.onAction] 的固定 value。
 *   多个按钮共用一个 actionId 时用它区分
 */
data class ButtonNode(
    val label: String,
    override val actionId: String? = null,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val payload: String? = null,
    override val id: String? = null,
    override val locked: Boolean = true,
) : NodeInteractive

/**
 * 文本输入。
 *
 * 提交时宿主把用户输入回传给 [SourcePlugin.onAction]，或按 [submitTo] 直接投给
 * 绑定数据源（会话就是这样收输入的）。宿主不替插件清空语义——输入框内容由宿主
 * 清空，但插件应在下一次 `detailUi` 里体现这条输入。
 *
 * @property submitTo 非空时提交**不进** `onAction`，而是投给该绑定源。
 *   值为 `false` 表示源不支持写入，此时退回 `onAction`
 */
data class InputNode(
    override val actionId: String?,
    val placeholder: String = "输入内容",
    val submitLabel: String = "提交",
    val multiline: Boolean = true,
    val enabled: Bound<Boolean> = Bound.Static(true),
    val submitTo: BindingRef? = null,
    override val id: String? = null,
    override val locked: Boolean = true,
) : NodeInteractive

/**
 * 下拉选择。
 *
 * ## value 回传格式
 *
 * - 单选：`onAction(actionId, option.value)`
 * - 多选：`onAction(actionId, JSON 数组文本)`，如 `["a","c"]`。
 *   不用逗号拼接，因为选项 value 本身可能含逗号，拼接后无法无歧义还原
 *
 * @property submitLabel 非空时出现确认按钮（多选必需；单选留空=选中即提交）
 */
data class SelectNode(
    override val actionId: String?,
    val options: List<SelectOption>,
    val label: String = "",
    val multiple: Boolean = false,
    val submitLabel: String = "",
    val enabled: Boolean = true,
    override val id: String? = null,
    override val locked: Boolean = true,
) : NodeInteractive

/** @property value 选项的机器可读值，**必填**；为空则该选项被丢弃 */
data class SelectOption(
    val value: String,
    val label: String = "",
)

/**
 * 开关。value 回传 `"true"` / `"false"`。
 *
 * 用字面量而不是 `null`：开关的「关」是一个有意义的值，用 null 表达会与
 * 「按钮点击」撞在一起。
 */
data class ToggleNode(
    override val actionId: String?,
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    override val id: String? = null,
    override val locked: Boolean = true,
) : NodeInteractive

// ---------------------------------------------------------------------------
// 媒体节点（本期只定义类型，宿主降级渲染）
// ---------------------------------------------------------------------------

/**
 * 音频。
 *
 * ## 为什么现在就定义
 *
 * 节点类型是联合类型的一部分。等宿主实现播放器时再加，意味着插件要等宿主发版
 * 才能写；提前定义好，插件可以先把 UI 发出去——用户在旧宿主上看到「暂不支持」，
 * 在新宿主上就有播放器。
 *
 * ## 地址限制
 *
 * `source` 只允许 `https://` 或 `data:`。校验在**解析期**做，不留到渲染期：
 * Android 9+ 默认拦明文 HTTP，与其将来带着这个漏洞上线，不如现在就拒绝。
 */
data class AudioNode(
    val source: String,
    val title: String = "",
    val subtitle: String = "",
    val durationSeconds: Int = 0,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 视频。
 *
 * @property posterDataUri 封面图，`data:` 形式；视频占版面大，必须先占位防跳动
 * @property autoplay 默认 `false`：自动播放是最容易被用户讨厌的行为
 */
data class VideoNode(
    val source: String,
    val posterDataUri: String = "",
    val aspectRatio: Float = 1.778f,
    val title: String = "",
    val autoplay: Boolean = false,
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

// ---------------------------------------------------------------------------
// 枚举
// ---------------------------------------------------------------------------

/** 间距档位。宿主按设计系统映射到具体 dp，插件不能直接写像素 */
enum class Spacing {
    NONE,
    COMPACT,
    NORMAL,
    LOOSE,
}

/** 文字层级。宿主映射到 typography 的标题/正文/说明三档 */
enum class Emphasis {
    HEADING,
    BODY,
    CAPTION,
}

/** 水平对齐 */
enum class Align {
    START,
    CENTER,
    END,
}

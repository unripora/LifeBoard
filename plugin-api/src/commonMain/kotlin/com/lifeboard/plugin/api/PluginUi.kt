package com.lifeboard.plugin.api

/**
 * 插件详情页的声明式 UI 描述。
 *
 * ## 为什么是数据而不是 UI 代码
 *
 * 宿主与插件的契约模块是纯 JVM 的，不能依赖 Compose。让插件直接写 Composable
 * 会把插件和宿主的 Compose 版本绑死，升级宿主就等于要求插件全部重新编译。
 * 所以插件只描述「要展示什么」，渲染由宿主统一负责。
 *
 * ## 谁控制表现
 *
 * 颜色、字体、间距全部由宿主决定，插件只说语义（如 [Tone.ERROR]）。
 * 这样所有插件的详情页看起来是同一个应用，而不是一堆嵌入的小程序。
 *
 * ## 版本约束
 *
 * 本文件属于契约的一部分。**只有破坏性变更才递增 [PluginApi.VERSION]**：
 * 新增 [UiBlock] 分支对插件是安全的（多一个可选项），但对**旧宿主**不安全——
 * 它遇到不认识的类型只能不渲染，表现为「插件说的东西没显示」。
 * 所以新增分支不需要升版本号，但插件应当在自己的最低宿主版本说明里写清依赖关系。
 */
sealed interface UiBlock {
    /**
     * 可选稳定标识：用户自定义区块顺序时用来引用具体区块。
     *
     * 不要求插件提供——缺省时宿主按「类型 + 当前位置」兜底。
     */
    val id: String?
}

/**
 * 一段带标题的说明文字。
 *
 * @property body 为空时只渲染标题，适合当小节的引导句
 */
data class TextBlock(
    val title: String,
    val body: String = "",
    val tone: Tone = Tone.NEUTRAL,
    /** 可选稳定标识，用于用户自定义区块顺序；缺省由宿主按「类型 + 位置」兜底 */
    override val id: String? = null,
) : UiBlock

/**
 * 一张状态卡：左边是标签，右边是值。
 *
 * 用于「同步状态」「上次成功时间」这类**一眼要看到结果**的信息，
 * 不适合放长文本——长文本用 [TextBlock]，否则卡片会被撑成一段话。
 */
data class StatusCardBlock(
    val label: String,
    val value: String,
    val tone: Tone = Tone.NEUTRAL,
    val detail: String? = null,
    override val id: String? = null,
) : UiBlock

/**
 * 一行操作按钮。
 *
 * [actionId] 由插件自定义，点击后宿主把它原样回传给
 * [SourcePlugin.onAction]，插件据此决定做什么。
 *
 * 宿主在 [SourcePlugin.onAction] 返回前会禁用按钮，插件不必自己防连点。
 */
data class ButtonRowBlock(
    val buttons: List<ButtonSpec>,
    override val id: String? = null,
) : UiBlock

/**
 * 单个按钮。
 *
 * @property actionId 回传给 [SourcePlugin.onAction] 的标识，插件内唯一
 * @property primary 主操作按钮，一行里只应有一个
 */
data class ButtonSpec(
    val label: String,
    val actionId: String,
    val primary: Boolean = false,
    val enabled: Boolean = true,
)

/** 图表区块 */
data class ChartBlock(
    val title: String,
    val chart: ChartSpec,
    override val id: String? = null,
) : UiBlock

/**
 * 宿主内置图表。
 *
 * 优先用这些模板而不是 [CustomImageBlock]：模板由宿主一次实现、所有插件受益，
 * 且自动跟随深浅色。自定义图片是每个插件各自造轮子，风格也难统一。
 */
sealed interface ChartSpec

/**
 * 折线图。
 *
 * [points] 是「横轴标签 → 值」。宿主会限制点数上限（超出按等距抽样），
 * 插件不必自己截断，但也不该传几千个点——那不是给人看的。
 */
data class LineChartSpec(
    val points: List<ChartPoint>,
    val unit: String = "",
) : ChartSpec

/**
 * 柱状图。
 *
 * @property highlightIndex 高亮第几根（如「今天」），-1 表示都不高亮
 */
data class BarChartSpec(
    val bars: List<ChartPoint>,
    val unit: String = "",
    val highlightIndex: Int = -1,
) : ChartSpec

/**
 * 环形进度。
 *
 * @property fraction 0..1，超出范围由宿主收敛，避免画出越界的弧
 */
data class ProgressRingSpec(
    val fraction: Float,
    val centerText: String,
    val subtitle: String? = null,
) : ChartSpec

/** 图表上的一个数据点 */
data class ChartPoint(
    val label: String,
    val value: Double,
)

/**
 * 插件自己画好的图，以 `data:image/...;base64,...` 形式给出。
 *
 * 出口留给内置模板表达不了的场景。插件是 Android 代码，可以用
 * `Bitmap`/`Canvas` 自绘再转 base64，所以这条路可行。
 *
 * @property aspectRatio 宽高比，宿主按它占位，避免图片加载完成后页面跳动
 */
data class CustomImageBlock(
    val dataUri: String,
    val aspectRatio: Float = 1.6f,
    override val id: String? = null,
) : UiBlock

/**
 * 消息列表：一段对话记录。
 *
 * 由宿主负责气泡样式、左右对齐与滚动；插件只给出「谁说的、说了什么」。
 * 每次 [SourcePlugin.detailUi] 都返回**全量**消息而不是增量，
 * 宿主整体替换即可——增量协议要处理丢包与乱序，对聊天记录没有收益。
 *
 * @property messages 按时间正序，最后一条在底部
 */
data class MessageListBlock(
    val messages: List<ChatMessage>,
    override val id: String? = null,
) : UiBlock

/**
 * 一条消息。
 *
 * @property role 决定气泡样式；[ChatRole.SYSTEM] 用于「已连接」「对方正在处理」这类提示
 * @property pending 消息尚未完成（流式输出中或等待回复），宿主会给出进行中的视觉提示
 * @property timeLabel 展示用时间文案，由插件按自己的协议格式化；为空则不显示
 */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timeLabel: String? = null,
    val pending: Boolean = false,
)

/** 消息角色 */
enum class ChatRole {
    /** 用户自己说的 */
    USER,

    /** 对方（Agent / 服务端）说的 */
    ASSISTANT,

    /** 系统提示，不属于任何一方 */
    SYSTEM,
}

/**
 * 输入框：用户输入文本后提交。
 *
 * 提交时宿主调用 [SourcePlugin.onAction]，`actionId` 是本区块的标识，
 * `value` 是用户输入的文本。宿主**不替插件清空**输入语义——
 * 输入框内容由宿主清空，但插件应在下一次 `detailUi` 里把这条消息
 * 作为 [ChatRole.USER] 放进 [MessageListBlock]。
 *
 * @property actionId 回传给 [SourcePlugin.onAction] 的标识
 * @property submitLabel 提交按钮文案
 * @property multiline 多行输入；对话场景通常为 `true`
 * @property enabled 为 `false` 时只展示不可输入（如连接断开期间）
 */
data class InputBoxBlock(
    val actionId: String,
    val placeholder: String = "输入消息",
    val submitLabel: String = "发送",
    val multiline: Boolean = true,
    val enabled: Boolean = true,
    override val id: String? = null,
) : UiBlock

/**
 * 审批区块：请求用户做一个选择（批准/拒绝、选一个选项）。
 *
 * [waiting] 为 `true` 时只展示「等待处理」的占位，不显示选项——
 * 用于「已提交、等对方决定」的阶段。插件在对应用户操作后应重新发出
 * 一个 `waiting=false` 或去掉选项的区块来表达结果。
 *
 * @property options 选项列表；点击后宿主把该项的 `actionId` 回传给
 *   [SourcePlugin.onAction]，`value` 为空
 */
data class ApprovalBlock(
    val title: String,
    val detail: String = "",
    val options: List<ButtonSpec>,
    val waiting: Boolean = false,
    override val id: String? = null,
) : UiBlock

/**
 * 会话区块：一块由宿主托管的实时对话区域。
 *
 * 与 [MessageListBlock] 的区别是「活」的：消息由插件持续推入，用户输入直接交给插件，
 * 不需要每次操作都重画整页。[MessageListBlock] 仍是静态快照，适合展示历史记录。
 *
 * ## 插件控制位置，宿主控制表现
 *
 * 插件把本区块放在 `detailUi()` 返回列表的任意位置，宿主负责气泡样式、滚动与输入框行为；
 * 具体绑定哪个会话由 [sessionId] 决定，同一插件内唯一。
 *
 * ## 生命周期
 *
 * 宿主在区块可见时开始会话、不可见时按策略保留或结束。插件不需要自己判断页面是否在前台。
 *
 * @property sessionId 插件内唯一的会话标识
 * @property title 区块标题；为空时使用「会话」
 * @property placeholder 输入框占位文案
 * @property submitLabel 提交按钮文案
 * @property emptyHint 尚无消息时的提示
 */
data class ConversationBlock(
    val sessionId: String,
    val title: String = "",
    val placeholder: String = "输入消息",
    val submitLabel: String = "发送",
    val emptyHint: String = "还没有消息",
    override val id: String? = null,
) : UiBlock

/** 语义色调，具体颜色由宿主按主题决定 */
enum class Tone {
    /** 普通信息 */
    NEUTRAL,

    /** 一切正常 */
    OK,

    /** 需要用户注意但不影响使用 */
    WARN,

    /** 出错，或用不了 */
    ERROR,
}

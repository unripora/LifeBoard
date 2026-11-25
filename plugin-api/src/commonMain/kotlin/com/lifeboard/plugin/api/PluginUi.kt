package com.lifeboard.plugin.api

/**
 * 详情页 UI 里被新节点树复用的**值类型**。
 *
 * ## 这个文件只剩值类型
 *
 * 早期这里是 9 个封闭区块（`UiBlock` 及其实现）。它们已被可组合原语树
 * [PluginNode] 取代——区块只能平铺，无法表达「两列并排」「卡片里放列表 + 按钮」
 * 「可折叠的次要信息」，而每加一种组合需求就得由宿主新增一个区块类型。
 *
 * 保留下来的这些不是「区块」，而是节点与插件共同使用的**数据形状**：
 * 色调、图表规格、聊天消息。它们与「怎么排布」无关，所以不必随树结构变化。
 *
 * ## 谁控制表现
 *
 * 颜色、字体、间距全部由宿主决定，插件只说语义（如 [Tone.ERROR]）。
 * 这样所有插件的详情页看起来是同一个应用，而不是一堆嵌入的小程序。
 */

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

/**
 * 宿主内置图表。
 *
 * 优先用这些模板而不是让插件自绘图片：模板由宿主一次实现、所有插件受益，
 * 且自动跟随深浅色。自绘是每个插件各自造轮子，风格也难统一。
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
 * 一条消息。
 *
 * 只用于 [ListNode] 绑定到会话消息源时由宿主填充，以及插件展示静态历史记录。
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

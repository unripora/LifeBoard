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
sealed interface UiBlock

/**
 * 一段带标题的说明文字。
 *
 * @property body 为空时只渲染标题，适合当小节的引导句
 */
data class TextBlock(
    val title: String,
    val body: String = "",
    val tone: Tone = Tone.NEUTRAL,
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

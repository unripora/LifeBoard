package com.lifeboard.shared.dashboard

/**
 * 内置业务序列。
 *
 * 这些序列不来自任何采集源，而是由本应用自己的数据算出来的
 * （得分、完成率、连击……）。与普通指标的差别不只是「数据从哪来」：
 * 有的序列必须先指定一条任务才有意义。
 *
 * 加新项时必须同时想清楚「分母是什么、缺失日怎么表达」，
 * 否则很容易算出一个看起来合理但含义错误的数字。
 *
 * @param metricKey 写进 [ChartSeriesConfig.metricKey] 的固定键。
 *   刻意带 `BUILTIN_` 前缀：普通指标键不会长成这样，两者在同一字段里不会撞车，
 *   配置模型因此不需要为「内置序列」单开一个字段
 */
enum class BuiltinSeries(
    val metricKey: String,
    val label: String,
    val unit: String,
    val needsTask: Boolean = false,
) {
    /** 每日得分：当天所有记录得分之和 */
    DAILY_SCORE("BUILTIN_DAILY_SCORE", "每日得分", "分"),

    /** 每日完成率：已完成权重 / 参与计分权重 */
    COMPLETION_RATE("BUILTIN_COMPLETION_RATE", "每日完成率", "%"),

    /** 每日完成待办数 */
    DONE_TASK_COUNT("BUILTIN_DONE_TASK_COUNT", "完成待办数", "条"),

    /**
     * 连击走势：按日重建的连续达成天数。
     *
     * 必须指定任务：连击是「某条任务连续达成多少天」，没有任务就没有这条曲线。
     */
    STREAK("BUILTIN_STREAK", "连击天数走势", "天", needsTask = true),

    /** 单条规则完成率。同样必须指定任务 */
    RULE_COMPLETION("BUILTIN_RULE_COMPLETION", "单条规则完成率", "%", needsTask = true),

    /** 手动打卡次数 */
    MANUAL_MARK_COUNT("BUILTIN_MANUAL_MARK_COUNT", "手动打卡次数", "次"),
    ;

    companion object {
        /**
         * 按配置里的 metricKey 反查内置序列，不是内置序列返回 null。
         *
         * 这是「普通采集序列 / 内置序列」的唯一判据：配置、查询、界面都调它，
         * 避免各处各写一套判断而漏掉某一处。
         */
        fun fromMetricKey(metricKey: String?): BuiltinSeries? =
            entries.firstOrNull { it.metricKey == metricKey }
    }
}

/** Y 轴归属。Vico 最多支持两根 Y 轴，与这里的取值一一对应 */
enum class ChartAxis {
    LEFT,
    RIGHT,
}

/**
 * 图表形态。
 *
 * 存进配置的是枚举名，改名字等于改数据格式。
 */
enum class ChartType {
    /** 折线：多指标时间序列 */
    LINE,

    /** 柱状：多指标分组柱 */
    COLUMN,

    /** 面积：折线的填充变体 */
    AREA,

    /** 散点：折线仅显示点、不连线 */
    SCATTER,

    /** 饼图：单分类源的占比，使用 Top N + 其他 */
    PIE,

    /** 环形进度：单指标当前值 / 目标值 */
    RING,

    /**
     * 热力图：GitHub 打卡日历式的格子图。
     *
     * 只用第一条序列（一个指标），横轴周、纵轴星期，格子颜色深浅代表当天的值。
     * 多指标叠在同一个格子里没有可读的比法，所以它和 [RING] 一样是单序列形态。
     */
    HEATMAP,
}

/**
 * 卡片密度。
 *
 * 同一份数据在两种密度下画法不同：
 * - [COMFORTABLE] 是原来的大卡，图占主体，适合单向滚动精读
 * - [COMPACT] 参考仪表盘式的小方块：大数字在上、迷你趋势在下，
 *   宽屏一屏能排三四列，适合「扫一眼全局」
 *
 * 存放在每张卡自己的配置里而不是页签级设置：同一个页签里
 * 「得分」适合方块、「趋势」适合大卡，绑死在页签上反而不好用。
 */
enum class ChartDensity {
    COMFORTABLE,
    COMPACT,
}

/** 允许的时间窗天数。与 UI 上的三个选项保持一致 */
val CHART_TIME_WINDOWS_DAYS = listOf(7, 30, 90)

/**
 * 一条指标序列。
 *
 * @param label 图例展示名；为空时回退到 metricKey
 * @param unit 单位（步、小时、次……）。同单位的序列才能共用一根 Y 轴
 * @param axis 分配到的 Y 轴。不同单位最多用左右两轴
 * @param colorArgb 用户指定颜色；为空时按主题自动分配
 * @param taskId 单条任务类内置序列要看的任务；普通采集序列为 null。
 *   有值即表示这条序列走「内置业务序列」而不是查 `metric` 表，
 *   判据统一见 [BuiltinSeries.fromMetricKey]
 */
data class ChartSeriesConfig(
    val sourceKey: String,
    val metricKey: String,
    val label: String,
    val unit: String,
    val axis: ChartAxis = ChartAxis.LEFT,
    val colorArgb: Int? = null,
    val taskId: Long? = null,
)

/**
 * 一张自定义图表的完整配置。
 *
 * @param title 用户可编辑的标题。为空时界面回退到图表类型名（「折线图」等），
 *   而不是显示一个空白标题——空白让人以为是加载中
 * @param chartType 图表形态，决定用哪套渲染与数据约束
 * @param series 指标序列。折线/柱状/面积/散点可多条；饼图与热力图不读它，
 *   环形进度与热力图只用第一条
 * @param timeWindowDays 时间窗，固定 7/30/90
 * @param showLegend 是否显示图例
 * @param categorySourceKey 分类数据源（仅饼图使用）
 * @param topN 饼图只展示前 N 项，其余归入「其他」
 * @param density 卡片密度。[ChartDensity.COMPACT] 时用方块样式渲染
 */
data class ChartCardConfig(
    val title: String = "",
    val chartType: ChartType,
    val series: List<ChartSeriesConfig>,
    val timeWindowDays: Int = 30,
    val showLegend: Boolean = true,
    val categorySourceKey: String? = null,
    val topN: Int? = null,
    val density: ChartDensity = ChartDensity.COMFORTABLE,
) {
    /**
     * 这份配置是否足以画出一张有意义的图。
     *
     * 各种形态的最低要求不同，不能让一张「选了折线但没选指标」的卡进库——
     * 它打开后只会显示空图，用户以为是坏了。
     *
     * 检查项刻意保守，只挡「必然画不出来」，不挡「可能没数据」：
     * 时间窗内没数据是正常的，不该因此禁止保存。
     */
    fun isRenderable(): Boolean {
        if (timeWindowDays !in CHART_TIME_WINDOWS_DAYS) return false
        return when (chartType) {
            // 时间序列类至少要一条有效序列
            ChartType.LINE, ChartType.COLUMN, ChartType.AREA, ChartType.SCATTER ->
                series.isNotEmpty() && series.all { it.isUsable() }

            // 饼图读分类数据，不用 series
            ChartType.PIE -> !categorySourceKey.isNullOrBlank()

            // 环形进度要一个指标作为当前值
            ChartType.RING -> series.isNotEmpty() && series.all { it.isUsable() }

            // 热力图只画一个指标：它一张图只有一种「深浅」标度，
            // 两条序列落在同一格上无法分辨谁是谁。
            // 判据是「恰好一条」而不是「至少一条」——多出来的序列读不到、也画不出，
            // 放它入库只会得到一张比配置少的图
            ChartType.HEATMAP -> series.size == 1 && series.all { it.isUsable() }
        }
    }

    /**
     * 这张卡默认显示的名字。
     *
     * 用户没起名时用类型名而不是空串：卡片标题是用户定位「这张图是什么」的第一眼信息，
     * 空着等于让每张没命名的图都长得一样。
     */
    fun displayTitle(): String = title.trim().ifEmpty { chartType.displayName() }
}

/** 图表类型的中文名。放在模型层是因为卡片的默认标题也要用它 */
fun ChartType.displayName(): String = when (this) {
    ChartType.LINE -> "折线图"
    ChartType.COLUMN -> "柱状图"
    ChartType.AREA -> "面积图"
    ChartType.SCATTER -> "散点图"
    ChartType.PIE -> "饼图"
    ChartType.RING -> "环形进度"
    ChartType.HEATMAP -> "热力图"
}


/**
 * 这条序列是否足以查到数据。
 *
 * 除了源与指标，还要检查「需要任务」的内置序列有没有绑定任务：
 * 连击、单条规则完成率没有任务就没有可统计的对象，放它入库只会得到一张空图，
 * 而用户看不出是自己少选了一步。这条规则以前只写在配置弹窗里，
 * 绕过界面的调用（导入、迁移、将来别的入口）就挡不住。
 */
private fun ChartSeriesConfig.isUsable(): Boolean {
    if (sourceKey.isBlank() || metricKey.isBlank()) return false
    if (BuiltinSeries.fromMetricKey(metricKey)?.needsTask == true && taskId == null) return false
    return true
}

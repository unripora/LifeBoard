package com.lifeboard.shared.panel

import java.time.LocalDate

/**
 * 一条已经分配好坐标轴的序列。
 *
 * @property axis 走左轴还是右轴。同单位的序列共用一根轴
 */
data class PlacedSeries(
    val series: ChartSeries,
    val axis: ChartAxisSlot,
    /** 原配置中的序号。名称可以重复，过滤无数据序列后也不能重新编号。 */
    val sourceIndex: Int,
)

/** Y 轴归属。与 Vico 的左右两根轴一一对应 */
enum class ChartAxisSlot { LEFT, RIGHT }

/**
 * 一次渲染需要的全部输入。
 *
 * @property xLabels 从第一个有数据的日期到最后一个，**逐日铺满**（含中间没有数据的日期）
 * @property series 分配好轴的序列
 * @property unitGroups 每个轴的单位，用于轴标题
 * @property warnings 需要显示给用户的说明（源失效、统计不完整等）
 * @property showLegend 是否在图表下方画图例
 */
data class ChartRenderModel(
    val xLabels: List<String>,
    val series: List<PlacedSeries>,
    val leftUnit: String?,
    val rightUnit: String?,
    val warnings: List<String>,
    val showLegend: Boolean = true,
) {
    /**
     * 日期 → 横轴下标的映射。
     *
     * 下标即「距第一个有数据日期的天数」，所以**没数据的日期仍然占一格**：
     * 时间轴的间距与真实时间成正比，「缺了 5 天」就该比「连续 5 天」宽 5 倍。
     * 断档本身由 [continuousRuns] 拆段表达——线在缺口处断开，但缺口宽度是真实的。
     */
    val xIndexByDate: Map<String, Int> =
        xLabels.withIndex().associate { (index, date) -> date to index }
}

/**
 * 把 [ChartSeries.points] 里的日期落到「真实时间」横轴上。
 *
 * ## 缺失日期怎么表达
 *
 * 查询层不补 0（见 `ChartSeries` 的注释），渲染层也不补点：
 * 横轴从**第一个有数据的日期**铺到**最后一个**，中间没有数据的日期只占刻度。
 * 断档由 [continuousRuns] 拆段表达：线段在缺口处断开，而不是被一条直线跨过去。
 *
 * ## 轴的分配规则
 *
 * 轴由**配置**决定，不由数据决定：[RequestedSeries.axis] 是用户在配置里选的，
 * 这里照办。之所以不能再按单位自动推断——用户把两条「步」都放在左轴是合法的，
 * 自动推断会把它改到右轴；而当两条序列单位相同、配置不同轴时，
 * 推断也无法表达「我就要左右各一根」。
 *
 * 只有配置里**没有**对应项时（老配置、调用方少传）才回退到按单位推断，
 * 规则与以前一致：
 * - 第一根轴的单位成为左轴
 * - 遇到不同单位时分配右轴
 * - 出现**第三种**不可比单位时，不发明第三根轴，而是把该序列排除并给出警告
 *
 * 两者都按**顺序**配对而不是按 `sourceKey + metricKey` 查表：
 * 同一个指标可以被加两次（绑定不同任务、不同轴），查表会错配。
 *
 * @param requestedSeries 配置里要求的序列（含期望的轴、单位与标签）
 * @param loaded 查询回来的序列，顺序与 [requestedSeries] 一一对应
 * @param showLegend 是否显示图例，原样带进渲染模型
 */
fun buildChartRenderModel(
    requestedSeries: List<RequestedSeries>,
    loaded: List<ChartSeries>,
    showLegend: Boolean = true,
): ChartRenderModel {
    val warnings = mutableListOf<String>()

    // X 轴取所有序列出现过的日期的并集，升序。
    // 用并集而不是交集：某条序列缺几天不该让其他序列也丢掉那几天
    val allDates = loaded.flatMap { s -> s.points.map { it.date } }
        .distinct()
        .sorted()

    val leftUnit = mutableListOf<String>()
    val rightUnit = mutableListOf<String>()
    val placed = mutableListOf<PlacedSeries>()

    loaded.forEachIndexed { index, series ->
        // 完全不可用的序列不画，但要让用户知道为什么
        when (val status = series.status) {
            is SeriesStatus.SourceUnavailable -> {
                warnings += "${series.label}：${status.reason}"
                return@forEachIndexed
            }
            is SeriesStatus.NoData -> {
                warnings += "${series.label}：所选时间窗内没有数据"
                return@forEachIndexed
            }
            is SeriesStatus.Partial -> {
                warnings += "${series.label}：${status.reason}"
            }
            SeriesStatus.Ok -> Unit
        }

        val unit = series.unit
        val requestedAxis = requestedSeries.getOrNull(index)?.axis
        val slot = if (requestedAxis != null) {
            // 配置说了算。两根轴各自记下单位，只用于轴标题
            val bucket = if (requestedAxis == ChartAxisSlot.LEFT) leftUnit else rightUnit
            if (!bucket.contains(unit)) bucket += unit
            requestedAxis
        } else {
            inferSlot(unit, leftUnit, rightUnit)
        }
        if (slot == null) {
            warnings += "${series.label}：单位「$unit」与已有坐标轴不兼容，未显示"
            return@forEachIndexed
        }

        // 一条序列的单位与它所在的那根轴不一致，说明用户把不同单位的东西
        // 塞进了同一根轴。刻度只按第一种单位画，这条线的读数就是错的，
        // 而且错得很隐蔽——它看起来只是一条高得离谱的线。
        // 这里给警告而不是排除它：排除会让用户以为数据没了
        val axisUnit = if (slot == ChartAxisSlot.LEFT) leftUnit.firstOrNull() else rightUnit.firstOrNull()
        if (unit.isNotBlank() && axisUnit != null && axisUnit != unit) {
            val axisName = if (slot == ChartAxisSlot.LEFT) "左轴" else "右轴"
            warnings += "${series.label}：单位「$unit」与${axisName}的「$axisUnit」不同，" +
                "它按${axisName}刻度读出来的值不准确"
        }

        placed += PlacedSeries(series, slot, sourceIndex = index)
    }

    return ChartRenderModel(
        xLabels = fullCalendar(allDates),
        series = placed,
        leftUnit = leftUnit.firstOrNull(),
        rightUnit = rightUnit.firstOrNull(),
        warnings = warnings,
        showLegend = showLegend,
    )
}

/**
 * 没有配置轴时的回退推断：按单位找一根能共用的轴。
 *
 * @return 分配到的轴；null 表示已经有两根不同单位的轴，第三种不可比
 */
private fun inferSlot(
    unit: String,
    leftUnit: MutableList<String>,
    rightUnit: MutableList<String>,
): ChartAxisSlot? {
    // 左轴空着或单位相同 → 左轴
    if (leftUnit.isEmpty() || leftUnit.contains(unit)) {
        if (!leftUnit.contains(unit)) leftUnit += unit
        return ChartAxisSlot.LEFT
    }
    // 右轴空着或单位相同 → 右轴
    if (rightUnit.isEmpty() || rightUnit.contains(unit)) {
        if (!rightUnit.contains(unit)) rightUnit += unit
        return ChartAxisSlot.RIGHT
    }
    return null
}

/**
 * 把「有数据的日期」补成完整的日历轴。
 *
 * 补齐的日期**只有刻度，没有数据点**：它让缺口在视觉上保持真实宽度，
 * 而不会凭空造出「那天是 0」的结论。
 *
 * ## 起点为什么是第一个有数据的日期
 *
 * 不用「窗口首日」当起点：30 天窗口里只有最近 3 天有数据时，前 27 天
 * 会变成一大片空白，把真正要看的数据挤到右侧一小条。从第一个有数据的日期起算，
 * 空白只剩下**数据之间真实的断档**，那才是用户想看到的。
 *
 * 数据为空时返回空列表：没有日期可供推算边界，也就没有轴可画。
 *
 * @param dates 有数据的日期，升序
 */
internal fun fullCalendar(dates: List<String>): List<String> {
    if (dates.isEmpty()) return emptyList()
    val start = LocalDate.parse(dates.first())
    val end = LocalDate.parse(dates.last())
    val days = end.toEpochDay() - start.toEpochDay() + 1
    return (0L until days).map { start.plusDays(it).toString() }
}

/**
 * 把一条序列按「连续日期」拆成若干段。
 *
 * ## 为什么必须拆
 *
 * 查询层刻意不补缺失日期（见 `ChartSeries` 的注释）。但画折线时如果直接把这些点
 * 连起来，中间断掉的几天会被一条直线跨过去，看起来像「这几天一直是这个数」。
 * 拆段后每段单独绘制，断档处就是真的断开。
 *
 * 判定用真实日期差而不是下标：下标相邻只说明「这两个日期都在窗口里有值」，
 * 可能中间隔了半个月。
 *
 * @return 每段至少一个点；没有点时返回空列表
 */
fun continuousRuns(points: List<SeriesPoint>): List<List<SeriesPoint>> {
    if (points.isEmpty()) return emptyList()
    val sorted = points.sortedBy { it.date }
    val runs = mutableListOf<MutableList<SeriesPoint>>()
    var current = mutableListOf(sorted.first())
    runs += current

    sorted.drop(1).forEach { point ->
        val previous = current.last()
        val gap = LocalDate.parse(point.date).toEpochDay() -
            LocalDate.parse(previous.date).toEpochDay()
        if (gap == 1L) {
            current += point
        } else {
            current = mutableListOf(point)
            runs += current
        }
    }
    return runs.map { it.toList() }
}

/**
 * 把序列折成渲染用的 `(x, y)` 对。
 *
 * 散点图每个点各成一段：它表达的是「这些天分别是多少」，点与点之间本来就没有
 * 连续关系，连成线反而是额外的断言。
 *
 * x 用 [xIndexByDate] 里「距窗口首日的天数」，所以缺口在图上占的是真实宽度，
 * 而不是被压成一个格子的间距。
 *
 * @param xIndexByDate 日期 → 横轴下标（即距窗口首日的天数）
 */
fun chartPoints(
    series: ChartSeries,
    xIndexByDate: Map<String, Int>,
    scatter: Boolean,
): List<List<Pair<Double, Double>>> {
    // 只保留横轴上确实存在的日期：落在窗口外的点没有位置可放
    val points = series.points
        .filter { xIndexByDate.containsKey(it.date) }
        .sortedBy { it.date }
    if (points.isEmpty()) return emptyList()

    if (scatter) {
        return points.map { point ->
            listOf(xIndexByDate.getValue(point.date).toDouble() to point.value)
        }
    }
    return continuousRuns(points).map { run ->
        run.map { point -> xIndexByDate.getValue(point.date).toDouble() to point.value }
    }
}

/** 渲染事务的序列输入，key 与用户可编辑的图例名称无关。 */
internal data class CartesianSeriesInput(
    val key: Int,
    val points: List<Pair<Double, Double>>,
)

internal fun ChartRenderModel.cartesianSeries(
    scatter: Boolean,
    transformY: (PlacedSeries, Double) -> Double = { _, value -> value },
): List<CartesianSeriesInput> = series.map { placed ->
    CartesianSeriesInput(
        key = placed.sourceIndex,
        points = chartPoints(placed.series, xIndexByDate, scatter).flatten().map { (x, y) ->
            x to transformY(placed, y)
        },
    )
}

/**
 * 用户配置里要求画的一条序列。
 *
 * @property label 图例名
 * @property unit 单位
 * @property axis 配置里指定的 Y 轴。渲染按它落轴，而不再按单位自动猜
 */
data class RequestedSeries(
    val label: String,
    val unit: String,
    val axis: ChartAxisSlot = ChartAxisSlot.LEFT,
)

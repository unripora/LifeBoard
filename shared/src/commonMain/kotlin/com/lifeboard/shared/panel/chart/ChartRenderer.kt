package com.lifeboard.shared.panel.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.dashboard.ChartType
import com.lifeboard.shared.panel.CategoryBreakdown
import com.lifeboard.shared.panel.ChartAxisSlot
import com.lifeboard.shared.panel.ChartRenderModel
import com.lifeboard.shared.panel.PlacedSeries
import com.lifeboard.shared.panel.chartPoints
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.pie.PieChart
import com.patrykandpatrick.vico.compose.pie.PieChartHost
import com.patrykandpatrick.vico.compose.pie.PieSize
import com.patrykandpatrick.vico.compose.pie.data.PieChartModelProducer
import com.patrykandpatrick.vico.compose.pie.data.pieSeries
import com.patrykandpatrick.vico.compose.pie.rememberPieChart
import kotlin.math.abs

/**
 * 按图表形态分派到具体渲染。
 *
 * ## 谁画什么
 *
 * 折线 / 面积 / 散点 / 柱状 / 饼图交给 **Vico**（`com.patrykandpatrick.vico`）：
 * 坐标轴、刻度取舍、双轴、缺口断线、多序列并排、点击定位全部由它负责，
 * 本项目不再维护这套几何计算。
 *
 * 热力图和环形进度仍然自绘：
 * - Vico 没有热力图。GitHub 打卡那种「周为列、星期为行」的日历布局
 *   Vico 的任何 layer 都表达不了。
 * - 环形进度要带「目标 / 未达标」的语义和中心文字，
 *   Vico 的 pie 只能表达占比，画出来是一个缺口的饼而不是进度环。
 *
 * 两处自绘都只有几十行且形态固定，不会随图表种类增加而膨胀。
 *
 * @param type 图表形态
 * @param model 时间序列的渲染输入（饼图不用）
 * @param categories 分类占比（仅饼图使用）
 * @param ringTarget 环形进度的目标值；null 表示无法计算百分比
 * @param compact 方块密度。只在迷你趋势图里使用，主体大图不受影响
 */
@Composable
fun ChartRenderer(
    type: ChartType,
    model: ChartRenderModel?,
    categories: CategoryBreakdown? = null,
    ringTarget: Double? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    when (type) {
        ChartType.LINE -> model?.let { CartesianChart(it, SeriesStyle.LINE, modifier, compact) }
        ChartType.AREA -> model?.let { CartesianChart(it, SeriesStyle.AREA, modifier, compact) }
        ChartType.SCATTER -> model?.let { CartesianChart(it, SeriesStyle.SCATTER, modifier, compact) }
        ChartType.COLUMN -> model?.let { CartesianChart(it, SeriesStyle.COLUMN, modifier, compact) }
        ChartType.PIE -> categories?.let { PieChart(it, modifier) }
        ChartType.RING -> RingChart(model, ringTarget, modifier)
        ChartType.HEATMAP -> model?.let { HeatmapChart(it, modifier) }
    }
}

/** 时间序列的几种画法。散点是「只画点、不连线」，本质是折线的变体 */
private enum class SeriesStyle { LINE, AREA, SCATTER, COLUMN }

/**
 * 方块密度用的迷你趋势图。
 *
 * 只画一条面积折线、不带任何轴与文字：它的唯一职责是「在数字旁边用形状提示趋势」。
 * 刻度放在这里只会让 120dp 高的方块显得拥挤，而读数已经由上方的大数字给了。
 *
 * 这里**没有**迁到 Vico：Vico 的坐标系即使不画轴也仍要跑一遍测量与布局，
 * 而这 20 行 Canvas 已经是最短路径，换引擎只会更慢、依赖更多。
 */
@Composable
fun MiniTrendChart(model: ChartRenderModel, modifier: Modifier = Modifier) {
    val placed = model.series.firstOrNull() ?: return
    val color = MaterialTheme.colorScheme.primary
    val segments = chartPoints(placed.series, model.xIndexByDate, scatter = false)
    if (segments.isEmpty()) return

    // 用整张图的最大值归一化，而不是这条线自己的最大值：
    // 方块里通常只有一条线，两者等价；一旦将来并排画两条，共用标度才不会各说各话
    val maxValue = model.series.maxOfOrNull { series -> series.maxValue() }?.takeIf { it > 0.0 } ?: 1.0
    val lastPoint = placed.series.points.maxByOrNull { it.date }

    Canvas(modifier = modifier) {
        val step = (model.xLabels.size - 1).coerceAtLeast(1)
        fun xOf(index: Int) = size.width * (index.toFloat() / step)
        fun yOf(value: Double) =
            size.height * (1f - (value / maxValue).coerceIn(0.0, 1.0).toFloat())

        segments.forEach { segment ->
            if (segment.size < 2) return@forEach
            val area = Path()
            val line = Path()
            segment.forEachIndexed { i, (x, y) ->
                val px = xOf(x.toInt())
                val py = yOf(y)
                if (i == 0) {
                    line.moveTo(px, py)
                    area.moveTo(px, size.height)
                    area.lineTo(px, py)
                } else {
                    line.lineTo(px, py)
                    area.lineTo(px, py)
                }
            }
            area.lineTo(xOf(segment.last().first.toInt()), size.height)
            area.close()
            drawPath(area, color.copy(alpha = 0.18f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        }

        // 末点单独点一个小圆：方块图看的就是「现在到哪了」
        lastPoint?.let { point ->
            val index = model.xIndexByDate[point.date] ?: return@let
            drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(xOf(index), yOf(point.value)))
        }
    }
}

/**
 * 笛卡尔图：折线 / 面积 / 散点 / 柱状，由 Vico 绘制。
 *
 * ## 双轴怎么落
 *
 * Vico 原生的做法是「一个轴一个 layer」，但 3.2.3 里两个 layer 并存时
 * `End` 轴不渲染刻度、右 layer 也被按左轴量程绘制（单 layer 时 `End` 完全正常，
 * 已用「只有右轴」的对照图确认过）。那是库内部行为，配置层面修不掉。
 *
 * 于是改成**数据层归一化**：右轴序列的值线性映射到左轴量程，图上只放一个 layer。
 * 右轴刻度用 `rightFormatter` 把位置反算回真实数值，所以数字仍然正确；
 * 代价是只有一套网格线，图下方会附一行说明。
 *
 * ## 缺口怎么处理
 *
 * 查询层不补缺失日期，渲染层也不补点。一条序列只调一次 `series(x, y, key)`，
 * 缺口处的 x 直接跳过——Vico 按 x 的真实间距连线，缺口那段斜率按「缺了几天」
 * 摊平，不会画出一个假的垂直落差。
 *
 * 不能把连续段拆成多次 `series(...)`：Vico 要求同一个 seriesKey 在一次
 * transaction 内唯一，拆开直接抛 `Series keys must be unique.`。
 *
 * ## 柱状图的多序列怎么并排
 *
 * 交给 `ColumnCartesianLayer`：同一天有几条序列就并排几根，Vico 自己分组。
 *
 * ## 横轴刻度
 *
 * 用 `ItemPlacer.aligned` 的默认参数，让 Vico 按**可用宽度**决定标几个日期。
 * 不能传自定义 `spacing`：那等于告诉 Vico「这些位置必须有标签」，
 * 它据此反推内容最小宽度，点一多就把图表撑得比容器宽，右轴被推出窗口。
 */
@Composable
private fun CartesianChart(
    model: ChartRenderModel,
    style: SeriesStyle,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (model.xLabels.isEmpty() || model.series.isEmpty()) return

    val colors = seriesColors()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chartHeight = if (compact) 120.dp else 200.dp

    // 配色按「整张图第几条序列」取，两条轴上的序列共用一条色带，
    // 否则左上第一条和右上第一条会同色，图例分不清。
    // 键里带下标：两条序列可能同名（同一个指标绑了两个任务），
    // 只按标签查会互相覆盖，最后两条线画成同一个颜色
    val seriesEntries = model.series.withIndex().map { (index, placed) ->
        Triple(placed, index, colors[index % colors.size])
    }

    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val textComponent = rememberTextComponent(style = labelStyle)
    val guideLine = rememberLineComponent(
        fill = Fill(gridColor),
        thickness = 1.dp,
    )

    // 轴的标题文字是「左轴单位」/「右轴单位」，只能拼成静态文案，
    // 不能在 let 分支里就地 remember：条件分支内的 remember 会让槽位数量
    // 随数据变化，重组时直接崩
    val leftTitle = model.leftUnit?.takeIf { it.isNotBlank() }?.let {
        rememberTextComponent(style = labelStyle)
    }
    val rightTitle = model.rightUnit?.takeIf { it.isNotBlank() }?.let {
        rememberTextComponent(style = labelStyle)
    }

    val leftSeries = model.series.filter { it.axis == ChartAxisSlot.LEFT }
    val rightSeries = model.series.filter { it.axis == ChartAxisSlot.RIGHT }
    val dualAxis = leftSeries.isNotEmpty() && rightSeries.isNotEmpty()

    // 双轴的画法：右轴的数值在**数据层**线性映射到左轴量程，图上只放一个 layer。
    //
    // 为什么不直接用 Vico 的 `verticalAxisPosition = End` + 两个 layer：
    // 单 layer 时 `End` 完全正常（刻度 1772–2172、线铺满高度），
    // 但两个 layer 并存时右轴既不显示刻度、数据也被按左轴量程绘制。
    // 那是 Vico 内部行为，不是配置能修好的（layer 数量、position、fixed 范围
    // 都验证过正确）。归一化只依赖「一个 layer 一个 Y 范围」这个最稳的路径。
    //
    // 代价：右轴不能再有自己的网格线（图上只有一套网格），
    // 右侧刻度靠 `rightFormatter` 把映射后的位置反算回真实数值，
    // 所以看到的数字仍然正确。
    val leftRange = yRangeOf(leftSeries.ifEmpty { rightSeries })
    val rightRange = yRangeOf(rightSeries)

    // 右轴真实值 → 左轴量程上的等价位置
    fun normalize(value: Double): Double {
        if (!dualAxis) return value
        val span = rightRange.endInclusive - rightRange.start
        if (span == 0.0) return leftRange.start
        val ratio = (value - rightRange.start) / span
        return leftRange.start + ratio * (leftRange.endInclusive - leftRange.start)
    }

    // 反向：左轴量程上的位置 → 右轴真实值，供右轴刻度文案使用
    val rightFormatter = remember(rightRange, leftRange, dualAxis) {
        CartesianValueFormatter { _, value, _ ->
            if (!dualAxis) {
                value.toDisplay()
            } else {
                val span = leftRange.endInclusive - leftRange.start
                if (span == 0.0) {
                    rightRange.start.toDisplay()
                } else {
                    val ratio = (value - leftRange.start) / span
                    (rightRange.start + ratio * (rightRange.endInclusive - rightRange.start))
                        .toDisplay()
                }
            }
        }
    }

    // 横轴刻度文案：把 x 下标（距首日的天数）翻回 `M/D`
    val bottomFormatter = remember(model.xLabels) {
        CartesianValueFormatter { _, value, _ ->
            val index = value.toInt().coerceIn(0, model.xLabels.lastIndex)
            shortDate(model.xLabels[index])
        }
    }

    val layers = buildList<CartesianLayer<*>> {
        val indexed = seriesEntries.filter { (placed, _, _) ->
            // 没有左轴时，右轴序列就是唯一的数据源，按左轴位置画
            if (dualAxis) placed.axis == ChartAxisSlot.LEFT else true
        }
        add(
            cartesianLayer(
                series = indexed,
                style = style,
                position = AxisPositionStart,
                yRange = leftRange,
            ),
        )
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(model, style, dualAxis, leftRange, rightRange) {
        modelProducer.runTransaction {
            // 一个 layer 对应一次 `lineModel{}` / `columnModel{}`。
            // 右轴的序列在这里已经把值映射进了左轴量程，所以只需要一个 model。
            //
            // 断档不靠「拆成多次 series(...)」表达：Vico 要求同一个 seriesKey
            // 在一次 transaction 里只能出现一次，拆开直接抛
            // "Series keys must be unique."。这里让一条序列在数据层就是连续的，
            // 缺口处 x 直接跳过——Vico 按 x 的真实间距连线，于是缺口那段
            // 斜率是按「缺了几天」摊平的，不会画出一个假的垂直落差。
            val models = model.series.map { placed ->
                placed.series.label to chartPoints(
                    placed.series,
                    model.xIndexByDate,
                    scatter = style == SeriesStyle.SCATTER,
                ).flatten().map { (x, y) ->
                    x to if (placed.axis == ChartAxisSlot.RIGHT) normalize(y) else y
                }
            }
            when (style) {
                SeriesStyle.COLUMN -> columnModel {
                    models.forEach { (key, entries) ->
                        series(entries.map { it.first }, entries.map { it.second }, key)
                    }
                }

                else -> lineModel {
                    models.forEach { (key, entries) ->
                        series(entries.map { it.first }, entries.map { it.second }, key)
                    }
                }
            }
        }
    }

    // 编辑器的提示文案：归一化后右轴的刻度和网格都跟着左轴走，
    // 这里把「哪些其实没生效」如实说出来，而不是让用户对着一条
    // 贴底的曲线猜配置错在哪
    val axisWarnings = buildList {
        if (rightSeries.isNotEmpty() && !dualAxis) {
            add("右轴没有对应序列，已忽略")
        }
        if (dualAxis) {
            add(
                "双轴为叠加显示：右轴序列已按左轴量程等比缩放，" +
                    "只保留一套网格线，刻度数值各自正确",
            )
        }
    }

    val chart = rememberCartesianChart(
        *layers.toTypedArray(),
        startAxis = VerticalAxis.rememberStart(
            titleComponent = leftTitle,
            line = guideLine,
            label = textComponent,
        ),
        endAxis = if (rightSeries.isEmpty()) {
            null
        } else {
            VerticalAxis.rememberEnd(
                titleComponent = rightTitle,
                line = guideLine,
                label = textComponent,
                // 右轴刻度的位置来自左轴量程，文案要按右轴真实值反算
                valueFormatter = rightFormatter,
            )
        },
        bottomAxis = HorizontalAxis.rememberBottom(
            label = textComponent,
            valueFormatter = bottomFormatter,
            // 用默认参数的 aligned：spacing 默认「每个 x 单位都是候选」，
            // isSizeAware 默认 true，于是 Vico 按**可用宽度**决定实际标几个日期。
            // 不能传自定义 spacing（之前传的是 `(点数-1)/4`）：那等于告诉 Vico
            // 「这些位置必须有标签」，它据此反推内容的最小宽度，
            // 点一多就把图表撑得比容器宽，右轴直接被推到窗口外。
            itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
        ),
    )

    // 缩放锁成 Zoom.Content：让整段数据缩到刚好填满可视宽度，
    // 而不是按「每个点固定 16dp」铺开——后者在 90 天时总宽必然超出，
    // 右轴会被推到窗口外裁掉。
    //
    // 注意 zoom 只在滚动启用时才被 Vico 消费（内部是 `scrollEnabled && zoomEnabled`），
    // 所以这里 `scrollEnabled = true`；再把 initial=min=max 全设成 Content，
    // 缩放区间被压成一个点，用户拖不动也捏不动，等价于「不可滚动」。
    val scrollState = rememberVicoScrollState(scrollEnabled = true)
    val zoomState = rememberVicoZoomState(
        zoomEnabled = true,
        initialZoom = Zoom.Content,
        minZoom = Zoom.Content,
        maxZoom = Zoom.Content,
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(chartHeight),
            scrollState = scrollState,
            zoomState = zoomState,
        )
        if (model.showLegend) {
            SeriesLegend(model, colors)
        }
        if (axisWarnings.isNotEmpty()) {
            axisWarnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

/** Vico 的 `Axis.Position.Vertical.Start`，单独抽出来避免长名字刷屏 */
private val AxisPositionStart: com.patrykandpatrick.vico.compose.cartesian.axis.Axis.Position.Vertical
    get() = com.patrykandpatrick.vico.compose.cartesian.axis.Axis.Position.Vertical.Start

private val AxisPositionEnd: com.patrykandpatrick.vico.compose.cartesian.axis.Axis.Position.Vertical
    get() = com.patrykandpatrick.vico.compose.cartesian.axis.Axis.Position.Vertical.End

/**
 * 一根轴上这批序列的 Y 范围，用来把该轴的刻度钉死。
 *
 * 范围上下各留 8% 余量，否则极值会正好压在绘图区边框上。
 * 全零或空数据时给一个兜底区间，避免 min == max 导致 Vico 除零。
 */
private fun yRangeOf(series: List<PlacedSeries>): ClosedFloatingPointRange<Double> {
    val values = series.flatMap { it.series.points.map { point -> point.value } }
    if (values.isEmpty()) return 0.0..1.0
    val min = values.min()
    val max = values.max()
    if (min == max) {
        val pad = if (min == 0.0) 1.0 else abs(min) * 0.1
        return (min - pad)..(max + pad)
    }
    val pad = (max - min) * 0.08
    return (min - pad)..(max + pad)
}

/**
 * 一根轴上的一组序列 → 一个 Vico layer。
 *
 * `verticalAxisPosition` 决定这个 layer 的数据按哪根轴读数，
 * 这是双轴图成立的唯一条件：不传就会全部落到全局 Y 范围上。
 *
 * 范围用 `fixed()` 显式钉死，而不是 `Intrinsic`。原因：transaction 里
 * 一次写两个 model 时，`Intrinsic` 下第二个 layer 的范围仍会被并进第一个，
 * 结果「千卡」被按「步」的 6000–14000 画成一条贴底的水平线。
 * 这里本轴的范围由调用方按本轴序列自己算好传进来，Vico 没有合并的余地。
 */
@Composable
private fun cartesianLayer(
    series: List<Triple<PlacedSeries, Int, Color>>,
    style: SeriesStyle,
    position: com.patrykandpatrick.vico.compose.cartesian.axis.Axis.Position.Vertical,
    yRange: ClosedFloatingPointRange<Double>,
): CartesianLayer<*> = when (style) {
    SeriesStyle.COLUMN -> ColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
            series.map { (_, _, color) ->
                rememberLineComponent(
                    fill = Fill(color),
                    thickness = 12.dp,
                    shape = RoundedCornerShape(2.dp),
                )
            },
        ),
        rangeProvider = CartesianLayerRangeProvider.fixed(
            minY = yRange.start,
            maxY = yRange.endInclusive,
        ),
        verticalAxisPosition = position,
    )

    else -> LineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(
            series.map { (_, _, color) ->
                when (style) {
                    SeriesStyle.AREA -> LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(color)),
                        areaFill = LineCartesianLayer.AreaFill.single(
                            Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent))),
                        ),
                        pointProvider = LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(rememberLineComponent(Fill(color), 6.dp), 6.dp),
                        ),
                    )

                    SeriesStyle.SCATTER -> LineCartesianLayer.Line(
                        // 散点不连线：fill 透明，只留点
                        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
                        pointProvider = LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(
                                rememberLineComponent(Fill(color), 7.dp),
                                7.dp,
                            ),
                        ),
                    )

                    else -> LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(color)),
                        pointProvider = LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(
                                rememberLineComponent(Fill(color), 6.dp),
                                6.dp,
                            ),
                        ),
                    )
                }
            },
        ),
        // 同 ColumnCartesianLayer：本轴的刻度只按本轴的序列算
        rangeProvider = CartesianLayerRangeProvider.fixed(
            minY = yRange.start,
            maxY = yRange.endInclusive,
        ),
        verticalAxisPosition = position,
    )
}

/**
 * 图例。
 *
 * 双轴时必须标出每条线属于哪根轴，否则「左右各一根轴」这件事完全看不出来。
 */
@Composable
private fun SeriesLegend(model: ChartRenderModel, colors: List<Color>) {
    val dualAxis = model.series.any { it.axis == ChartAxisSlot.LEFT } &&
        model.series.any { it.axis == ChartAxisSlot.RIGHT }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        model.series.forEachIndexed { index, placed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(10.dp).background(colors[index % colors.size], RoundedCornerShape(2.dp)))
                Text(placed.series.label, style = MaterialTheme.typography.labelSmall)
                if (dualAxis) {
                    // 轴名带上单位，用户才知道「右」这根轴量的是什么
                    val unit = if (placed.axis == ChartAxisSlot.LEFT) model.leftUnit else model.rightUnit
                    val suffix = if (placed.axis == ChartAxisSlot.LEFT) "左" else "右"
                    Text(
                        if (unit.isNullOrBlank()) suffix else "$suffix · $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 序列配色。
 *
 * 下标 0 固定用主色：单序列图（最常见）的观感与改造前保持一致。
 */
@Composable
private fun seriesColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.error,
        scheme.primaryContainer,
    )
}

/** 一条序列在窗口内的最大值 */
private fun PlacedSeries.maxValue(): Double =
    series.points.maxOfOrNull { it.value } ?: 0.0

/**
 * 饼图，由 Vico 绘制。
 *
 * 分块的顺序与「其他」的合并都在查询层完成，这里只负责按比例画。
 * 图例必须给：饼图没有坐标轴，不标名字就看不出哪个扇区是谁。
 */
@Composable
private fun PieChart(breakdown: CategoryBreakdown, modifier: Modifier = Modifier) {
    if (breakdown.slices.isEmpty()) return

    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val modelProducer = remember { PieChartModelProducer() }
    LaunchedEffect(breakdown) {
        modelProducer.runTransaction {
            pieSeries { series(breakdown.slices.map { it.value }) }
        }
    }

    val chart = rememberPieChart(
        sliceProvider = PieChart.SliceProvider.series(
            breakdown.slices.mapIndexed { index, _ ->
                PieChart.Slice(fill = Fill(colors[index % colors.size]))
            },
        ),
        outerSize = PieSize.Outer.Fill,
        innerSize = PieSize.Inner.Zero,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PieChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        breakdown.slices.forEachIndexed { index, slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 色块与扇区同色，否则用户要在图上猜哪个扇区对应哪一行
                Box(Modifier.size(10.dp).background(colors[index % colors.size], RoundedCornerShape(2.dp)))
                Text(
                    "${slice.label}  ${slice.value.toDisplay()} ${breakdown.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 环形进度。
 *
 * 目标未知时不编百分比——只显示当前值并说明原因。
 * 编一个 100% 出来比什么都不显示更糟：用户会以为目标达成了。
 *
 * 这里没有用 Vico 的 pie：那是「各占多少」的表达，
 * 进度环要的是「已达成 / 还差多少」，中间还要放百分比文字。
 */
@Composable
private fun RingChart(
    model: ChartRenderModel?,
    target: Double?,
    modifier: Modifier = Modifier,
) {
    val series = model?.series?.firstOrNull()
    // 取窗口内最后一个有值的点：进度表达的是「到现在为止」
    val current = series?.series?.points?.lastOrNull()?.value

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(series?.series?.label ?: "环形进度", style = MaterialTheme.typography.titleSmall)

        if (current == null) {
            Text(
                "所选时间窗内没有数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val unit = series.series.unit

        val ratio = if (target != null && target > 0.0) {
            (current / target).coerceIn(0.0, 1.0).toFloat()
        } else {
            null
        }

        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(120.dp)) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                if (ratio != null) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * ratio,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (ratio != null) "${(ratio * 100).toInt()}%" else current.toDisplay(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ratio == null) {
            Text(
                "没有唯一的目标值（来自任务规则的阈值），只显示当前值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "目标 ${target?.toDisplay()} $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 热力图：GitHub 打卡日历式的格子。
 *
 * ## 为什么不是 Vico
 *
 * Vico 没有任何 heatmap 能力；而它的柱状图做不出「周为列、星期为行、
 * 每格一个数值」这种二维日历布局。自绘也就三十行。
 *
 * ## 布局怎么定
 *
 * 横轴是周、纵轴是星期，和 GitHub 首页那块一样：
 * 每列 7 格，列从左到右按时间推进，行固定为周一到周日。
 * 这样「每周同一天对齐在同一行」，一眼能看出「我是不是总在周末掉链子」——
 * 这正是按月排日历给不出的信息。
 *
 * ## 颜色怎么定
 *
 * 按值在窗口内的**相对分位**取深浅（0 → 最浅，窗口最大值 → 最深），
 * 而不是绝对阈值：步数的「达标」和得分的「达标」量级完全不同，
 * 写死阈值意味着换一个指标颜色就全糊了。
 *
 * 没有数据的格子显式画成极浅的底色，与「值为 0」区分开：
 * 前者是没采集到，后者是真的为 0，两者混在一起会得出错误结论。
 */
@Composable
private fun HeatmapChart(model: ChartRenderModel, modifier: Modifier = Modifier) {
    val placed = model.series.firstOrNull() ?: return
    val points = placed.series.points.associate { it.date to it.value }
    if (points.isEmpty()) return

    val maxValue = points.values.maxOrNull() ?: 0.0
    val baseColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    // 按日期排出「第几周 / 星期几」。以周一为一行起点，与日历习惯一致
    val days = model.xLabels.map { date ->
        val local = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
        val value = points[date]
        HeatCell(
            date = date,
            // DayOfWeek.getValue()：周一=1 … 周日=7
            row = local?.dayOfWeek?.value?.minus(1) ?: 0,
            value = value,
        )
    }
    if (days.isEmpty()) return

    val firstDate = runCatching { java.time.LocalDate.parse(days.first().date) }.getOrNull() ?: return
    // 首列前面要留空：窗口首日可能是周三，前三格不该画成「没数据」
    val leadingBlank = firstDate.dayOfWeek.value - 1
    val columns = (leadingBlank + days.size + 6) / 7

    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${placed.series.label} · 越深表示越高（峰值 ${maxValue.toDisplay()} ${placed.series.unit}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 左侧一列星期缩写：没有它就看不出哪一行是周末
            Column(
                modifier = Modifier.height(HEAT_ROW_HEIGHT * 7 + HEAT_GAP * 6),
                verticalArrangement = Arrangement.spacedBy(HEAT_GAP),
            ) {
                weekLabels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 画布宽度按「列数 × 方格宽」定，而不是 fillMaxWidth：
            // 90 天只有 13 列，填满整行会把格子撑成长条；而 7 天只有 1 列时
            // 又会在右侧留下大片空白。用定宽让它自己量出该有的宽度，
            // 超宽（比如 365 天）时由外层横向滚动兜住
            Canvas(
                modifier = Modifier
                    .width(HEAT_ROW_HEIGHT * columns + HEAT_GAP * (columns - 1))
                    .height(HEAT_ROW_HEIGHT * 7 + HEAT_GAP * 6),
            ) {
                // 行距 = 格子高 + 间隙。格子的 y 按它排，格子本身的高度也必须用
                // 同一个间距算——否则格子比自己的槽位矮，越往下偏移越明显
                val slotHeight = HEAT_ROW_HEIGHT.toPx() + HEAT_GAP.toPx()
                val step = HEAT_ROW_HEIGHT.toPx() + HEAT_GAP.toPx()
                val cellWidth = HEAT_ROW_HEIGHT.toPx()

                fun xOf(column: Int) = column * step
                fun yOf(row: Int) = row * slotHeight

                days.forEachIndexed { index, day ->
                    val column = (leadingBlank + index) / 7
                    val color = if (day.value == null) {
                        emptyColor
                    } else if (maxValue <= 0.0) {
                        baseColor.copy(alpha = 0.15f)
                    } else {
                        // 至少给 0.18 的透明度：0 值也要有颜色，否则和「没数据」长得一样
                        val ratio = (day.value / maxValue).coerceIn(0.0, 1.0)
                        baseColor.copy(alpha = (0.18f + ratio.toFloat() * 0.82f))
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(xOf(column), yOf(day.row)),
                        size = Size(cellWidth, HEAT_ROW_HEIGHT.toPx()),
                        cornerRadius = CornerRadius(HEAT_CELL_CORNER.toPx()),
                    )
                }
            }
        }
    }
}

/** 热力图的一格 */
private data class HeatCell(val date: String, val row: Int, val value: Double?)

private val HEAT_ROW_HEIGHT = 12.dp
private val HEAT_GAP = 3.dp
private val HEAT_CELL_CORNER = 2.dp

/** 数值展示：整数不带小数点，其余最多一位 */
private fun Double.toDisplay(): String =
    if (this == toLong().toDouble()) toLong().toString() else {
        val scaled = kotlin.math.round(this * 10).toLong()
        "${scaled / 10}.${abs(scaled % 10)}"
    }

/**
 * `2026-03-04` → `3/4`。
 *
 * 用字符串直接取后两段而不是 `LocalDate.parse`：横轴标签每帧都会拼，
 * 解析 90 个日期不值得；而且这个函数只处理本应用自己写出的 ISO 日期。
 */
private fun shortDate(date: String): String {
    val parts = date.split('-')
    if (parts.size != 3) return date
    val month = parts[1].trimStart('0').ifEmpty { "0" }
    val day = parts[2].trimStart('0').ifEmpty { "0" }
    return "$month/$day"
}

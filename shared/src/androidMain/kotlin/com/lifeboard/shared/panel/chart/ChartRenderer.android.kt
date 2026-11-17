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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.dashboard.ChartType
import com.lifeboard.shared.panel.CategoryBreakdown
import com.lifeboard.shared.panel.ChartAxisSlot
import com.lifeboard.shared.panel.ChartRenderModel
import com.lifeboard.shared.panel.chartPoints
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Android 端实现：笛卡尔图用 Vico，饼图与环形图用 Canvas 自绘。
 *
 * @see ChartRenderer 共享声明
 */
@Composable
actual fun ChartRenderer(
    type: ChartType,
    model: ChartRenderModel?,
    categories: CategoryBreakdown?,
    ringTarget: Double?,
    modifier: Modifier,
) {
    when (type) {
        ChartType.LINE -> model?.let { CartesianSeriesChart(it, SeriesStyle.LINE, modifier) }
        ChartType.AREA -> model?.let { CartesianSeriesChart(it, SeriesStyle.AREA, modifier) }
        ChartType.SCATTER -> model?.let { CartesianSeriesChart(it, SeriesStyle.SCATTER, modifier) }
        ChartType.COLUMN -> model?.let { CartesianSeriesChart(it, SeriesStyle.COLUMN, modifier) }
        ChartType.PIE -> categories?.let { PieChart(it, modifier) }
        ChartType.RING -> RingChart(model, ringTarget, modifier)
    }
}

/** 时间序列的几种画法。散点是「只画点、不连线」，本质是折线的变体 */
private enum class SeriesStyle { LINE, AREA, SCATTER, COLUMN }

/**
 * 笛卡尔坐标系里的时间序列图。
 *
 * 折线 / 面积 / 散点共用 line layer，只改线型；柱状用 column layer。
 * 双轴规则与折线一致：按轴分组各建一个 layer，同一组内共用一根轴的范围。
 */
@Composable
private fun CartesianSeriesChart(
    model: ChartRenderModel,
    style: SeriesStyle,
    modifier: Modifier = Modifier,
) {
    if (model.xLabels.isEmpty() || model.series.isEmpty()) return

    val producer = remember { CartesianChartModelProducer() }

    LaunchedEffect(model, style) {
        producer.runTransaction {
            val left = model.series.filter { it.axis == ChartAxisSlot.LEFT }
            val right = model.series.filter { it.axis == ChartAxisSlot.RIGHT }
            listOf(left, right).forEach { group ->
                if (group.isEmpty()) return@forEach
                if (style == SeriesStyle.COLUMN) {
                    columnModel {
                        group.forEach { placed ->
                            // 柱状不拆段：按下标逐日一根，断档处没有柱子，
                            // 本身就能表达「那天没数据」。下标取自并集，才能与折线对齐
                            val points = chartPoints(placed.series, model.xIndexByDate, scatter = false)
                                .flatten()
                            if (points.isNotEmpty()) {
                                series(points.map { it.first }, points.map { it.second })
                            }
                        }
                    }
                } else {
                    lineModel {
                        group.forEach { placed ->
                            // 每段单独成为一条线：缺口处不连线
                            chartPoints(placed.series, model.xIndexByDate, style == SeriesStyle.SCATTER)
                                .forEach { segment ->
                                    series(
                                        segment.map { it.first },
                                        segment.map { it.second },
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    val hasLeft = model.series.any { it.axis == ChartAxisSlot.LEFT }
    val hasRight = model.series.any { it.axis == ChartAxisSlot.RIGHT }
    // 颜色按序列在模型里的下标取，图例用同一套规则，两边才对得上
    val leftColors = model.series.withIndex()
        .filter { it.value.axis == ChartAxisSlot.LEFT }
        .map { seriesColor(it.index) }
    val rightColors = model.series.withIndex()
        .filter { it.value.axis == ChartAxisSlot.RIGHT }
        .map { seriesColor(it.index) }
    val layers = buildList {
        if (hasLeft) {
            add(rememberSeriesLayer(style, Axis.Position.Vertical.Start, leftColors))
        }
        if (hasRight) {
            add(rememberSeriesLayer(style, Axis.Position.Vertical.End, rightColors))
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                *layers.toTypedArray(),
                startAxis = if (hasLeft) VerticalAxis.rememberStart() else null,
                endAxis = if (hasRight) VerticalAxis.rememberEnd() else null,
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, x, _ -> formatDay(x.toInt(), model.xLabels) },
                ),
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )
        if (model.showLegend) {
            SeriesLegend(model)
        }
    }
}

/**
 * 多序列图例。
 *
 * 双轴、多单位时没有图例就完全看不出哪条线是谁——颜色本身不带信息，
 * 只有和名字排在一起才成为可读的标记。
 *
 * 颜色按序列在模型里的下标取，与 [seriesColor] 同一套规则，
 * 所以图例上的色块就是图上那根线的颜色，不是另外配的。
 */
@Composable
private fun SeriesLegend(model: ChartRenderModel) {
    val axisLabels = listOfNotNull(
        model.leftUnit?.takeIf { it.isNotBlank() }?.let { "左轴 $it" },
        model.rightUnit?.takeIf { it.isNotBlank() }?.let { "右轴 $it" },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        model.series.forEachIndexed { index, placed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(seriesColor(index), RoundedCornerShape(2.dp)),
                )
                Text(
                    placed.series.label,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (axisLabels.size > 1) {
                    // 只有双轴时标出归属：单轴图上说「左轴」反而多一层噪音
                    Text(
                        if (placed.axis == ChartAxisSlot.LEFT) "左" else "右",
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
 * 不用「用户指定颜色」是因为当前配置界面没有取色入口，`colorArgb` 永远为空；
 * 等有了取色入口，把 `PlacedSeries` 里的颜色放在这个函数前面优先即可。
 *
 * 下标 0 固定用主色：单序列图（最常见）与改造前的观感保持一致。
 */
@Composable
private fun seriesColor(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.error,
    )
    return palette[index % palette.size]
}

@Composable
private fun rememberSeriesLayer(
    style: SeriesStyle,
    position: Axis.Position.Vertical,
    colors: List<Color>,
) =
    if (style == SeriesStyle.COLUMN) {
        rememberColumnCartesianLayer(
            // 每根柱子一个组件，下标对应第几条序列；只给一个时会整组同色
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                colors.map { rememberLineComponent(fill = Fill(it)) },
            ),
            verticalAxisPosition = position,
        )
    } else {
        rememberLineCartesianLayer(
            lineProvider = lineProviderFor(style, colors),
            rangeProvider = CartesianLayerRangeProvider.auto(),
            verticalAxisPosition = position,
        )
    }

/**
 * 三种线型。
 *
 * - 面积：线与底之间填充，适合看累计量的走势
 * - 散点：只画点不连线。Vico 没有独立散点类型，官方给的替代做法就是隐藏连线
 * - 折线：常规
 *
 * 颜色按 [colors] 逐序列给：多序列时两条线同色就等于没画图例，
 * 而单序列时 [colors] 只有一个元素，退化为主色，与改造前一致。
 *
 * 用 `LineCartesianLayer.Line(...)` 直接构造而不是 `rememberLine`：
 * 后者是对 `LineCartesianLayer.Companion` 的扩展，从 Kotlin 侧调用时
 * 需要把它当作 Receiver 才能解析，直接构造更不容易出错。
 */
@Composable
private fun lineProviderFor(style: SeriesStyle, colors: List<Color>): LineCartesianLayer.LineProvider {
    val fallback = MaterialTheme.colorScheme.primary
    val resolved = colors.ifEmpty { listOf(fallback) }
    return when (style) {
        SeriesStyle.SCATTER -> LineCartesianLayer.LineProvider.series(
            *resolved.map { color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
                    // 线宽 0 即不画连线，只保留点
                    stroke = LineCartesianLayer.LineStroke.Continuous(0.dp),
                    areaFill = null,
                    pointProvider = LineCartesianLayer.PointProvider.single(
                        LineCartesianLayer.Point(
                            component = rememberShapeComponent(Fill(color)),
                            size = 6.dp,
                        ),
                    ),
                )
            }.toTypedArray(),
        )

        SeriesStyle.AREA -> LineCartesianLayer.LineProvider.series(
            *resolved.map { color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
                    stroke = LineCartesianLayer.LineStroke.Continuous(2.dp),
                    areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.25f))),
                )
            }.toTypedArray(),
        )

        else -> LineCartesianLayer.LineProvider.series(
            *resolved.map { color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
                    stroke = LineCartesianLayer.LineStroke.Continuous(2.dp),
                    areaFill = null,
                )
            }.toTypedArray(),
        )
    }
}

/**
 * 饼图。
 *
 * 分块比例由查询层排好序并合成「其他」，画法本身与平台无关，
 * 但为了让形态分派留在 [ChartRenderer] 一处，这一份跟笛卡尔图一起放在 androidMain。
 */
@Composable
private fun PieChart(breakdown: CategoryBreakdown, modifier: Modifier = Modifier) {
    if (breakdown.slices.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 自绘环形扇区：Vico 的饼图 API 尚在实验期，且这里只需要「按比例分块」，
            // 用 Canvas 画能同时避免把实验性依赖扩散到更多地方
            PieCanvas(breakdown)
        }
        // 图例必须给：饼图没有坐标轴，不标名字就看不出哪个扇区是谁
        breakdown.slices.forEach { slice ->
            Text(
                "${slice.label}  ${slice.value.toDisplay()} ${breakdown.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 按比例画扇区。数据在查询层已排好序并合成「其他」 */
@Composable
private fun PieCanvas(breakdown: CategoryBreakdown) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val total = breakdown.slices.sumOf { it.value }

    Canvas(modifier = Modifier.size(140.dp)) {
        if (total <= 0.0) return@Canvas
        val stroke = 0f
        var start = -90f
        breakdown.slices.forEachIndexed { index, slice ->
            val sweep = (slice.value / total * 360.0).toFloat()
            drawArc(
                color = colors[index % colors.size],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - stroke * 2, size.height - stroke * 2),
                style = Stroke(width = 0f),
            )
            start += sweep
        }
    }
}

/**
 * 环形进度。
 *
 * 自绘而不是用饼图：饼图是「若干部分占整体」，进度是「一个值达到目标的多少」，
 * 语义不同。自绘一个环只需两段弧，比把单值伪装成饼图更直白。
 *
 * @param target 目标值。为 null 或非正时无法算百分比，显示原始值并说明
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

        // 目标未知时不能编一个百分比出来。显示原始值和说明，比画一个假的 100% 诚实
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
            // 目标可能缺失也可能不唯一，但对用户来说下一步动作不同：
            // 前者要去建规则，后者要去统一阈值。这里如实说明，不编百分比
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

/** 数值展示：整数不带小数点，其余最多一位 */
private fun Double.toDisplay(): String =
    if (this == toLong().toDouble()) toLong().toString() else String.format("%.1f", this)

/** X 轴刻度：横轴存的是「有数据的日期」下标，这里还原成 `M/d` */
private fun formatDay(index: Int, xLabels: List<String>): String {
    val date = xLabels.getOrNull(index) ?: return ""
    return LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M/d"))
}

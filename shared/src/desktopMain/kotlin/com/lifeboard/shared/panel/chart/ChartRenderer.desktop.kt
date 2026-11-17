package com.lifeboard.shared.panel.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.time.LocalDate

/**
 * 桌面端实现：全部用 Compose Canvas 自绘，不依赖任何 Android 专属图表库。
 *
 * ## 为什么不用 Vico
 *
 * Vico 只有 Android 构件（依赖 `android.graphics` 与 `androidx` 若干库），
 * 桌面端拿不到。为了两端用同一份面板代码，只能把渲染这一层做成 expect/actual：
 * Android 继续用 Vico（它支持双轴、缩放、手势，自绘重写不划算），
 * 桌面端给一份够用的自绘版本。
 *
 * ## 桌面版刻意简化的地方
 *
 * - 不画 Y 轴刻度数字，只在图例里标出「左轴 / 右轴」的单位
 * - 不画 X 轴所有刻度，只标首、中、末三个日期
 * - 不支持缩放与平移
 *
 * 这些都不影响「看一眼趋势」这个主用途，而自绘要把它们做对需要不少布局计算。
 * 需要精细读数的场合应该去看规则页的原始数据，而不是图表本身。
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
        ChartType.LINE -> model?.let { CartesianChart(it, SeriesStyle.LINE, modifier) }
        ChartType.AREA -> model?.let { CartesianChart(it, SeriesStyle.AREA, modifier) }
        ChartType.SCATTER -> model?.let { CartesianChart(it, SeriesStyle.SCATTER, modifier) }
        ChartType.COLUMN -> model?.let { CartesianChart(it, SeriesStyle.COLUMN, modifier) }
        ChartType.PIE -> categories?.let { PieChart(it, modifier) }
        ChartType.RING -> RingChart(model, ringTarget, modifier)
    }
}

private enum class SeriesStyle { LINE, AREA, SCATTER, COLUMN }

/**
 * 笛卡尔图的自绘版本。
 *
 * ## 双轴怎么画
 *
 * 左右两根轴各自归一化：左轴序列按左轴的最大值缩放，右轴序列按右轴的最大值缩放。
 * 两条不同单位的线因此能画在同一张图上，而不会因为量级差几个数量级变成一条贴着底的线。
 *
 * ## 缺口怎么处理
 *
 * 每段单独 `moveTo` / `lineTo`，不跨越断档。所以图上「线断了」就是真的没数据，
 * 而不是那天恰好是 0。
 */
@Composable
private fun CartesianChart(
    model: ChartRenderModel,
    style: SeriesStyle,
    modifier: Modifier = Modifier,
) {
    if (model.xLabels.isEmpty() || model.series.isEmpty()) return

    val leftSeries = model.series.filter { it.axis == ChartAxisSlot.LEFT }
    val rightSeries = model.series.filter { it.axis == ChartAxisSlot.RIGHT }
    // 每根轴各自的最大值。用 max 而不是 max-min：图从 0 起画，
    // 便于直观比较柱高与线高；负值按 0 处理，否则会画到画布外
    val leftMax = leftSeries.maxOfOrNull { it.maxValue() }?.takeIf { it > 0.0 } ?: 1.0
    val rightMax = rightSeries.maxOfOrNull { it.maxValue() }?.takeIf { it > 0.0 } ?: 1.0

    val colors = seriesColors(model)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisStep = (model.xLabels.size - 1).coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            // 留一点内边距，否则贴边的最值点会被裁掉半个线宽
            val padding = 8.dp.toPx()
            val plotLeft = padding
            val plotTop = padding
            val plotWidth = size.width - padding * 2
            val plotHeight = size.height - padding * 2
            if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

            fun xOf(index: Int): Float =
                plotLeft + plotWidth * (index.toFloat() / axisStep)

            fun yOf(value: Double, max: Double): Float {
                val ratio = (value / max).coerceIn(0.0, 1.0).toFloat()
                return plotTop + plotHeight * (1f - ratio)
            }

            // 横向参考线：0 / 25% / 50% / 75% / 100% 五条，仅作为读数辅助
            repeat(5) { step ->
                val y = plotTop + plotHeight * (step / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(plotLeft, y),
                    end = Offset(plotLeft + plotWidth, y),
                    strokeWidth = 1f,
                )
            }

            model.series.forEachIndexed { index, placed ->
                val max = if (placed.axis == ChartAxisSlot.LEFT) leftMax else rightMax
                val color = colors[index]
                val segments = chartPoints(
                    placed.series,
                    model.xIndexByDate,
                    scatter = style == SeriesStyle.SCATTER,
                )

                when (style) {
                    SeriesStyle.COLUMN -> {
                        // 每根柱子按下标定位，宽度 = 单格宽度的 60%，留出间隙
                        val slot = plotWidth / axisStep
                        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
                        segments.flatten().forEach { (x, y) ->
                            val left = xOf(x.toInt()) - barWidth / 2
                            val top = yOf(y, max)
                            drawRect(
                                color = color,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, (plotTop + plotHeight) - top),
                            )
                        }
                    }

                    SeriesStyle.SCATTER -> {
                        segments.forEach { segment ->
                            segment.forEach { (x, y) ->
                                drawCircle(
                                    color = color,
                                    radius = 3.dp.toPx(),
                                    center = Offset(xOf(x.toInt()), yOf(y, max)),
                                )
                            }
                        }
                    }

                    SeriesStyle.AREA -> {
                        // 面积：先把每一段连成折线，再闭回底部填充
                        segments.forEach { segment ->
                            if (segment.size < 2) {
                                segment.forEach { (x, y) ->
                                    drawCircle(
                                        color = color,
                                        radius = 2.dp.toPx(),
                                        center = Offset(xOf(x.toInt()), yOf(y, max)),
                                    )
                                }
                                return@forEach
                            }
                            val line = Path()
                            val area = Path()
                            segment.forEachIndexed { i, (x, y) ->
                                val px = xOf(x.toInt())
                                val py = yOf(y, max)
                                if (i == 0) {
                                    line.moveTo(px, py)
                                    area.moveTo(px, plotTop + plotHeight)
                                    area.lineTo(px, py)
                                } else {
                                    line.lineTo(px, py)
                                    area.lineTo(px, py)
                                }
                            }
                            area.lineTo(xOf(segment.last().first.toInt()), plotTop + plotHeight)
                            area.close()
                            drawPath(area, color.copy(alpha = 0.25f))
                            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
                        }
                    }

                    SeriesStyle.LINE -> {
                        segments.forEach { segment ->
                            if (segment.size < 2) {
                                // 单点段画个小圆点，否则「只有一天有数据」时图上什么都没有
                                segment.forEach { (x, y) ->
                                    drawCircle(
                                        color = color,
                                        radius = 2.dp.toPx(),
                                        center = Offset(xOf(x.toInt()), yOf(y, max)),
                                    )
                                }
                                return@forEach
                            }
                            val path = Path()
                            segment.forEachIndexed { i, (x, y) ->
                                val px = xOf(x.toInt())
                                val py = yOf(y, max)
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
        }

        // 只标首 / 中 / 末三个日期：自绘的文字排布做全刻度容易重叠，
        // 而这三个点足够定位整张图的时间范围
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(0, model.xLabels.size / 2, model.xLabels.size - 1)
                .distinct()
                .forEach { index ->
                    Text(
                        shortDate(model.xLabels[index]),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        if (model.showLegend) {
            SeriesLegend(model, colors)
        }
    }
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(colors[index], RoundedCornerShape(2.dp)),
                )
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

/** 序列配色。与 Android 端同一套下标规则，两端观感一致 */
@Composable
private fun seriesColors(model: ChartRenderModel): List<Color> {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    return model.series.indices.map { palette[it % palette.size] }
}

/** 一条序列在窗口内的最大值，用于它所在那根轴的归一化 */
private fun PlacedSeries.maxValue(): Double =
    series.points.maxOfOrNull { it.value } ?: 0.0

/**
 * 饼图。
 *
 * 扇区用 `drawArc` 的 `useCenter = true` 画成实心扇形，与 Android 端一致。
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
    val total = breakdown.slices.sumOf { it.value }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                if (total <= 0.0) return@Canvas
                var start = -90f
                breakdown.slices.forEachIndexed { index, slice ->
                    val sweep = (slice.value / total * 360.0).toFloat()
                    drawArc(
                        color = colors[index % colors.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = true,
                    )
                    start += sweep
                }
            }
        }
        breakdown.slices.forEach { slice ->
            Text(
                "${slice.label}  ${slice.value.toDisplay()} ${breakdown.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 环形进度。
 *
 * 目标未知时不编百分比——与 Android 端同一策略，只显示当前值并说明原因。
 */
@Composable
private fun RingChart(
    model: ChartRenderModel?,
    target: Double?,
    modifier: Modifier = Modifier,
) {
    val series = model?.series?.firstOrNull()
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

/** 数值展示：整数不带小数点，其余最多一位 */
private fun Double.toDisplay(): String =
    if (this == toLong().toDouble()) toLong().toString() else formatOneDecimal(this)

/** `String.format` 在桌面端可用，但这里保持与 Android 端一致的输出（逗号小数点问题） */
private fun formatOneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}

/** `2026-03-04` → `3/4`，与 Android 端的 X 轴标签格式一致 */
private fun shortDate(date: String): String = runCatching {
    val parsed = LocalDate.parse(date)
    "${parsed.monthValue}/${parsed.dayOfMonth}"
}.getOrDefault(date)

package com.lifeboard.app.ui.plugin.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.BarChartSpec
import com.lifeboard.plugin.api.ChartPoint
import com.lifeboard.plugin.api.ChartSpec
import com.lifeboard.plugin.api.LineChartSpec
import com.lifeboard.plugin.api.ProgressRingSpec
import kotlin.math.max

/**
 * 图表渲染（宿主内置模板）。
 *
 * 全部手写，不引第三方图表库：项目已有的柱状图也是手写比例，
 * 引一个库只为三张小图不划算，而且库的默认配色很难跟随玻璃主题。
 *
 * **数据量在这里收口**。插件传什么都得先经过 [limitPoints]：
 * 详情页是同步渲染，几千个点会直接把主线程拖住。
 * 这是宿主的责任，不能指望插件自觉。
 */
@Composable
fun ChartRenderer(spec: ChartSpec, modifier: Modifier = Modifier) {
    when (spec) {
        is LineChartSpec -> LineChart(spec, modifier)
        is BarChartSpec -> BarChart(spec, modifier)
        is ProgressRingSpec -> ProgressRing(spec, modifier)
    }
}

/**
 * 限制数据点数量。
 *
 * 超出上限时**等距抽样**而不是取前 N 个：取前 N 个会让折线只显示开头一段，
 * 后面的趋势直接消失，比不显示更误导。
 */
private fun limitPoints(points: List<ChartPoint>, max: Int): List<ChartPoint> {
    if (points.size <= max) return points
    val step = points.size.toFloat() / max
    return (0 until max).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
}

/** 折线图：点连线 + 首尾标签 */
@Composable
private fun LineChart(spec: LineChartSpec, modifier: Modifier = Modifier) {
    val points = limitPoints(spec.points, MAX_POINTS)
    if (points.isEmpty()) {
        EmptyChart(modifier)
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.12f)
    val maxValue = max(points.maxOf { it.value }, 1.0)

    Column(modifier = modifier.fillMaxWidth()) {
        // 峰值与单位单独提出来：折线本身看不出绝对量级
        Text(
            "峰值 ${formatValue(points.maxOf { it.value })}${spec.unit}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 8.dp),
        ) {
            val w = size.width
            val h = size.height
            val stepX = if (points.size > 1) w / (points.size - 1) else w

            // y 轴留 10% 顶部余量，峰值不会贴着上边框
            val scaleY = h * 0.9f / maxValue.toFloat()

            val linePath = Path()
            val fillPath = Path()
            points.forEachIndexed { i, p ->
                val x = i * stepX
                val y = h - (p.value.toFloat() * scaleY)
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo((points.size - 1) * stepX, h)
            fillPath.close()

            drawPath(fillPath, color = fillColor)
            drawPath(
                linePath,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AxisLabel(points.first().label)
            AxisLabel(points.last().label)
        }
    }
}

/** 柱状图：等宽柱，按最大值定高，可高亮一根（通常是今天） */
@Composable
private fun BarChart(spec: BarChartSpec, modifier: Modifier = Modifier) {
    val bars = limitPoints(spec.bars, MAX_POINTS)
    if (bars.isEmpty()) {
        EmptyChart(modifier)
        return
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val maxValue = max(bars.maxOf { it.value }, 1.0)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, bar ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 柱高按比例；0 值也留 2dp，否则那一列会完全消失、看起来像缺数据
                    val fraction = (bar.value / maxValue).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((fraction * 90).dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (index == spec.highlightIndex) activeColor else idleColor,
                            ),
                    )
                    Text(
                        bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        color = if (index == spec.highlightIndex) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        Text(
            "最高 ${formatValue(bars.maxOf { it.value })}${spec.unit}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 环形进度：中心文字 + 外圈弧 */
@Composable
private fun ProgressRing(spec: ProgressRingSpec, modifier: Modifier = Modifier) {
    // 插件给的 fraction 未必在 0..1：收敛而不是画一段越界的弧
    val fraction = spec.fraction.coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val arc = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(96.dp)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(
                    size.width - stroke,
                    size.height - stroke,
                )
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (fraction > 0f) {
                    drawArc(
                        color = arc,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                spec.centerText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        spec.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyChart(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "暂无数据",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 数值展示：整数不带小数点，小数留一位，避免「1234.0 步」这种别扭写法 */
private fun formatValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)

/** 图表最多渲染的点数，超出由宿主抽样 */
private const val MAX_POINTS = 60

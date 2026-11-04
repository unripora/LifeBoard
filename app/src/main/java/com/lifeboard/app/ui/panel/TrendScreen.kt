package com.lifeboard.app.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.screen.ScreenTimeContent
import com.lifeboard.core.util.Dates

/**
 * 趋势页（M3.5）。
 *
 * 三块：区间汇总 → 每日得分柱状图 → 分任务达成率排行。
 * 汇总放最前面，因为用户来这个页面通常先想知道"这周怎么样"，
 * 而不是先看某一天的具体数字。
 */
@Composable
fun TrendScreen(vm: TrendViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrendRange.entries.forEach { range ->
                FilterChip(
                    selected = state.range == range,
                    onClick = { vm.selectRange(range) },
                    label = { Text(range.label) },
                )
            }
        }

        SummaryCard(state)

        DailyChartCard(state)

        TaskRankCard(state)

        // 原始指标区块：直接复用 M1 的内容组件，让用户能对照"任务达成"与"底层数据"
        ScreenTimeContent()

        if (state.loading) {
            Text(
                "统计中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(state: TrendUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("区间汇总", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("累计得分", trimNumber(state.totalScore))
                Stat("日均得分", "%.1f".format(state.averageScore))
                Stat("平均完成率", "${(state.averageCompletion * 100).toInt()}%")
                Stat("全达成天数", "${state.perfectDays}")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DailyChartCard(state: TrendUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("每日得分", style = MaterialTheme.typography.titleSmall)
            if (state.dailyScores.isEmpty()) {
                Text(
                    "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val max = state.dailyScores.maxOf { it.second }.coerceAtLeast(1.0)
            // 柱宽固定、横向滚动，30 天视图下也能看清每根柱子，不做压缩
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                state.dailyScores.forEach { (date, value) ->
                    val isToday = date == Dates.today()
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((92 * (value / max).toFloat().coerceIn(0.02f, 1f)).dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isToday) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                ),
                        )
                        // 30 天视图下只标周一的日期，否则标签会挤成一团
                        val showLabel = state.range == TrendRange.WEEK || date.dayOfWeek.value == 1
                        Text(
                            if (showLabel) "${date.dayOfMonth}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                if (state.range == TrendRange.WEEK) "周一至今日" else "近 30 天（仅标注周一日期）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskRankCard(state: TrendUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分任务达成率", style = MaterialTheme.typography.titleSmall)
            if (state.tasks.isEmpty()) {
                Text(
                    "还没有任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            state.tasks.forEach { trend ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            trend.task.title +
                                if (trend.task.enabled) "" else "（已停用）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${trend.completedDays}/${trend.totalDays}" +
                                " · ${(trend.rate * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { trend.rate },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Text(
                        "当前连击 ${trend.currentStreak} 天 · 最长 ${trend.bestStreak} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun trimNumber(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

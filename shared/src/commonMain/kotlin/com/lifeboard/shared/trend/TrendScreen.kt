package com.lifeboard.shared.trend

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.screentime.ScreenTimeContent
import com.lifeboard.shared.screentime.ScreenTimeViewModel

/**
 * 趋势页（M3.5，M11 补上屏幕时长块）。
 *
 * 四块：区间汇总 → 每日得分柱状图 → 分任务达成率排行 → 屏幕使用时长。
 * 汇总放最前面，因为用户来这个页面通常先想知道「这周怎么样」，
 * 而不是先看某一天的具体数字。
 *
 * ## 屏幕时长块为什么现在能放了
 *
 * 早先这块被摘掉：桌面端的 `ScreenTimeBackend` 是 `UnsupportedScreenTimeBackend`，
 * 调用即抛异常，搬过来会让整页崩在渲染期。
 * M11 给桌面端补了真实实现（窗口焦点记账），两端终于都有可用后端，
 * 于是块本身下沉到 `shared.screentime` 后由两端共用。
 *
 * @param screenTime 屏幕时长后端。传 null 时整块不画——
 *   用于「这个平台确实没有这个能力」的场景，而不是画一个永远空着的卡片
 * @param onOpenScreenTimePermission 点「去开启」时跳系统授权页。
 *   桌面端没有这个入口，传 null 后那只按钮不渲染
 */
@Composable
fun TrendScreen(
    panelScope: PanelScope,
    modifier: Modifier = Modifier,
    screenTime: ScreenTimeBackend? = null,
    onOpenScreenTimePermission: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val vm = remember(panelScope, coroutineScope) {
        TrendViewModel(panelScope, coroutineScope)
    }
    val state by vm.state.collectAsState()

    // 首次进入时若数据已过期（跨天），重新拉一次：页面是常驻的，
    // 不刷新的话切回来看的还是昨天算出来的「本周」
    LaunchedEffect(Unit) {
        if (!state.loading) vm.selectRange(state.range)
    }

    Column(
        modifier = modifier
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

        if (screenTime != null) {
            ScreenTimeSection(
                backend = screenTime,
                onOpenPermission = onOpenScreenTimePermission,
            )
        }

        if (state.loading) {
            Text(
                "统计中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 趋势页底部那块屏幕使用时长。
 *
 * 单独包一层是为了让 ViewModel 的生命周期跟着这块走：
 * 用户不看这块时不该有后台在反复读库，所以放在这里而不是页顶。
 */
@Composable
private fun ScreenTimeSection(
    backend: ScreenTimeBackend,
    onOpenPermission: (() -> Unit)?,
) {
    val coroutineScope = rememberCoroutineScope()
    val vm = remember(backend, coroutineScope) { ScreenTimeViewModel(backend, coroutineScope) }
    val state by vm.state.collectAsState()

    LaunchedEffect(vm) { vm.refresh() }

    ScreenTimeContent(
        state = state,
        onOpenPermission = onOpenPermission,
        onRecheck = vm::refresh,
        onCollect = vm::collectNow,
    )
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

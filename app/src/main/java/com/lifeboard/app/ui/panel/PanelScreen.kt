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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassProgress
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.util.Dates
import com.lifeboard.server.health.SourceHealthChecker
import java.time.LocalDate

/**
 * 首页仪表盘（M3.4）。
 *
 * 从上到下的信息层次：今日总分 → 待办列表 → 本周趋势。
 * 总分放最上面，是因为它是这个应用唯一的"一眼结论"；
 * 任务行只展示判断所需的四项：名称、时段、当前/目标、连击。
 */
@Composable
fun PanelScreen(
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onOpenMiHealthLogin: () -> Unit = {},
    /** 变化时重新判定数据源健康度（如刚完成小米账号登录） */
    refreshKey: Int = 0,
    vm: PanelViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TaskRow?>(null) }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) vm.refreshHealth()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScoreCard(state = state, onRefresh = { vm.reevaluate() })

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("今日待办", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${state.completedCount} / ${state.activeRows.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.rows.isEmpty()) {
                Text(
                    "还没有任务，先建一个吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.rows.forEach { row ->
                    TaskItem(
                        row = row,
                        onMarkDone = { vm.markDone(row) },
                        onEdit = { onEditTask(row.task.id) },
                        onToggle = { vm.toggleEnabled(row) },
                        onDelete = { pendingDelete = row },
                    )
                    HorizontalDivider()
                }
            }

            Button(onClick = onNewTask) { Text("新建任务") }
        }

        WeekCard(scores = state.weekScores)

        if (state.brokenSources.isNotEmpty()) {
            SourceHealthCard(
                entries = state.brokenSources,
                onOpenSettings = {
                    // 应用内动作（如小米健康登录）没有系统设置页，交给外层覆盖页
                    if (!vm.openSourceSettings(it)) onOpenMiHealthLogin()
                },
            )
        }

        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("「${row.task.title}」的完成记录与连击也会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTask(row)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 今日得分卡。
 *
 * 与其他卡片用同一种 GlassCard 底色，不用 primaryContainer 另起一套颜色——
 * 之前它独自偏色，夹在几张普通卡中间显得很突兀，而且它并不是页面上唯一重点。
 * 强调只落在「数字」与「进度条」上，靠内容而不是底色区分主次。
 */
@Composable
private fun ScoreCard(state: PanelUiState, onRefresh: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("今日得分", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                trimScore(state.weightedScore),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "/ ${trimScore(state.totalWeight)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassProgress(progress = state.completionRate, height = 8.dp)
        Text(
            "${state.date} · 完成 ${state.completedCount}/${state.activeRows.size}" +
                " · 最长连击 ${state.bestCurrentStreak} 天",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text(if (state.loading) "刷新中…" else "重新判定")
        }
    }
}

@Composable
private fun TaskItem(
    row: TaskRow,
    onMarkDone: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = row.task
    var expanded by remember { mutableStateOf(false) }
    val dimmed = !task.enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (row.completed) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (dimmed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!task.enabled) Badge("已停用", MaterialTheme.colorScheme.outline)
                LevelBadge(task.remindLevel)
            }
            TextButton(onClick = { expanded = !expanded }) { Text("···") }
        }

        Text(
            "${PanelViewModel.formatMinute(task.windowStartMinute)}-" +
                "${PanelViewModel.formatMinute(task.windowEndMinute)}" +
                " · ${PanelViewModel.formatValue(row.value, task.metricBucket.unit)}" +
                " / ${PanelViewModel.formatValue(task.threshold, task.metricBucket.unit)}" +
                " · 权重 ${trimScore(task.weight)}" +
                if (row.currentStreak > 0) " · 连击 ${row.currentStreak} 天" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 关联了规则时，把规则的生效时间与达成状态直接写出来：
        // 用户不需要再去「规则」页对照，就能判断"这条规则今天到底有没有达成"
        if (row.ruleSummary.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "规则：${row.ruleSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Badge(
                    if (row.completed) "已达成" else "未达成",
                    if (row.completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        GlassProgress(progress = row.progress, height = 4.dp)

        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!row.completed) {
                    TextButton(onClick = onMarkDone) { Text("手动完成") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onToggle) { Text(if (task.enabled) "停用" else "启用") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun LevelBadge(level: RemindLevel) {
    val color = when (level) {
        RemindLevel.NONE -> MaterialTheme.colorScheme.outline
        RemindLevel.WEAK -> MaterialTheme.colorScheme.secondary
        RemindLevel.STRONG -> MaterialTheme.colorScheme.error
    }
    Badge(PanelViewModel.levelName(level), color)
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun WeekCard(scores: List<Pair<LocalDate, Double>>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("本周得分", style = MaterialTheme.typography.titleSmall)
        if (scores.isEmpty()) {
            Text(
                "本周暂无数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }
        // 用 1.0 兜底，避免全为 0 时除零；柱高下限 2% 保证 0 分也能看到基线
        val max = scores.maxOf { it.second }.coerceAtLeast(1.0)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            scores.forEach { (date, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        trimScore(value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .fillMaxWidth()
                            .height((88 * (value / max).toFloat().coerceIn(0.02f, 1f)).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (date == Dates.today()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                },
                            ),
                    )
                    Text(
                        WEEK_LABELS[date.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 数据源状态（M4.8）。
 *
 * 只列出需要用户知道的源：缺权限的给一个直接跳设置的按钮，
 * 二期未接入的明确标注"二期接入"，避免用户看到永远是 0 却以为是采集坏了。
 */
@Composable
private fun SourceHealthCard(
    entries: List<SourceHealthChecker.Entry>,
    onOpenSettings: (SourceHealthChecker.Entry) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("数据源状态", style = MaterialTheme.typography.titleSmall)
        entries.forEach { entry ->
            val missing = entry.state == SourceHealthChecker.State.NO_PERMISSION
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (!missing) {
                            Badge("二期接入", MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (entry.actionKey != null) {
                    TextButton(onClick = { onOpenSettings(entry) }) { Text("去授权") }
                }
            }
        }
    }
}

/** 去掉无意义的小数位：3.0 → "3"，3.5 → "3.5" */
private fun trimScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

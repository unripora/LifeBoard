package com.lifeboard.app.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType

/**
 * 任务编辑页（M3.3）。
 *
 * 一个表单页，新建与编辑共用。数据源、分桶、档位都用 FilterChip，
 * 比下拉菜单少一次点击，且所有选项一眼可见，便于比较。
 */
@Composable
fun TaskEditScreen(
    taskId: Long,
    onDone: () -> Unit,
    vm: TaskEditViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()

    LaunchedEffect(taskId) { vm.load(taskId) }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (taskId == 0L) "新建任务" else "编辑任务",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        OutlinedTextField(
            value = state.title,
            onValueChange = vm::setTitle,
            label = { Text("任务名称") },
            placeholder = { Text("例如：每天阅读 30 分钟") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("数据源") {
            ChipRow(
                options = SourceType.entries.map { SourceRef.Builtin(it) },
                selected = state.source,
                label = { it.displayName },
                onSelect = vm::setSource,
            )
        }

        Section("量纲") {
            if (state.ruleId != 0L) {
                // 关联了规则：量纲只有规则一个事实来源，这里只读展示
                Text(
                    "由关联的采集规则决定（${bucketName(state.metricBucket)}），无需配置。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                ChipRow(
                    options = state.availableBuckets,
                    selected = state.metricBucket,
                    label = { bucketName(it) },
                    onSelect = vm::setBucket,
                )
                Text(
                    "「完成」= 一天做没做成（1 或 0），打卡签到类用它；" +
                        "「计数」= 一天做了几次，如喝 8 杯水。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.threshold,
                onValueChange = vm::setThreshold,
                label = { Text("达成阈值" + if (state.unit.isEmpty()) "" else "（${state.unit}）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.weight,
                onValueChange = vm::setWeight,
                label = { Text("权重") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "权重决定该任务在今日得分里占的比重，越大越重要。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("未完成提醒") {
            ChipRow(
                options = RemindLevel.entries,
                selected = state.remindLevel,
                label = { PanelViewModel.levelName(it) },
                onSelect = vm::setRemindLevel,
            )
            Text(
                when (state.remindLevel) {
                    RemindLevel.NONE -> "只记录是否完成，不做任何提示"
                    RemindLevel.WEAK -> "到点发一条通知"
                    RemindLevel.STRONG -> "到点响铃 + 震动，需手动停止"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.remindLevel != RemindLevel.NONE) {
                // 判定时刻相对「时段结束」偏移。负数提前、正数延后。
                // 默认 +5：规则时段同时是读屏窗口，准点判定会出现
                //「提醒响了，但已经读不到、没法补救」的尴尬。
                Text(
                    "判定时刻：时段结束 ${offsetText(state.windowOffsetMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.windowOffsetMinutes.toFloat(),
                    onValueChange = { vm.setWindowOffset(it.toInt()) },
                    valueRange = -60f..60f,
                    steps = 119,
                )
                Text(
                    "负数表示提前判定，正数表示延后判定。默认 +5 分钟，" +
                        "让读屏在时段结束后还能多计一会儿，避免提醒响了却没机会补救。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("完成提醒") {
            ChipRow(
                options = RemindLevel.entries,
                selected = state.doneRemindLevel,
                label = { PanelViewModel.levelName(it) },
                onSelect = vm::setDoneRemindLevel,
            )
            Text(
                when (state.doneRemindLevel) {
                    RemindLevel.NONE -> "完成时不发通知"
                    RemindLevel.WEAK -> "完成时弹出顶部横幅通知"
                    RemindLevel.STRONG -> "完成时弹横幅并响铃 + 震动"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("完成时弹出小浮窗", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "小浮窗只在 LifeBoard 打开时显示。系统不允许应用在后台弹 Toast，" +
                            "那种情况请用上面的横幅通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.doneToastEnabled,
                    onCheckedChange = vm::setDoneToastEnabled,
                )
            }
        }

        Section("关联采集规则") {
            Text(
                "选一条规则后，任务的生效时间与提醒时刻都由它决定，" +
                    "不需要在这里再设一遍时段。超过规则时段仍未达成才会提醒。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = rules
            if (options.isEmpty()) {
                Text(
                    "还没有可用的规则。先去「规则」页建一条，再回来关联。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.ruleId == 0L,
                        onClick = { vm.setRule(null) },
                        label = { Text("不关联（沿用自定义时段）", style = MaterialTheme.typography.labelSmall) },
                    )
                    options.forEach { option ->
                        FilterChip(
                            selected = state.ruleId == option.id,
                            onClick = { vm.setRule(option) },
                            label = {
                                Text(
                                    "${option.appLabel} · ${option.sourceName}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                options.firstOrNull { it.id == state.ruleId }?.let { chosen ->
                    Text(
                        "生效时间：${chosen.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 未关联规则时才需要手动设时间段，关联后由规则决定
        if (state.ruleId == 0L) {
            Section("自定义时段（未关联规则时生效）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("开始", style = MaterialTheme.typography.bodySmall)
                        Text(
                            PanelViewModel.formatMinute(state.windowStart),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.shiftStart(-30) }) { Text("-30") }
                        TextButton(onClick = { vm.shiftStart(30) }) { Text("+30") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("结束", style = MaterialTheme.typography.bodySmall)
                        Text(
                            PanelViewModel.formatMinute(state.windowEnd),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.shiftEnd(-30) }) { Text("-30") }
                        TextButton(onClick = { vm.shiftEnd(30) }) { Text("+30") }
                    }
                }
                Text(
                    "判定发生在结束前 5 分钟，若届时仍未达成才提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("启用该任务", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.enabled,
                onCheckedChange = vm::setEnabled,
            )
        }

        if (state.error.isNotBlank()) {
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::save, enabled = !state.saving) {
                Text(if (state.saving) "保存中…" else "保存")
            }
            OutlinedButton(onClick = onDone) { Text("取消") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(label(option), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

private fun bucketName(bucket: MetricBucket): String = when (bucket) {
    MetricBucket.DURATION_MINUTES -> "时长"
    MetricBucket.STEPS -> "步数"
    MetricBucket.SLEEP_MINUTES -> "睡眠"
    MetricBucket.COUNT -> "计数"
    MetricBucket.FLAG -> "完成"
}

/** 把分钟偏移显示成 "+5 分钟 / -5 分钟 / 准时" 这种好读的形式 */
private fun offsetText(minutes: Int): String = when {
    minutes > 0 -> "后 $minutes 分钟"
    minutes < 0 -> "前 ${-minutes} 分钟"
    else -> "准时"
}

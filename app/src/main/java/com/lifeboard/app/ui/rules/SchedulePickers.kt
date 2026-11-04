package com.lifeboard.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.core.schedule.RepeatType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 重复周期选择。
 *
 * 周期与"星期/日期"是联动关系：选了「自定义星期」才显示星期多选，
 * 选了「每月」才显示几号。全都平铺出来会让人不知道该填哪个。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatSelector(
    repeatType: RepeatType,
    weekdays: Set<Int>,
    anchorDay: Int,
    anchorMonth: Int,
    onRepeatType: (RepeatType) -> Unit,
    onWeekdays: (Set<Int>) -> Unit,
    onAnchorDay: (Int) -> Unit,
    onAnchorMonth: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RepeatType.entries.forEach { type ->
                FilterChip(
                    selected = type == repeatType,
                    onClick = { onRepeatType(type) },
                    label = { Text(type.displayName, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Text(
            repeatType.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (repeatType) {
            RepeatType.WEEKLY, RepeatType.CUSTOM_WEEKDAYS -> {
                if (repeatType == RepeatType.WEEKLY) {
                    Text("每周的哪一天", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { day ->
                            FilterChip(
                                selected = weekdays == setOf(day),
                                onClick = { onWeekdays(setOf(day)) },
                                label = { Text(WEEKDAY_NAMES[day - 1], style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                } else {
                    Text("勾选生效的星期（可多选）", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { day ->
                            FilterChip(
                                selected = day in weekdays,
                                onClick = {
                                    onWeekdays(
                                        if (day in weekdays) weekdays - day else weekdays + day,
                                    )
                                },
                                label = { Text(WEEKDAY_NAMES[day - 1], style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }

            RepeatType.MONTHLY -> {
                NumberPickerRow(
                    label = "每月几号（1-31，该月无此日则按月末）",
                    value = anchorDay,
                    range = 1..31,
                    onChange = onAnchorDay,
                )
            }

            RepeatType.YEARLY -> {
                NumberPickerRow(label = "月份", value = anchorMonth, range = 1..12, onChange = onAnchorMonth)
                NumberPickerRow(label = "日期", value = anchorDay, range = 1..31, onChange = onAnchorDay)
            }

            RepeatType.DAILY, RepeatType.WORKDAY -> Unit
        }
    }
}

/**
 * 数字选择：弹出列表框让用户点，不做文本输入。
 * 1-31 这种范围小的枚举，点选比手动输快且不会输错。
 */
@Composable
private fun NumberPickerRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TimeChip(text = "$value", modifier = Modifier.width(120.dp)) { show = true }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("选择") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(range.toList()) { n ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                        ) {
                            TextButton(onClick = {
                                onChange(n)
                                show = false
                            }) {
                                Text(
                                    "$n" + if (n == value) " ✓" else "",
                                    fontWeight = if (n == value) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } },
        )
    }
}

/**
 * 日期选择（可选，空串表示不限）。
 *
 * 用系统 `DatePicker`：自己实现一套日历控件既费代码又难用，
 * 系统控件自带无障碍支持与本地化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeChip(
                text = date.ifBlank { "不限" },
                modifier = Modifier.weight(1f),
                onClick = { show = true },
            )
            if (date.isNotBlank()) {
                TextButton(onClick = { onChange("") }) { Text("清除") }
            }
        }
    }

    if (show) {
        val initial = remember(date) {
            runCatching {
                if (date.isBlank()) null else LocalDate.parse(date)
            }.getOrNull()
        }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(d.toString())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        ) {
            DatePicker(state = state)
        }
    }
}

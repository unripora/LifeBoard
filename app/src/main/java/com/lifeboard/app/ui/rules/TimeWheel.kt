package com.lifeboard.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.core.schedule.TimeWindow

/**
 * 小时/分钟滚轮。
 *
 * 为什么不做文本输入框：旧版让用户手填 "HH:mm"，非法输入要么静默吞掉、
 * 要么存进库里再在采集时解析失败，属于典型的"用户不知道自己做错了"。
 * 滚轮从根上消灭了这种输入错误的可能。
 *
 * 分别选「小时」和「分钟」，不合成 1440 项的整列表——
 * 合成后要滚很久才能从 09:00 到 20:00。
 */
@Composable
fun TimeWheelDialog(
    title: String,
    initialMinute: Int,
    minuteStep: Int = 5,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember { mutableIntStateOf(initialMinute / 60) }
    var minute by remember { mutableIntStateOf((initialMinute % 60 / minuteStep) * minuteStep) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "当前：%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WheelColumn(
                        label = "时",
                        values = (0..23).toList(),
                        selected = hour,
                        modifier = Modifier.weight(1f),
                        onChange = { hour = it },
                    )
                    WheelColumn(
                        label = "分",
                        values = (0 until 60 step minuteStep).toList(),
                        selected = minute,
                        modifier = Modifier.weight(1f),
                        onChange = { minute = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 单列滚轮。
 *
 * 用 LazyColumn + 选中项高亮实现，不引 `LazyColumn` 之外的滚轮库（体积约束）。
 * 打开时自动滚到选中项，否则每次都要从 0 开始滑。
 */
@Composable
private fun WheelColumn(
    label: String,
    values: List<Int>,
    selected: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    val index = values.indexOf(selected).coerceAtLeast(0)
    LaunchedEffect(Unit) {
        state.scrollToItem((index - 2).coerceAtLeast(0))
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            state = state,
            modifier = Modifier.height(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(values) { value ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .width(88.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onChange(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "%02d".format(value),
                        style = if (isSelected) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 时段编辑行：一个时段的开始/结束 + 删除 */
@Composable
fun WindowEditorRow(
    index: Int,
    window: TimeWindow,
    canDelete: Boolean,
    onChange: (TimeWindow) -> Unit,
    onDelete: () -> Unit,
) {
    var editingStart by remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }

    if (editingStart != null) {
        val isStart = editingStart == true
        TimeWheelDialog(
            title = if (isStart) "时段 ${index + 1} 开始" else "时段 ${index + 1} 结束",
            initialMinute = if (isStart) window.startMinute else window.endMinute,
            onConfirm = { picked ->
                onChange(
                    if (isStart) window.copy(startMinute = picked) else window.copy(endMinute = picked),
                )
                editingStart = null
            },
            onDismiss = { editingStart = null },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimeChip(
            text = TimeWindow.formatMinute(window.startMinute),
            modifier = Modifier.weight(1f),
            onClick = { editingStart = true },
        )
        Text("至", style = MaterialTheme.typography.bodyMedium)
        TimeChip(
            text = TimeWindow.formatMinute(window.endMinute),
            modifier = Modifier.weight(1f),
            onClick = { editingStart = false },
        )
        if (canDelete) {
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
    if (window.crossMidnight) {
        Text(
            "结束早于开始，按跨零点处理（次日 ${TimeWindow.formatMinute(window.endMinute)} 结束）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 可点击的时间块，替代输入框 */
@Composable
fun TimeChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

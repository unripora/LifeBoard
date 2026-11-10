package com.lifeboard.app.ui.task

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.TimeUnit

/**
 * 顶栏的任务指示器。
 *
 * 没任务时是一个静止的细圆环，有任务时那圈弧线转起来。
 * 两种状态用同一个直径、同一线宽，切换时不会跳尺寸。
 */
@Composable
fun TaskIndicatorButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by TaskCenter.tasks.collectAsStateWithLifecycle()
    val running = tasks.count { it.running }

    IconButton(onClick = onClick, modifier = modifier) {
        if (running > 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            // 静止态：同直径、同线宽的空心圆，切换时不跳尺寸
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * 任务列表弹窗。
 *
 * 进行中的排在上面并带进度圈，已完成的按时间倒序跟在后面。
 * 空列表时给一句话，而不是弹一个空白框。
 */
@Composable
fun TaskListDialog(onDismiss: () -> Unit) {
    val tasks by TaskCenter.tasks.collectAsStateWithLifecycle()
    val running = tasks.filter { it.running }
    val finished = tasks.filterNot { it.running }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (running.isEmpty()) "任务记录" else "正在执行 ${running.size} 项",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            if (tasks.isEmpty()) {
                Text(
                    "还没有执行过任务。同步插件、导入数据时这里会显示进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    running.forEach { TaskRow(it) }
                    if (running.isNotEmpty() && finished.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Text(
                            "最近完成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    finished.forEach { TaskRow(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 一行任务：状态点 + 标题 + 时间，失败时补一行原因 */
@Composable
private fun TaskRow(task: BackgroundTask) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .background(statusColor(task), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium)
            if (task.detail.isNotBlank()) {
                Text(
                    task.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                timeText(task),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val error = task.error
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (task.running) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun statusColor(task: BackgroundTask): Color = when {
    task.running -> MaterialTheme.colorScheme.primary
    task.error != null -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

/**
 * 「刚刚完成 / 3 分钟前完成 / 2 天前完成」。
 *
 * 一天以前只给时刻看不出是哪天，所以用天数而不是时间点。
 */
private fun timeText(task: BackgroundTask): String {
    val end = task.finishedAt
    if (task.running) return "进行中"
    if (end == null) return ""
    val delta = (System.currentTimeMillis() - end).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    return when {
        minutes < 1 -> "刚刚完成"
        minutes < 60 -> "${minutes} 分钟前完成"
        minutes < 60 * 24 -> "${TimeUnit.MINUTES.toHours(minutes)} 小时前完成"
        else -> "${TimeUnit.MILLISECONDS.toDays(delta)} 天前完成"
    }
}

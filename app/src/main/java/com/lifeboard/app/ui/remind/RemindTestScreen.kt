package com.lifeboard.app.ui.remind

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.server.remind.RemindPermissions

/**
 * 提醒验证页（M2.7）。
 *
 * 三块内容：权限自检 → 三档提醒一键测试 → 执行日志。
 * M3 有正式任务管理后，本页可退化为设置里的"提醒自检"入口。
 */
@Composable
fun RemindTestScreen(vm: RemindTestViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            items = state.permissions,
            onOpen = { key -> RemindPermissions.open(context, key) },
            onRecheck = vm::refresh,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("三档提醒测试", style = MaterialTheme.typography.titleSmall)
                Text(
                    "测试任务的阈值设为 999，永远不达成，因此随时可以触发提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.createTestTask(RemindLevel.NONE) }) { Text("建不提醒") }
                    Button(onClick = { vm.createTestTask(RemindLevel.WEAK) }) { Text("建弱提醒") }
                    Button(onClick = { vm.createTestTask(RemindLevel.STRONG) }) { Text("建强提醒") }
                }
                OutlinedButton(onClick = vm::clearTestTasks) { Text("清理测试任务") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("任务列表", style = MaterialTheme.typography.titleSmall)
                if (state.tasks.isEmpty()) {
                    Text(
                        "暂无启用任务，先创建一个测试任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.tasks.forEach { task ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "#${task.id} · ${RemindTestViewModel.levelName(task.remindLevel)}" +
                                        " · 阈值 ${task.threshold.toInt()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { vm.triggerNow(task.id) },
                                enabled = !state.busy,
                            ) { Text("立即触发") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("执行日志", style = MaterialTheme.typography.titleSmall)
                if (state.log.isEmpty()) {
                    Text(
                        "尚无操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.log.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    items: List<RemindPermissions.Item>,
    onOpen: (String) -> Unit,
    onRecheck: () -> Unit,
) {
    val missing = items.filter { !it.granted }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (missing.isEmpty()) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (missing.isEmpty()) "提醒权限已就绪" else "提醒权限待开启",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${if (item.granted) "✓" else "✗"} ${item.title}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!item.granted) {
                        TextButton(onClick = { onOpen(item.key) }) { Text("去开启") }
                    }
                }
            }
            OutlinedButton(onClick = onRecheck) { Text("重新检测") }
        }
    }
}

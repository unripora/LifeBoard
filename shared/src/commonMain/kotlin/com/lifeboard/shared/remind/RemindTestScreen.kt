package com.lifeboard.shared.remind

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.shared.platform.RemindBackend
import com.lifeboard.shared.platform.remindLevelName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提醒验证页状态（M2.7）。
 *
 * 提供最小的任务创建与提醒触发能力，用于验证三档提醒是否按预期工作。
 * 正式的任务管理在「面板 → 新建任务」里，这里只是自检工具。
 */
data class RemindTestUiState(
    val permissions: List<RemindBackend.PermissionItem> = emptyList(),
    val tasks: List<TaskConfig> = emptyList(),
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
)

/**
 * 提醒验证页状态机。
 *
 * 原实现在 `:app`，唯一的 Android 依赖是 `Context`（构造 `AndroidRemindBackend`），
 * 而 [RemindBackend] 早就是 commonMain 的契约，桌面端已实现一份
 * （`DesktopRemindBackend`）。所以这里改成由调用方注入后端，两端共用同一份逻辑。
 *
 * 不继承 `ViewModel`：`:shared` 没有 androidx.lifecycle，协程作用域由调用方传入。
 */
class RemindTestViewModel(
    private val backend: RemindBackend,
    private val coroutineScope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RemindTestUiState())
    val state: StateFlow<RemindTestUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        coroutineScope.launch {
            val perms = withContext(Dispatchers.Default) { backend.checkPermissions() }
            val tasks = withContext(Dispatchers.Default) { backend.listTasks() }
            _state.value = _state.value.copy(permissions = perms, tasks = tasks)
        }
    }

    /**
     * 创建一个测试任务。
     *
     * 故意把阈值设成 999，保证「永远不达成」，这样随时可以触发提醒而不必造数据。
     */
    fun createTestTask(level: RemindLevel) {
        coroutineScope.launch {
            val title = "测试·${remindLevelName(level)}"
            withContext(Dispatchers.Default) {
                val id = backend.createTask(
                    TaskConfig(
                        title = title,
                        source = builtinRef(SourceType.SCREEN_TIME),
                        metricBucket = MetricBucket.DURATION_MINUTES,
                        threshold = 999.0,
                        remindLevel = level,
                        windowStartMinute = 0,
                        windowEndMinute = 24 * 60 - 1,
                    ),
                )
                appendLog("已创建任务 #$id「$title」")
            }
            // 建完立刻注册闹钟，与正式的任务编辑链路保持一致
            withContext(Dispatchers.Default) { backend.rescheduleAll() }
            refresh()
            appendLog("闹钟已重排")
        }
    }

    /** 立刻对该任务执行一次判定 + 提醒，不等待闹钟 */
    fun triggerNow(taskId: Long) {
        coroutineScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.Default) {
                backend.evaluateAndRemind(taskId) ?: "任务 #$taskId 不存在"
            }
            appendLog(msg)
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    /** 删除全部测试任务，清理现场 */
    fun clearTestTasks() {
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                backend.listTasks()
                    .filter { it.title.startsWith("测试·") }
                    .forEach {
                        backend.deleteTask(it.id)
                        backend.cancelTask(it.id)
                    }
            }
            appendLog("已清理测试任务")
            refresh()
        }
    }

    private fun appendLog(line: String) {
        _state.value = _state.value.copy(
            log = (listOf(line) + _state.value.log).take(30),
        )
    }

    companion object {
        fun levelName(level: RemindLevel): String = remindLevelName(level)
    }
}

/**
 * 提醒验证页（M2.7）。
 *
 * 三块内容：权限自检 → 三档提醒一键测试 → 执行日志。
 *
 * @param onOpenPermission 用户点「去开启」时跳到系统设置。
 *   Android 走 `RemindPermissions.open`，桌面端没有对应系统页，
 *   传 null 时按钮会自动隐藏——不给用户一个点了没反应的按钮。
 */
@Composable
fun RemindTestScreen(
    backend: RemindBackend,
    modifier: Modifier = Modifier,
    onOpenPermission: ((String) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val vm = remember(backend, coroutineScope) {
        RemindTestViewModel(backend, coroutineScope)
    }
    val state by vm.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            items = state.permissions,
            onOpen = onOpenPermission,
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
    items: List<RemindBackend.PermissionItem>,
    onOpen: ((String) -> Unit)?,
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
                    // 没有跳转能力就不显示这个按钮：桌面上点「去开启」无处可去
                    if (!item.granted && onOpen != null) {
                        TextButton(onClick = { onOpen(item.key) }) { Text("去开启") }
                    }
                }
            }
            OutlinedButton(onClick = onRecheck) { Text("重新检测") }
        }
    }
}

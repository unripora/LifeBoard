package com.lifeboard.app.ui.remind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.shared.platform.RemindBackend
import com.lifeboard.shared.platform.remindLevelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提醒验证页状态（M2.7）。
 *
 * M3 才有真正的任务管理界面，这里先提供最小的任务创建与提醒触发能力，
 * 用于在实机上验证三档提醒是否按预期工作。
 */
data class RemindTestUiState(
    val permissions: List<RemindBackend.PermissionItem> = emptyList(),
    val tasks: List<TaskConfig> = emptyList(),
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
)

class RemindTestViewModel(
    private val backend: RemindBackend,
) : ViewModel() {

    private val _state = MutableStateFlow(RemindTestUiState())
    val state: StateFlow<RemindTestUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val perms = withContext(Dispatchers.IO) { backend.checkPermissions() }
            val tasks = withContext(Dispatchers.IO) { backend.listTasks() }
            _state.value = _state.value.copy(permissions = perms, tasks = tasks)
        }
    }

    /**
     * 创建一个测试任务。
     *
     * 故意把阈值设成 999，保证「永远不达成」，这样随时可以触发提醒而不必造数据。
     */
    fun createTestTask(level: RemindLevel) {
        viewModelScope.launch {
            val title = "测试·${remindLevelName(level)}"
            withContext(Dispatchers.IO) {
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
            // 建完立刻注册闹钟，与 M3 的任务编辑链路保持一致
            withContext(Dispatchers.IO) { backend.rescheduleAll() }
            refresh()
            appendLog("闹钟已重排")
        }
    }

    /** 立刻对该任务执行一次判定 + 提醒，不等待闹钟 */
    fun triggerNow(taskId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                backend.evaluateAndRemind(taskId) ?: "任务 #$taskId 不存在"
            }
            appendLog(msg)
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    /** 删除全部测试任务，清理现场 */
    fun clearTestTasks() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
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

package com.lifeboard.app.ui.remind

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.TaskEntity
import com.lifeboard.server.remind.ConditionEvaluator
import com.lifeboard.server.remind.RemindPermissions
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.remind.Reminder
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
    val permissions: List<RemindPermissions.Item> = emptyList(),
    val tasks: List<TaskConfig> = emptyList(),
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
)

class RemindTestViewModel(app: Application) : AndroidViewModel(app) {

    private val db get() = LifeBoardDatabase.get(getApplication())

    private val _state = MutableStateFlow(RemindTestUiState())
    val state: StateFlow<RemindTestUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val perms = withContext(Dispatchers.IO) {
                RemindPermissions.checkAll(getApplication())
            }
            val tasks = withContext(Dispatchers.IO) {
                db.taskDao().getEnabled().map { it.toUiTask() }
            }
            _state.value = _state.value.copy(permissions = perms, tasks = tasks)
        }
    }

    /**
     * 创建一个测试任务。
     *
     * 故意把阈值设成 999，保证"永远不达成"，这样随时可以触发提醒而不必造数据。
     */
    fun createTestTask(level: RemindLevel) {
        viewModelScope.launch {
            val title = when (level) {
                RemindLevel.NONE -> "测试·不提醒"
                RemindLevel.WEAK -> "测试·弱提醒"
                RemindLevel.STRONG -> "测试·强提醒"
            }
            withContext(Dispatchers.IO) {
                val id = db.taskDao().insert(
                    TaskEntity(
                        title = title,
                        sourceType = SourceType.SCREEN_TIME.name,
                        metricBucket = MetricBucket.DURATION_MINUTES.name,
                        threshold = 999.0,
                        weight = 1.0,
                        remindLevel = level.code,
                        windowStartMinute = 0,
                        windowEndMinute = 24 * 60 - 1,
                        enabled = true,
                    ),
                )
                appendLog("已创建任务 #$id「$title」")
            }
            // 建完立刻注册闹钟，upsert 链路与 M3 的任务编辑保持一致
            withContext(Dispatchers.IO) { RemindScheduler.rescheduleAll(getApplication()) }
            refresh()
            appendLog("闹钟已重排")
        }
    }

    /** 立刻对该任务执行一次判定 + 提醒，不等待闹钟 */
    fun triggerNow(taskId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                val evaluator = ConditionEvaluator(getApplication())
                val result = evaluator.evaluate(taskId, Dates.todayString())
                if (result == null) {
                    "任务 #$taskId 不存在"
                } else {
                    val actual = Reminder.dispatch(getApplication(), result)
                    Reminder.logDelivery(
                        getApplication(),
                        result,
                        actualLevel = actual,
                        delivered = true,
                    )
                    "任务 #$taskId 判定 value=${result.value} " +
                        "completed=${result.completed} → 实际提醒 ${levelName(actual)}"
                }
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
                val all = db.taskDao().getEnabled()
                all.filter { it.title.startsWith("测试·") }.forEach {
                    db.taskDao().deleteById(it.id)
                    RemindScheduler.cancelTask(getApplication(), it.id)
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
        fun levelName(level: RemindLevel): String = when (level) {
            RemindLevel.NONE -> "不提醒"
            RemindLevel.WEAK -> "弱提醒"
            RemindLevel.STRONG -> "强提醒"
        }
    }
}

/** Room 实体 → 领域模型（测试页不关心自建源，直接用内置枚举解析） */
private fun TaskEntity.toUiTask(): TaskConfig = TaskConfig(
    id = id,
    title = title,
    source = builtinRef(SourceType.fromKey(sourceType) ?: SourceType.MANUAL),
    metricBucket = MetricBucket.entries.firstOrNull { it.name == metricBucket }
        ?: MetricBucket.FLAG,
    threshold = threshold,
    weight = weight,
    remindLevel = RemindLevel.fromCode(remindLevel),
    windowStartMinute = windowStartMinute,
    windowEndMinute = windowEndMinute,
    enabled = enabled,
)

package com.lifeboard.app.ui.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.server.health.SourceHealthChecker
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.panel.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 面板上的一行任务：任务配置 + 今日记录 + 连击。
 */
data class TaskRow(
    val task: TaskConfig,
    val record: DailyRecord?,
    val streak: StreakInfo?,
    /** 关联规则的说明（如「每日 · 09:00–09:30」），未关联时为空 */
    val ruleSummary: String = "",
) {
    val completed: Boolean get() = record?.completed == true
    val value: Double get() = record?.value ?: 0.0

    /** 进度 0..1，阈值非法时按 0 处理，避免除零 */
    val progress: Float
        get() = if (task.threshold <= 0.0) 0f
        else (value / task.threshold).toFloat().coerceIn(0f, 1f)

    val currentStreak: Int get() = streak?.currentStreak ?: 0
    val bestStreak: Int get() = streak?.bestStreak ?: 0
}

/**
 * 首页仪表盘状态。
 *
 * [weightedScore] 是"今日已得权重分"，[totalWeight] 是"今日所有启用任务的总权重"，
 * 两者相除即完成度。分开存是为了让界面既能显示 x/y 分，也能显示百分比。
 */
data class PanelUiState(
    val date: String = Dates.todayString(),
    val loading: Boolean = true,
    val rows: List<TaskRow> = emptyList(),
    val weightedScore: Double = 0.0,
    val totalWeight: Double = 0.0,
    val weekScores: List<Pair<LocalDate, Double>> = emptyList(),
    /** 有问题的数据源（缺权限 / 二期未接入），空列表表示全部正常 */
    val brokenSources: List<SourceHealthChecker.Entry> = emptyList(),
    val message: String = "",
) {
    val completionRate: Float
        get() = if (totalWeight <= 0.0) 0f else (weightedScore / totalWeight).toFloat()

    val completedCount: Int get() = rows.count { it.completed }
    val activeRows: List<TaskRow> get() = rows.filter { it.task.enabled }
    val bestCurrentStreak: Int get() = rows.maxOfOrNull { it.currentStreak } ?: 0
}

/**
 * 首页仪表盘 ViewModel（M3.2 / M3.4）。
 *
 * 三个数据源（任务、今日记录、连击）用 combine 合成一个状态流，
 * 任何一处变化——包括后台 Worker 写入的记录——都会自动刷新界面，不需要手动轮询。
 */
class PanelViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaskRepository(app)

    private val _message = MutableStateFlow("")
    private val _weekScores = MutableStateFlow<List<Pair<LocalDate, Double>>>(emptyList())
    private val _refreshing = MutableStateFlow(true)
    private val _brokenSources = MutableStateFlow<List<SourceHealthChecker.Entry>>(emptyList())

    /** 规则 id → 生效摘要，任务行据此显示「规则：每日 · 09:00–09:30」 */
    private val _ruleSummaries = MutableStateFlow<Map<Long, String>>(emptyMap())

    private val today get() = Dates.todayString()

    val state: StateFlow<PanelUiState> = combine(
        repo.observeTasks(),
        repo.observeRecords(today),
        repo.observeStreaks(),
        _weekScores,
        _message,
        _refreshing,
        _brokenSources,
        _ruleSummaries,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tasks = values[0] as List<TaskConfig>
        @Suppress("UNCHECKED_CAST")
        val records = values[1] as Map<Long, DailyRecord>
        @Suppress("UNCHECKED_CAST")
        val streaks = values[2] as Map<Long, StreakInfo>
        @Suppress("UNCHECKED_CAST")
        val week = values[3] as List<Pair<LocalDate, Double>>
        val message = values[4] as String
        val refreshing = values[5] as Boolean
        @Suppress("UNCHECKED_CAST")
        val broken = values[6] as List<SourceHealthChecker.Entry>
        @Suppress("UNCHECKED_CAST")
        val summaries = values[7] as Map<Long, String>

        val rows = tasks.map { t ->
            TaskRow(
                task = t,
                record = records[t.id],
                streak = streaks[t.id],
                ruleSummary = summaries[t.ruleId] ?: "",
            )
        }
        val active = rows.filter { it.task.enabled }
        PanelUiState(
            loading = refreshing,
            rows = rows,
            weightedScore = active.sumOf { it.record?.score ?: 0.0 },
            totalWeight = active.sumOf { it.task.weight },
            weekScores = week,
            brokenSources = broken,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PanelUiState(),
    )

    init {
        reloadWeek()
        reloadHealth()
        reloadRuleSummaries()
        // 首次进入先跑一次判定，保证刚从后台采集到的指标立刻反映成达成状态
        reevaluate(silent = true)
    }

    /** 读取规则摘要，让任务行能显示"关联的是哪条规则、什么时候生效" */
    private fun reloadRuleSummaries() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    LifeBoardDatabase.get(getApplication()).captureRuleDao().getAll()
                        .associate { it.id to it.toRuleSchedule().summary() }
                }.getOrDefault(emptyMap())
            }
            _ruleSummaries.value = map
        }
    }

    /** 打开某个断源对应的设置页 */
    fun openSourceSettings(entry: SourceHealthChecker.Entry) {
        val key = entry.actionKey ?: return
        SourceHealthChecker.openSettings(getApplication(), key)
    }

    /** 重新判定今日全部任务并刷新本周得分 */
    fun reevaluate(silent: Boolean = false) {
        viewModelScope.launch {
            _refreshing.value = true
            val count = withContext(Dispatchers.IO) {
                runCatching { repo.reevaluateAll(today) }.getOrDefault(-1)
            }
            reloadWeekSuspend()
            reloadHealth()
            _refreshing.value = false
            if (!silent) {
                _message.value = if (count >= 0) "已重新判定 $count 个任务" else "判定失败"
            }
        }
    }

    /** 手动打卡：把当日记录标记为达成，采集源已有数据时不覆盖 */
    fun markDone(row: TaskRow) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { repo.manualMark(row.task, today) }.getOrDefault(false)
            }
            _message.value = if (ok) {
                "「${row.task.title}」已记为完成"
            } else {
                "今天已采集到真实数据，请以自动判定为准"
            }
            reloadWeekSuspend()
        }
    }

    /** 删除任务 */
    fun deleteTask(row: TaskRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteTask(row.task.id) }
            _message.value = "已删除「${row.task.title}」"
            reloadWeekSuspend()
        }
    }

    /** 启用/停用任务 */
    fun toggleEnabled(row: TaskRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.setEnabled(row.task, !row.task.enabled)
            }
            _message.value =
                if (row.task.enabled) "「${row.task.title}」已停用" else "「${row.task.title}」已启用"
        }
    }

    fun consumeMessage() {
        _message.value = ""
    }

    private fun reloadWeek() {
        viewModelScope.launch { reloadWeekSuspend() }
    }

    /**
     * 刷新数据源健康度，只保留需要用户处理的两类：
     * 缺权限（可以去设置里补）和二期未接入（无法处理，但要让人知道为什么是 0）。
     */
    private fun reloadHealth() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { SourceHealthChecker.checkAll(getApplication()) }.getOrDefault(emptyList())
            }
            _brokenSources.value = entries.filter {
                it.state == SourceHealthChecker.State.NO_PERMISSION ||
                    it.state == SourceHealthChecker.State.NOT_IMPLEMENTED
            }
        }
    }

    private suspend fun reloadWeekSuspend() {
        val todayDate = Dates.today()
        val scores = withContext(Dispatchers.IO) {
            repo.dailyScores(Dates.weekStart(todayDate), todayDate)
        }
        _weekScores.value = scores
    }

    companion object {
        /** 分钟数转 HH:mm，用于时段展示 */
        fun formatMinute(minuteOfDay: Int): String =
            "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

        fun levelName(level: RemindLevel): String = when (level) {
            RemindLevel.NONE -> "不提醒"
            RemindLevel.WEAK -> "弱提醒"
            RemindLevel.STRONG -> "强提醒"
        }

        /** 指标值格式化：整数不带小数，小数保留一位 */
        fun formatValue(value: Double, unit: String): String {
            val number = if (value == value.toInt().toDouble()) {
                value.toInt().toString()
            } else {
                "%.1f".format(value)
            }
            return if (unit.isEmpty()) number else "$number $unit"
        }
    }
}

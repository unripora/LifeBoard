package com.lifeboard.app.ui.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.server.panel.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 趋势页的时间跨度 */
enum class TrendRange(val label: String, val days: Int) {
    WEEK("本周", 7),
    MONTH("本月", 30),
}

/** 单个任务的近 N 天表现 */
data class TaskTrend(
    val task: TaskConfig,
    val streak: StreakInfo?,
    val completedDays: Int,
    val totalDays: Int,
) {
    val rate: Float
        get() = if (totalDays <= 0) 0f else completedDays.toFloat() / totalDays

    val currentStreak: Int get() = streak?.currentStreak ?: 0
    val bestStreak: Int get() = streak?.bestStreak ?: 0
}

data class TrendUiState(
    val range: TrendRange = TrendRange.WEEK,
    val loading: Boolean = true,
    val dailyScores: List<Pair<LocalDate, Double>> = emptyList(),
    val dailyCompletion: List<Pair<LocalDate, Double>> = emptyList(),
    val tasks: List<TaskTrend> = emptyList(),
) {
    val totalScore: Double get() = dailyScores.sumOf { it.second }
    val averageScore: Double
        get() = if (dailyScores.isEmpty()) 0.0 else totalScore / dailyScores.size
    val averageCompletion: Double
        get() = if (dailyCompletion.isEmpty()) 0.0 else dailyCompletion.map { it.second }.average()
    val perfectDays: Int get() = dailyCompletion.count { it.second >= 0.999 }
}

/**
 * 趋势页 ViewModel（M3.5）。
 *
 * 只读不写。跨度切换时重新按区间查库，不缓存——数据量最多 30 行，
 * 缓存反而会带来"改了任务但图表没更新"的困惑。
 */
class TrendViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaskRepository(app)

    private val _state = MutableStateFlow(TrendUiState())
    val state: StateFlow<TrendUiState> = _state.asStateFlow()

    init {
        load(TrendRange.WEEK)
    }

    fun selectRange(range: TrendRange) {
        if (_state.value.range == range && !_state.value.loading) return
        load(range)
    }

    private fun load(range: TrendRange) {
        viewModelScope.launch {
            _state.value = _state.value.copy(range = range, loading = true)
            val today = Dates.today()
            // 本周按周一起算，"近 7 天"会让周视图在周一显示上周末的数据，不符合直觉
            val start = if (range == TrendRange.WEEK) Dates.weekStart(today) else today.minusDays(29)
            val span = range.days

            val scores = withContext(Dispatchers.IO) { repo.dailyScores(start, today) }
            val completion = withContext(Dispatchers.IO) { repo.dailyCompletion(start, today) }
            val tasks = withContext(Dispatchers.IO) {
                val all = repo.getTasksOnce()
                val streaks = repo.getStreaksOnce()
                all.map { task ->
                    val history = repo.taskHistory(task.id, span)
                    TaskTrend(
                        task = task,
                        streak = streaks[task.id],
                        completedDays = history.count { it.second },
                        totalDays = history.size,
                    )
                }.sortedByDescending { it.rate }
            }

            _state.value = TrendUiState(
                range = range,
                loading = false,
                dailyScores = scores,
                dailyCompletion = completion,
                tasks = tasks,
            )
        }
    }
}

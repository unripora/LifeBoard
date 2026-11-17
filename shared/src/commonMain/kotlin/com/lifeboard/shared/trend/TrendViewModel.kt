package com.lifeboard.shared.trend

import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.panel.TaskRepository
import kotlinx.coroutines.CoroutineScope
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

/** 趋势页状态 */
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
 * 趋势页状态机。
 *
 * 只读不写。跨度切换时重新按区间查库，不缓存——数据量最多 30 行，
 * 缓存反而会带来「改了任务但图表没更新」的困惑。
 *
 * ## 为什么下沉到 `:shared`
 *
 * 原实现唯一的 Android 依赖是 `Context`（用来构造 `AndroidPanelScope`），
 * 而 `TaskRepository` 本身早就在 commonMain。换成 [PanelScope] 之后
 * 桌面端可以直接复用，不必另写一份统计逻辑——两份算出来的达成率
 * 一旦不一致，用户会先怀疑数据，而不是怀疑界面。
 *
 * 不继承 `ViewModel`（`:shared` 没有 androidx.lifecycle），
 * 协程作用域由调用方传入。
 */
class TrendViewModel(
    private val scope: PanelScope,
    private val coroutineScope: CoroutineScope,
) {

    private val repo = TaskRepository(scope)

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
        coroutineScope.launch {
            _state.value = _state.value.copy(range = range, loading = true)
            val today = Dates.today()
            // 本周按周一起算：「近 7 天」会让周视图在周一显示上周末的数据，不符合直觉
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

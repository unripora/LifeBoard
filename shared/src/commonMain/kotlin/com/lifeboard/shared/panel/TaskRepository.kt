package com.lifeboard.shared.panel

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 面板数据仓库（M3.1）。
 *
 * 面板、任务编辑、趋势图三处共用这一层，避免各自直接查数据导致口径不一致。
 * 所有写操作后都通过 [PanelScope.onTaskSaved] / [PanelScope.onTaskDeleted]
 * 通知平台侧同步提醒闹钟——任务改了时段/档位/启用状态，闹钟必须跟着变，
 * 这个联动放在仓库里，UI 侧就不可能忘记调用。
 *
 * ## 为什么住在 commonMain
 *
 * 除了「排闹钟」这一步，这里的每个方法都只是对 [com.lifeboard.shared.repository.TaskStore]
 * 的编排。把排闹钟这一步抽成端口以后，手机与电脑共用同一份「打卡口径」：
 * 什么算完成、手动打卡什么时候不允许、周得分怎么补零，两端不会各写一套。
 */
class TaskRepository(
    private val scope: PanelScope,
) {

    private val store get() = scope.taskStore

    /**
     * 判定器。
     *
     * 用完即弃而不是持有：它本身无状态，只包了两个 Store，
     * 而重新 new 的成本可以忽略，省得为它的生命周期操心。
     */
    private fun evaluator() = ConditionEvaluator(scope.taskStore, scope.metricStore)

    /** 观察全部任务（含已停用），用于任务管理列表 */
    fun observeTasks(): Flow<List<TaskConfig>> = store.observeAll()

    /** 观察某日全部任务的完成记录，日期 → (taskId → 记录) */
    fun observeRecords(date: String): Flow<Map<Long, DailyRecord>> =
        store.observeRecordsByDate(date).map { list ->
            list.associateBy { it.taskId }
        }

    /** 观察全部连击状态 */
    fun observeStreaks(): Flow<Map<Long, StreakInfo>> =
        store.observeAllStreaks().map { list ->
            list.associateBy { it.taskId }
        }

    suspend fun getTask(id: Long): TaskConfig? = withContext(Dispatchers.IO) {
        store.getById(id)
    }

    /** 一次性读取全部任务（含停用），趋势页快照用 */
    suspend fun getTasksOnce(): List<TaskConfig> = withContext(Dispatchers.IO) {
        store.getAll()
    }

    /** 一次性读取全部连击状态，趋势页快照用 */
    suspend fun getStreaksOnce(): Map<Long, StreakInfo> = withContext(Dispatchers.IO) {
        store.getAllStreaks().associateBy { it.taskId }
    }

    /**
     * 新建或更新任务。
     *
     * @return 任务 id（新建时为自增主键）
     */
    suspend fun saveTask(task: TaskConfig): Long = withContext(Dispatchers.IO) {
        val id = store.save(task)
        scope.onTaskSaved(id)
        id
    }

    /** 删除任务，连带撤销闹钟、通知、当日记录与连击，避免留下孤儿数据 */
    suspend fun deleteTask(taskId: Long) = withContext(Dispatchers.IO) {
        // 记录与连击由 TaskStore 在同一事务里一起清，避免中间态被趋势页读到
        store.deleteById(taskId)
        scope.onTaskDeleted(taskId)
    }

    /** 启用/停用任务，停用后不再参与判定与提醒 */
    suspend fun setEnabled(task: TaskConfig, enabled: Boolean) = withContext(Dispatchers.IO) {
        val updated = task.copy(enabled = enabled)
        store.save(updated)
        scope.onTaskSaved(updated.id)
    }

    /**
     * 手动打卡。
     *
     * 只在当日没有任何采集值时才补一条记录——采集源已有数据时，达成与否应由
     * [ConditionEvaluator] 按真实指标判定，人工覆盖会把"没做到的"记成"做到了"。
     * 补的记录不写 `metric` 表，指标表保持"只由采集器写入"，否则趋势图会把
     * 人工补账当成真实数据。
     *
     * @return true 表示记录已写入；false 表示当日已有采集数据，未做任何改动
     */
    suspend fun manualMark(task: TaskConfig, date: String = Dates.todayString()): Boolean =
        withContext(Dispatchers.IO) {
            val existing = scope.metricStore
                .getMetric(task.sourceKey, date, task.metricBucket.name)
            if (existing != null && existing.value > 0.0) return@withContext false

            store.saveManualRecord(
                DailyRecord(
                    taskId = task.id,
                    date = date,
                    value = task.threshold,
                    completed = true,
                    score = task.weight,
                ),
            )
            // 只维护连击，不重算记录：上面写的这条就是最终结果
            evaluator().refreshStreakFromRecord(task.id, date)
            true
        }

    /**
     * 重新判定某日全部启用任务，用于面板下拉刷新。
     * 走 [ConditionEvaluator] 而不是自己算，保证与提醒链路同一套规则。
     */
    suspend fun reevaluateAll(date: String = Dates.todayString()): Int =
        evaluator().evaluateAll(date, persist = true).size

    /**
     * 读取指定区间每天的加权得分，缺失日期补 0。
     *
     * 只返回绝对值，不返回比率：历史上停用的任务也曾计分，
     * 用"当前启用任务的总权重"当分母会把过去的分数算成超额，比率交给调用方决定口径。
     */
    suspend fun dailyScores(from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> =
        withContext(Dispatchers.IO) {
            val rows = store.getRecordsInRange(from.toString(), to.toString())
            val byDate = rows.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.score } }
            val days = ChronoUnit.DAYS.between(from, to).toInt()
            (0..days).map { offset ->
                val d = from.plusDays(offset.toLong())
                d to (byDate[d.toString()] ?: 0.0)
            }
        }

    /** 读取指定区间的完成率（达成数 / 记录数），用于趋势页 */
    suspend fun dailyCompletion(from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> =
        withContext(Dispatchers.IO) {
            val rows = store.getRecordsInRange(from.toString(), to.toString())
            val byDate = rows.groupBy { it.date }.mapValues { (_, v) ->
                if (v.isEmpty()) 0.0 else v.count { it.completed }.toDouble() / v.size
            }
            val days = ChronoUnit.DAYS.between(from, to).toInt()
            (0..days).map { offset ->
                val d = from.plusDays(offset.toLong())
                d to (byDate[d.toString()] ?: 0.0)
            }
        }

    /** 某任务近 N 天的达成序列，缺失天按未达成处理 */
    suspend fun taskHistory(taskId: Long, days: Int): List<Pair<LocalDate, Boolean>> =
        withContext(Dispatchers.IO) {
            val today = Dates.today()
            val start = today.minusDays(days - 1L)
            val rows = store.getRecordsInRange(start.toString(), today.toString())
                .filter { it.taskId == taskId }
                .associate { it.date to it.completed }
            (0 until days).map { offset ->
                val d = start.plusDays(offset.toLong())
                d to (rows[d.toString()] ?: false)
            }
        }
}

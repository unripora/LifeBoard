package com.lifeboard.server.remind

import android.content.Context
import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.DailyRecordEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.StreakEntity
import com.lifeboard.server.db.TaskEntity
import com.lifeboard.server.panel.toConfigWithRule
import com.lifeboard.server.panel.toScheduleRule
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 条件判定器（M2.1）。
 *
 * 职责：把「任务配置 + 当日采集到的指标」判定成「是否达成」，并落库 `daily_record` 与 `streak`。
 * 这是提醒链路的唯一判定入口，提醒调度器只消费判定结果，不自己重算，避免两处逻辑漂移。
 *
 * 达成语义统一为 `value >= threshold`（见 [TaskConfig.threshold]）。
 */
class ConditionEvaluator(private val context: Context) {

    private val db get() = LifeBoardDatabase.get(context)

    /**
     * 判定单个任务在指定日期的达成状态。
     *
     * @param persist true 时把结果写入 daily_record 并维护 streak；判定预览可传 false
     * @return 判定结果，任务不存在时返回 null
     */
    suspend fun evaluate(taskId: Long, date: String, persist: Boolean = true): Evaluation? {
        val entity = withContext(Dispatchers.IO) { db.taskDao().getById(taskId) } ?: return null
        val source = withContext(Dispatchers.IO) { SourceRegistry.resolve(context, entity.sourceType) }
        val task = entity.toConfigWithRule(context, source)
        return evaluate(task, date, persist)
    }

    suspend fun evaluate(task: TaskConfig, date: String, persist: Boolean = true): Evaluation {
        val value = readMetricValue(task, date)
        val completed = task.threshold > 0 && value >= task.threshold
        // 权重 * 达成系数：达成记满权重，未达成记 0。后续若要做部分达成，只改这里。
        val score = if (completed) task.weight else 0.0

        if (persist) {
            withContext(Dispatchers.IO) {
                db.dailyRecordDao().upsert(
                    DailyRecordEntity(
                        taskId = task.id,
                        date = date,
                        value = value,
                        completed = completed,
                        score = score,
                    ),
                )
                updateStreak(task.id, date, completed)
            }
        }

        return Evaluation(
            task = task,
            date = date,
            value = value,
            completed = completed,
            score = score,
        )
    }

    /**
     * 批量判定当日所有启用任务。
     * 供日切任务与提醒调度器使用。
     */
    suspend fun evaluateAll(date: String = Dates.todayString(), persist: Boolean = true): List<Evaluation> {
        val tasks = withContext(Dispatchers.IO) {
            val rows = db.taskDao().getEnabled()
            val sources = SourceRegistry.resolveAll(context, rows.map { it.sourceType })
            rows.map { it.toConfigWithRule(context, sources[it.sourceType] ?: SourceRef.Builtin(SourceType.MANUAL)) }
        }
        return tasks.map { evaluate(it, date, persist) }
    }

    /** 读取任务对应数据源在当日已采集到的值；无数据按 0 处理 */
    private suspend fun readMetricValue(task: TaskConfig, date: String): Double =
        withContext(Dispatchers.IO) {
            db.metricDao().getOne(task.sourceKey, date, task.metricBucket.name)?.value ?: 0.0
        }

    /**
     * 维护连续打卡。
     *
     * 规则：
     * - 达成且上次达成日期是昨天 → current + 1
     * - 达成且上次就是今天 → 不变（重复判定不能刷连击）
     * - 达成但中间断了 → 重置为 1
     * - 未达成 → 回到 0
     * - best 只增不减
     */
    private suspend fun updateStreak(taskId: Long, date: String, completed: Boolean) {
        val dao = db.streakDao()
        val existing = dao.get(taskId)
        val yesterday = LocalDate.parse(date).minusDays(1).toString()

        val newCurrent = when {
            !completed -> 0
            existing == null -> 1
            existing.lastDate == date -> existing.currentStreak
            existing.lastDate == yesterday -> existing.currentStreak + 1
            else -> 1
        }
        val newBest = maxOf(existing?.bestStreak ?: 0, newCurrent)
        // 未达成时保留上次达成日期，避免丢失"上次成功是哪天"的信息
        val newLastDate = if (completed) date else existing?.lastDate

        dao.upsert(
            StreakEntity(
                taskId = taskId,
                currentStreak = newCurrent,
                bestStreak = newBest,
                lastDate = newLastDate,
            ),
        )
    }

    /** 读取某任务在指定日期的判定结果（不重新计算，直接读库） */
    suspend fun loadRecord(taskId: Long, date: String): DailyRecord? =
        withContext(Dispatchers.IO) {
            db.dailyRecordDao().getOne(taskId, date)?.let {
                DailyRecord(
                    taskId = it.taskId,
                    date = it.date,
                    value = it.value,
                    completed = it.completed,
                    score = it.score,
                )
            }
        }

    /**
     * 只按已有记录维护连击，不重算也不改写记录本身。
     *
     * 供手动打卡使用：那条记录是用户明确声明的结果，不该被指标值覆盖回去。
     */
    suspend fun refreshStreakFromRecord(taskId: Long, date: String) {
        val record = withContext(Dispatchers.IO) { db.dailyRecordDao().getOne(taskId, date) }
            ?: return
        withContext(Dispatchers.IO) { updateStreak(taskId, date, record.completed) }
    }
}

/**
 * 单个任务的一次判定结果。
 */
data class Evaluation(
    val task: TaskConfig,
    val date: String,
    val value: Double,
    val completed: Boolean,
    val score: Double,
) {
    /** 是否需要提醒：未达成且档位不是「不提醒」 */
    val shouldRemind: Boolean
        get() = !completed && task.remindLevel != RemindLevel.NONE
}


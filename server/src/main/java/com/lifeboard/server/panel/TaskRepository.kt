package com.lifeboard.server.panel

import android.content.Context
import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.DailyRecordEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.StreakEntity
import com.lifeboard.server.db.TaskEntity
import com.lifeboard.server.remind.ConditionEvaluator
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.remind.WeakNotifier
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 面板数据仓库（M3.1）。
 *
 * 面板、任务编辑、趋势图三处共用这一层，避免各自直接拼 DAO 查询导致口径不一致。
 * 所有写操作后都负责同步提醒闹钟——任务改了时段/档位/启用状态，闹钟必须跟着变，
 * 这个联动放在仓库里，UI 侧就不可能忘记调用。
 */
class TaskRepository(private val context: Context) {

    private val db get() = LifeBoardDatabase.get(context)

    /** 观察全部任务（含已停用），用于任务管理列表 */
    fun observeTasks(): Flow<List<TaskConfig>> =
        db.taskDao().observeAll().map { list ->
            val sources = resolveSourceMap(list.map { it.sourceType }.distinct())
            list.map { it.toConfigWithRule(context, sources[it.sourceType] ?: fallbackSource) }
        }
    /** 观察某日全部任务的完成记录，日期 → (taskId → 记录) */
    fun observeRecords(date: String): Flow<Map<Long, DailyRecord>> =
        db.dailyRecordDao().observeByDate(date).map { list ->
            list.associate { it.taskId to it.toModel() }
        }

    /** 观察全部连击状态 */
    fun observeStreaks(): Flow<Map<Long, StreakInfo>> =
        db.streakDao().observeAll().map { list ->
            list.associate { it.taskId to it.toModel() }
        }

    suspend fun getTask(id: Long): TaskConfig? = withContext(Dispatchers.IO) {
        db.taskDao().getById(id)?.let {
            it.toConfigWithRule(context, SourceRegistry.resolve(context, it.sourceType))
        }
    }

    /** 一次性读取全部任务（含停用），趋势页快照用 */
    suspend fun getTasksOnce(): List<TaskConfig> = withContext(Dispatchers.IO) {
        val rows = db.taskDao().getAll()
        val sources = resolveSourceMap(rows.map { it.sourceType }.distinct())
        rows.map { it.toConfigWithRule(context, sources[it.sourceType] ?: fallbackSource) }
    }

    /** 一次性读取全部连击状态，趋势页快照用 */
    suspend fun getStreaksOnce(): Map<Long, StreakInfo> = withContext(Dispatchers.IO) {
        db.streakDao().getAll().associate { it.taskId to it.toModel() }
    }

    /**
     * 新建或更新任务。
     *
     * @return 任务 id（新建时为自增主键）
     */
    suspend fun saveTask(task: TaskConfig): Long = withContext(Dispatchers.IO) {
        if (task.id == 0L) {
            val id = db.taskDao().insert(task.toEntity())
            syncAlarm(id, task)
            id
        } else {
            db.taskDao().upsert(task.toEntity())
            syncAlarm(task.id, task)
            task.id
        }
    }

    /** 删除任务，连带撤销闹钟、通知、当日记录与连击，避免留下孤儿数据 */
    suspend fun deleteTask(taskId: Long) = withContext(Dispatchers.IO) {
        db.taskDao().deleteById(taskId)
        db.dailyRecordDao().deleteByTask(taskId)
        db.streakDao().deleteByTask(taskId)
        RemindScheduler.cancelTask(context, taskId)
        WeakNotifier.cancel(context, taskId)
    }

    /** 启用/停用任务，停用后不再参与判定与提醒 */
    suspend fun setEnabled(task: TaskConfig, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.taskDao().upsert(task.copy(enabled = enabled).toEntity())
        syncAlarm(task.id, task.copy(enabled = enabled))
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
            val existing = db.metricDao()
                .getOne(task.sourceKey, date, task.metricBucket.name)
            if (existing != null && existing.value > 0.0) return@withContext false

            db.dailyRecordDao().upsert(
                DailyRecordEntity(
                    taskId = task.id,
                    date = date,
                    value = task.threshold,
                    completed = true,
                    score = task.weight,
                ),
            )
            // 只维护连击，不重算记录：上面写的这条就是最终结果
            ConditionEvaluator(context).refreshStreakFromRecord(task.id, date)
            true
        }

    /**
     * 重新判定某日全部启用任务，用于面板下拉刷新。
     * 走 [ConditionEvaluator] 而不是自己算，保证与提醒链路同一套规则。
     */
    suspend fun reevaluateAll(date: String = Dates.todayString()): Int =
        ConditionEvaluator(context).evaluateAll(date, persist = true).size

    private fun syncAlarm(taskId: Long, task: TaskConfig) {
        // 关联了规则时，提醒时刻由规则推导；否则用任务自身的时段
        val rule = if (task.ruleId != 0L) {
            runBlocking { db.captureRuleDao().getById(task.ruleId) }
        } else {
            null
        }
        val schedule = rule?.toRuleSchedule() ?: task.schedule
        RemindScheduler.scheduleTask(
            context,
            taskId,
            schedule,
            task.enabled,
            task.windowOffsetMinutes,
        )
    }

    /** 兜底源：解析失败时用，语义是"只记录不采集" */
    private val fallbackSource: SourceRef get() = SourceRef.Builtin(SourceType.MANUAL)

    /** 批量解析，避免每个任务各查一次自建源表 */
    private suspend fun resolveSourceMap(keys: List<String>): Map<String, SourceRef> =
        SourceRegistry.resolveAll(context, keys)

    /**
     * 读取指定区间每天的加权得分，缺失日期补 0。
     *
     * 只返回绝对值，不返回比率：历史上停用的任务也曾计分，
     * 用"当前启用任务的总权重"当分母会把过去的分数算成超额，比率交给调用方决定口径。
     */
    suspend fun dailyScores(from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> =
        withContext(Dispatchers.IO) {
            val rows = db.dailyRecordDao().getRange(from.toString(), to.toString())
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
            val rows = db.dailyRecordDao().getRange(from.toString(), to.toString())
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
            val rows = db.dailyRecordDao().getRange(start.toString(), today.toString())
                .filter { it.taskId == taskId }
                .associate { it.date to it.completed }
            (0 until days).map { offset ->
                val d = start.plusDays(offset.toLong())
                d to (rows[d.toString()] ?: false)
            }
        }
}

/** Room 实体 → 领域模型。[ruleBucket] 非空时覆盖任务量纲 */
fun TaskEntity.toConfig(source: SourceRef, ruleBucket: String? = null): TaskConfig = TaskConfig(
    id = id,
    title = title,
    source = source,
    metricBucket = ruleBucket?.let { rb -> MetricBucket.entries.firstOrNull { it.name == rb } }
        ?: MetricBucket.entries.firstOrNull { it.name == metricBucket }
        ?: MetricBucket.FLAG,
    threshold = threshold,
    weight = weight,
    remindLevel = RemindLevel.fromCode(remindLevel),
    windowStartMinute = windowStartMinute,
    windowEndMinute = windowEndMinute,
    schedule = toScheduleRule(),
    ruleId = ruleId,
    enabled = enabled,
    timeSource = TimeSource.fromName(timeSource),
    doneRemindLevel = RemindLevel.fromCode(doneRemindLevel),
    doneToastEnabled = doneToastEnabled,
    windowOffsetMinutes = windowOffsetMinutes,
)

/**
 * 关联了规则的任务：量纲以规则为准。
 *
 * 量纲（完成/计数）只有规则一个事实来源——规则命中写到哪个桶，任务就从哪个桶读。
 * 任务表里存的 metricBucket 只在未关联规则时生效；这样旧任务即使配歪了
 * （规则写 FLAG、任务存 COUNT）也能自动纠正，不用用户进编辑页重存。
 */
suspend fun TaskEntity.toConfigWithRule(context: Context, source: SourceRef): TaskConfig {
    if (ruleId == 0L) return toConfig(source)
    val rule = LifeBoardDatabase.get(context).captureRuleDao().getById(ruleId)
    return toConfig(source, rule?.bucketName)
}

/** 实体里的时段字段 → 生效规则 */
fun TaskEntity.toScheduleRule(): ScheduleRule {
    val decoded = ScheduleRule.decodeWindows(windows)
    return ScheduleRule(
        // 老数据没有 windows 字段，回落到旧的单时段，行为保持不变
        windows = decoded.ifEmpty {
            listOf(TimeWindow(windowStartMinute, windowEndMinute.coerceAtLeast(windowStartMinute)))
        },
        startDate = startDate,
        endDate = endDate,
        repeatType = RepeatType.fromName(repeatType),
        weekdays = ScheduleRule.decodeWeekdays(weekdayMask),
        anchorDay = anchorDay,
        anchorMonth = anchorMonth,
    )
}

/** 领域模型 → Room 实体 */
fun TaskConfig.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    sourceType = sourceKey,
    metricBucket = metricBucket.name,
    threshold = threshold,
    weight = weight,
    remindLevel = remindLevel.code,
    windowStartMinute = windowStartMinute,
    windowEndMinute = windowEndMinute,
    enabled = enabled,
    windows = ScheduleRule.encodeWindows(schedule.windows),
    startDate = schedule.startDate,
    endDate = schedule.endDate,
    repeatType = schedule.repeatType.name,
    weekdayMask = ScheduleRule.encodeWeekdays(schedule.weekdays),
    anchorDay = schedule.anchorDay,
    anchorMonth = schedule.anchorMonth,
    ruleId = ruleId,
    timeSource = timeSource.name,
    doneRemindLevel = doneRemindLevel.code,
    doneToastEnabled = doneToastEnabled,
    windowOffsetMinutes = windowOffsetMinutes,
)

fun DailyRecordEntity.toModel(): DailyRecord = DailyRecord(
    taskId = taskId,
    date = date,
    value = value,
    completed = completed,
    score = score,
)

fun StreakEntity.toModel(): StreakInfo = StreakInfo(
    taskId = taskId,
    currentStreak = currentStreak,
    bestStreak = bestStreak,
    lastDate = lastDate,
)

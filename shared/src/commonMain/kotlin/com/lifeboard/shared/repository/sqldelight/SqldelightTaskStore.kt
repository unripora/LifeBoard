package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.TaskQueries
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.TaskStore
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [TaskStore] 的 SQLDelight 实现。
 *
 * 与 Room 版**逐方法等价**，包括几个容易漏的语义：
 * - `save` 用 `INSERT OR REPLACE`（对应 Room 的 `onConflict = REPLACE`），
 *   传已存在的 id 时整行替换、未赋值的列回到 SQL 默认值
 * - `deleteById` 的顺序是「先删任务，再清记录与连击」，反过来会让
 *   「任务已不在但记录还在」的中间态被趋势页读到
 *
 * @param lookup 解析自建源用。桌面端与 Android 端注入同一份实现即可
 */
class SqldelightTaskStore(
    db: LifeBoardDb,
    private val lookup: SourceKeys.CustomSourceLookup,
) : TaskStore {

    private val q: TaskQueries = db.taskQueries

    override fun observeAll(): Flow<List<TaskConfig>> =
        q.observeAllTasks().asListFlow()
            .map { rows -> rows.map { it.toModel(lookup) } }

    override suspend fun getAll(): List<TaskConfig> =
        q.selectAllTasks().executeAsList().map { it.toModel(lookup) }

    override suspend fun getEnabled(): List<TaskConfig> =
        q.selectEnabledTasks().executeAsList().map { it.toModel(lookup) }

    override suspend fun getById(id: Long): TaskConfig? =
        q.selectTaskById(id).executeAsOneOrNull()?.toModel(lookup)

    override suspend fun countByRule(ruleId: Long): Int =
        q.countTasksByRule(ruleId).executeAsOne().toInt()

    override suspend fun save(task: TaskConfig): Long {
        val row = SqldelightMappers.run { task.toRow() }
        return if (row.id == 0L) {
            // 新增：省略 id 列，让 AUTOINCREMENT 分配。
            // 显式传 0 会被真的存成 0，且 last_insert_rowid() 也返回 0。
            // insert 的返回值就是新行 id
            q.insertTaskAuto(
                title = row.title,
                sourceType = row.sourceType,
                metricBucket = row.metricBucket,
                threshold = row.threshold,
                weight = row.weight,
                remindLevel = row.remindLevel,
                windowStartMinute = row.windowStartMinute,
                windowEndMinute = row.windowEndMinute,
                enabled = row.enabled,
                windows = row.windows,
                startDate = row.startDate,
                endDate = row.endDate,
                repeatType = row.repeatType,
                weekdayMask = row.weekdayMask,
                anchorDay = row.anchorDay,
                anchorMonth = row.anchorMonth,
                ruleId = row.ruleId,
                timeSource = row.timeSource,
                doneRemindLevel = row.doneRemindLevel,
                doneToastEnabled = row.doneToastEnabled,
                windowOffsetMinutes = row.windowOffsetMinutes,
            ).await()
        } else {
            q.insertTask(
                id = row.id,
                title = row.title,
                sourceType = row.sourceType,
                metricBucket = row.metricBucket,
                threshold = row.threshold,
                weight = row.weight,
                remindLevel = row.remindLevel,
                windowStartMinute = row.windowStartMinute,
                windowEndMinute = row.windowEndMinute,
                enabled = row.enabled,
                windows = row.windows,
                startDate = row.startDate,
                endDate = row.endDate,
                repeatType = row.repeatType,
                weekdayMask = row.weekdayMask,
                anchorDay = row.anchorDay,
                anchorMonth = row.anchorMonth,
                ruleId = row.ruleId,
                timeSource = row.timeSource,
                doneRemindLevel = row.doneRemindLevel,
                doneToastEnabled = row.doneToastEnabled,
                windowOffsetMinutes = row.windowOffsetMinutes,
            )
            row.id
        }
    }

    override suspend fun deleteById(id: Long) {
        q.transaction {
            q.deleteTaskById(id)
            q.deleteRecordsByTask(id)
            q.deleteStreakByTask(id)
        }
    }

    override fun observeRecordsByDate(date: String): Flow<List<DailyRecord>> =
        q.observeRecordsByDate(date).asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getRecordsByDate(date: String): List<DailyRecord> =
        q.selectRecordsByDate(date).executeAsList().map { it.toModel() }

    override suspend fun getRecord(taskId: Long, date: String): DailyRecord? =
        q.selectRecord(taskId, date).executeAsOneOrNull()?.toModel()

    override suspend fun getRecordsInRange(from: String, to: String): List<DailyRecord> =
        q.selectRecordsInRange(from, to).executeAsList().map { it.toModel() }

    override suspend fun countRecordsByOrigin(
        from: String,
        to: String,
    ): Map<String, Map<String, Int>> =
        q.countRecordsByOrigin(from, to).executeAsList()
            .groupBy({ it.date }, { it.origin to it.total.toInt() })
            .mapValues { (_, pairs) -> pairs.toMap() }

    override suspend fun saveRecord(record: DailyRecord) {
        writeRecord(record, RECORD_ORIGIN_AUTO)
    }

    override suspend fun saveManualRecord(record: DailyRecord) {
        writeRecord(record, RECORD_ORIGIN_MANUAL)
    }

    private fun writeRecord(record: DailyRecord, origin: String) {
        val row = SqldelightMappers.run { record.toRow(origin = origin) }
        q.insertRecord(
            taskId = row.taskId,
            date = row.date,
            value_ = row.value_,
            completed = row.completed,
            score = row.score,
            origin = row.origin,
        )
    }

    override suspend fun getStreak(taskId: Long): StreakInfo? =
        q.selectStreak(taskId).executeAsOneOrNull()?.toModel()

    override fun observeAllStreaks(): Flow<List<StreakInfo>> =
        q.observeAllStreaks().asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getAllStreaks(): List<StreakInfo> =
        q.selectAllStreaks().executeAsList().map { it.toModel() }

    override suspend fun saveStreak(streak: StreakInfo) {
        val row = SqldelightMappers.run { streak.toRow() }
        q.insertStreak(
            taskId = row.taskId,
            currentStreak = row.currentStreak,
            bestStreak = row.bestStreak,
            lastDate = row.lastDate,
        )
    }

    private companion object {
        /**
         * 契约层的 [saveRecord] 只收 [DailyRecord]，没有 origin 维度。
         *
         * 明确写成 AUTO：本方法的调用者是采集与判定链路（由数据算出记录），
         * 手动打卡走的是带 origin 的另一条路径，不经过这里。
         * 若将来把手动打卡也接进来，必须先把 origin 提进契约，
         * 否则完成率统计会把人工干预算成自动达成。
         */
        const val RECORD_ORIGIN_AUTO = "AUTO"

        /** 用户在待办卡上手动勾选的记录，与自动判定区分开 */
        const val RECORD_ORIGIN_MANUAL = "MANUAL"
    }
}

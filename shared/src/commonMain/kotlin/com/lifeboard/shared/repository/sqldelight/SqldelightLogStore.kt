package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.LogQueries
import com.lifeboard.shared.repository.LogStore
import com.lifeboard.shared.repository.model.AppLogRecord
import com.lifeboard.shared.repository.model.NotificationLogRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import kotlinx.coroutines.flow.Flow

/**
 * [LogStore] 的 SQLDelight 实现。
 *
 * `clearPluginSnapshots` 要跨两张表清（原始条目 + 查询痕迹）：
 * 插件详情页的「最近一次」「查过但暂无数据」读的就是它们，
 * 只清日志表用户会以为没清干净。
 * **不动** `metric` 与 `daily_record`——那是正式用户数据，不是排障痕迹。
 */
class SqldelightLogStore(private val db: LifeBoardDb) : LogStore {

    private val q: LogQueries = db.logQueries

    override suspend fun insertAppLog(log: AppLogRecord) {
        q.insertAppLog(
            level = log.level,
            tag = log.tag,
            location = log.location,
            message = log.message,
            time = log.time,
        )
    }

    override suspend fun pageAppLogs(
        level: String,
        query: String,
        limit: Int,
        offset: Int,
    ): List<AppLogRecord> =
        // 位置参数：这一版生成器把 `value` 撞名的参数重命名成 value_/value__/…，
        // 名字不稳定，按位置传更可靠
        q.pageAppLogs(
            level,
            level,
            query,
            query,
            query,
            limit.toLong(),
            offset.toLong(),
        ).executeAsList().map { it.toModel() }

    override suspend fun latestAppLogs(limit: Int): List<AppLogRecord> =
        q.latestAppLogs(limit.toLong()).executeAsList().map { it.toModel() }

    override suspend fun countAppLogs(): Int =
        q.countAppLogs().executeAsOne().toInt()

    override suspend fun clearAppLogs() {
        q.clearAppLogs()
    }

    override suspend fun clearPluginSnapshots() {
        // 两张插件侧快照表必须一起清：只清条目不清查询痕迹，
        // 详情页仍会把已清空的指标列成「查过但暂无数据」，看起来像没清干净
        val plugin = db.pluginQueries
        q.transaction {
            plugin.clearAllRawRecords()
            plugin.clearAllMetricQueries()
        }
    }

    override suspend fun trimAppLogs(keepLimit: Int) {
        q.trimAppLogs(keepLimit.toLong())
    }

    override suspend fun insertNotificationLog(log: NotificationLogRecord): Long {
        if (log.id != 0L) {
            q.insertNotificationLog(
                id = log.id,
                taskId = log.taskId,
                remindLevel = log.remindLevel.toLong(),
                triggeredAt = log.triggeredAt,
                delivered = if (log.delivered) 1L else 0L,
                note = log.note,
                createdAt = log.createdAt,
            )
            return log.id
        }
        // id = 0 时省略主键列，让 AUTOINCREMENT 分配；insert 的返回值就是新 id
        return q.insertNotificationLogAuto(
            taskId = log.taskId,
            remindLevel = log.remindLevel.toLong(),
            triggeredAt = log.triggeredAt,
            delivered = if (log.delivered) 1L else 0L,
            note = log.note,
            createdAt = log.createdAt,
        ).await()
    }

    override suspend fun recentNotificationLogs(limit: Int): List<NotificationLogRecord> =
        q.recentNotificationLogs(limit.toLong()).executeAsList().map { it.toModel() }
}

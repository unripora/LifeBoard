package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.NoticeQueries
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.model.CaptureRuleRecord
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.model.RawNotificationRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [RuleStore] 的 SQLDelight 实现。
 *
 * `saveRule` / `saveCustomSource` 在 `id = 0` 时返回新自增 id。
 * 新增走 `insertXxxAuto`（省略 id 列），直接用 insert 的返回值——
 * SQLDelight 的 `execute()` 返回的就是 `last_insert_rowid()`，
 * 而 REPLACE 命中冲突时是 2 那种说法只适用于受影响行数，别被绕进去。
 */
class SqldelightRuleStore(private val db: LifeBoardDb) : RuleStore {

    private val q: NoticeQueries = db.noticeQueries

    override fun observeRules(): Flow<List<CaptureRuleRecord>> =
        q.observeAllRules().asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getEnabledRules(): List<CaptureRuleRecord> =
        q.selectEnabledRules().executeAsList().map { it.toModel() }

    override suspend fun getEnabledRulesByPackage(packageName: String): List<CaptureRuleRecord> =
        q.selectEnabledRulesByPackage(packageName).executeAsList().map { it.toModel() }

    override suspend fun getAllRules(): List<CaptureRuleRecord> =
        q.selectAllRules().executeAsList().map { it.toModel() }

    override suspend fun getRule(id: Long): CaptureRuleRecord? =
        q.selectRuleById(id).executeAsOneOrNull()?.toModel()

    override suspend fun saveRule(rule: CaptureRuleRecord): Long {
        val row = SqldelightMappers.run { rule.toRow() }
        if (row.id != 0L) {
            q.insertRule(
                id = row.id,
                packageName = row.packageName,
                sourceName = row.sourceName,
                bucketName = row.bucketName,
                includeKeywords = row.includeKeywords,
                excludeKeywords = row.excludeKeywords,
                timeRegex = row.timeRegex,
                windowStartMinute = row.windowStartMinute,
                windowEndMinute = row.windowEndMinute,
                dailyOnce = row.dailyOnce,
                enabled = row.enabled,
                createdAt = row.createdAt,
                windows = row.windows,
                startDate = row.startDate,
                endDate = row.endDate,
                repeatType = row.repeatType,
                weekdayMask = row.weekdayMask,
                anchorDay = row.anchorDay,
                anchorMonth = row.anchorMonth,
            )
            return row.id
        }
        // 新增时省略 id 列，让 AUTOINCREMENT 分配（显式传 0 会被真的存成 0）
        return q.insertRuleAuto(
            packageName = row.packageName,
            sourceName = row.sourceName,
            bucketName = row.bucketName,
            includeKeywords = row.includeKeywords,
            excludeKeywords = row.excludeKeywords,
            timeRegex = row.timeRegex,
            windowStartMinute = row.windowStartMinute,
            windowEndMinute = row.windowEndMinute,
            dailyOnce = row.dailyOnce,
            enabled = row.enabled,
            createdAt = row.createdAt,
            windows = row.windows,
            startDate = row.startDate,
            endDate = row.endDate,
            repeatType = row.repeatType,
            weekdayMask = row.weekdayMask,
            anchorDay = row.anchorDay,
            anchorMonth = row.anchorMonth,
        ).await()
    }

    override suspend fun deleteRule(id: Long) {
        q.deleteRuleById(id)
    }

    override suspend fun countRulesBySource(sourceName: String): Int =
        q.countRulesBySource(sourceName).executeAsOne().toInt()

    override fun observeCustomSources(): Flow<List<CustomSourceRecord>> =
        q.observeAllCustomSources().asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getCustomSources(): List<CustomSourceRecord> =
        q.selectAllCustomSources().executeAsList().map { it.toModel() }

    override suspend fun getCustomSource(id: Long): CustomSourceRecord? =
        q.selectCustomSourceById(id).executeAsOneOrNull()?.toModel()

    override suspend fun saveCustomSource(source: CustomSourceRecord): Long {
        if (source.id != 0L) {
            q.insertCustomSource(
                id = source.id,
                displayName = source.displayName,
                bucket = source.bucket,
                createdAt = source.createdAt,
            )
            return source.id
        }
        return q.insertCustomSourceAuto(
            displayName = source.displayName,
            bucket = source.bucket,
            createdAt = source.createdAt,
        ).await()
    }

    override suspend fun deleteCustomSource(id: Long) {
        q.deleteCustomSourceById(id)
    }

    override suspend fun insertRawNotification(entry: RawNotificationRecord) {
        q.insertRawNotification(
            packageName = entry.packageName,
            title = entry.title,
            text = entry.text,
            matchedSources = entry.matchedSources,
            postedAt = entry.postedAt,
        )
    }

    override fun observeRecentRawNotifications(limit: Int): Flow<List<RawNotificationRecord>> =
        q.observeRecentRawNotifications(limit.toLong()).asListFlow()
            .map { rows -> rows.map { it.toModel() } }

    override suspend fun trimRawNotifications(keep: Int) {
        q.trimRawNotifications(keep.toLong())
    }

    override suspend fun clearRawNotifications() {
        q.clearRawNotifications()
    }
}

package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.MetricQueries
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.repository.model.AppUsageSum
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toAppUsageSum
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRow
import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [MetricStore] 的 SQLDelight 实现。
 *
 * 这里**不做补零、不做缺日填充**：「今天还没采集」与「今天确实是 0」
 * 在图表上是两种含义，在数据层就把它们抹平会让上游永远分不出来。
 */
class SqldelightMetricStore(
    db: LifeBoardDb,
    private val lookup: SourceKeys.CustomSourceLookup,
) : MetricStore {

    private val q: MetricQueries = db.metricQueries

    override suspend fun getMetricsByDate(date: String): List<MetricRecord> =
        q.selectMetricsByDate(date).executeAsList().map { it.toModel(lookup) }

    override suspend fun getMetric(sourceKey: String, date: String, bucket: String): MetricRecord? =
        q.selectMetric(sourceKey, date, bucket).executeAsOneOrNull()?.toModel(lookup)

    override suspend fun getMetricsInRange(from: String, to: String): List<MetricRecord> =
        q.selectMetricsInRange(from, to).executeAsList().map { it.toModel(lookup) }

    override suspend fun getSourceRange(
        sourceKey: String,
        bucket: String,
        from: String,
        to: String,
    ): List<MetricRecord> =
        q.selectSourceRange(sourceKey, bucket, from, to).executeAsList().map { it.toModel(lookup) }

    override suspend fun latestDateWithBucket(
        sourceKey: String,
        bucket: String,
        from: String,
        today: String,
    ): String? = q.latestDateWithBucket(sourceKey, bucket, from, today)
        .executeAsOne()
        .latestDate

    override suspend fun saveMetric(metric: MetricRecord) {
        // bucket 取自 source.bucket，与 Room 版 toEntity 的取值口径一致
        q.insertMetric(
            sourceType = metric.source.key,
            date = metric.date,
            bucket = metric.source.bucket.name,
            value_ = metric.value,
        )
    }

    override suspend fun deleteMetricsBySource(sourceKey: String): Int =
        q.deleteMetricsBySource(sourceKey).await().toInt()

    override fun observeSourceHealth(): Flow<List<SourceHealth>> =
        q.observeSourceHealth().asListFlow().map { rows -> rows.map { it.model(lookup) } }

    override suspend fun getAllSourceHealth(): List<SourceHealth> =
        q.selectAllSourceHealth().executeAsList().map { it.model(lookup) }

    override suspend fun saveSourceHealth(health: SourceHealth) {
        q.insertSourceHealth(
            sourceType = health.source.key,
            healthy = if (health.healthy) 1L else 0L,
            lastSuccessAt = health.lastSuccessAt,
            message = health.message,
        )
    }

    override suspend fun deleteSourceHealth(sourceKey: String): Int =
        q.deleteSourceHealthBySource(sourceKey).await().toInt()

    override fun observeAppUsageByDate(date: String): Flow<List<AppUsageRecord>> =
        q.observeAppUsageByDate(date).asListFlow().map { rows -> rows.map { it.toModel() } }

    override suspend fun getAppUsageByDate(date: String): List<AppUsageRecord> =
        q.selectAppUsageByDate(date).executeAsList().map { it.toModel() }

    override suspend fun saveAppUsage(rows: List<AppUsageRecord>) {
        q.transaction {
            rows.forEach { r ->
                val row = SqldelightMappers.run { r.toRow() }
                q.insertAppUsage(
                    date = row.date,
                    packageName = row.packageName,
                    minutes = row.minutes,
                    label = row.label,
                )
            }
        }
    }

    override suspend fun deleteAppUsageByDate(date: String) {
        q.deleteAppUsageByDate(date)
    }

    override suspend fun trimAppUsageBefore(keepFrom: String) {
        q.trimAppUsageBefore(keepFrom)
    }

    override suspend fun sumAppUsageInRange(from: String, to: String): List<AppUsageSum> =
        q.sumAppUsageInRange(from, to) { packageName, label, minutes ->
            SqldelightMappers.toAppUsageSum(packageName, label, minutes)
        }.executeAsList()

    /** 健康度行需要单独解析源，映射器只做纯字段转换 */
    private suspend fun com.lifeboard.shared.db.Source_health.model(
        lookup: SourceKeys.CustomSourceLookup,
    ): SourceHealth {
        val ref = SourceKeys.resolve(sourceType, lookup)
        val source = when (ref) {
            is SourceKeys.Resolved.Ok -> ref.ref
            is SourceKeys.Resolved.Fallback -> throw IllegalStateException(
                "source_health.sourceType 解析失败：${ref.reason}",
            )
        }
        return SqldelightMappers.run { toModel(source) }
    }
}

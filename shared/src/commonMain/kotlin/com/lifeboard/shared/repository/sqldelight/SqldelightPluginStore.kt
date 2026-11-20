package com.lifeboard.shared.repository.sqldelight

import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.PluginQueries
import com.lifeboard.shared.repository.PluginStore
import com.lifeboard.shared.repository.model.PluginCredentialRecord
import com.lifeboard.shared.repository.model.PluginRawRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PluginStore] 的 SQLDelight 实现。
 *
 * `cipherText` 是**已加密**的密文，加解密由平台侧的凭据保管负责，本类不碰明文。
 */
class SqldelightPluginStore(private val db: LifeBoardDb) : PluginStore {

    private val q: PluginQueries = db.pluginQueries

    override suspend fun getCredential(pluginId: String, slot: String): String? =
        q.selectCredential(pluginId, slot).executeAsOneOrNull()

    override suspend fun getCredentialSlots(pluginId: String): List<String> =
        q.selectCredentialSlots(pluginId).executeAsList()

    override suspend fun saveCredential(row: PluginCredentialRecord) {
        q.insertCredential(
            pluginId = row.pluginId,
            slot = row.slot,
            cipherText = row.cipherText,
            updatedAt = row.updatedAt,
        )
    }

    override suspend fun deleteCredentialsByPlugin(pluginId: String): Int =
        q.deleteCredentialsByPlugin(pluginId).await().toInt()

    override suspend fun deleteCredentialSlot(pluginId: String, slot: String) {
        q.deleteCredentialSlot(pluginId, slot)
    }

    override suspend fun getRawRecords(
        pluginId: String,
        from: String,
        to: String,
    ): List<PluginRawRecord> =
        q.selectRawRecordsInRange(pluginId, from, to).executeAsList().map { it.toModel() }

    override suspend fun earliestRawRecordDate(pluginId: String): String? =
        q.selectEarliestRawRecordDate(pluginId).executeAsOne().MIN

    override suspend fun latestRawRecordDate(pluginId: String): String? =
        q.selectLatestRawRecordDate(pluginId).executeAsOne().MAX

    override suspend fun insertRawRecords(rows: List<PluginRawRecord>) {
        q.transaction {
            rows.forEach { r ->
                q.insertRawRecord(
                    pluginId = r.pluginId,
                    date = r.date,
                    metricKey = r.metricKey,
                    timestamp = r.timestamp,
                    value_ = r.value,
                    detail = r.detail,
                    seq = r.seq.toLong(),
                )
            }
        }
    }

    override suspend fun deleteRawRecords(pluginId: String, date: String, metricKey: String): Int =
        q.deleteRawRecordsByDateAndMetric(pluginId, date, metricKey).await().toInt()

    override suspend fun deleteRawRecordsByPlugin(pluginId: String): Int =
        q.deleteRawRecordsByPlugin(pluginId).await().toInt()

    override suspend fun clearAllRawRecords(): Int =
        q.clearAllRawRecords().await().toInt()

    override suspend fun recordMetricQueries(
        pluginId: String,
        date: String,
        metricKeys: List<String>,
    ) {
        q.transaction {
            // REPLACE 语义：同一天重复查询同一指标不该报主键冲突
            metricKeys.forEach { key -> q.insertMetricQuery(pluginId, date, key) }
        }
    }

    override suspend fun getMetricQueries(pluginId: String, from: String, to: String): List<String> =
        q.selectMetricQueriesInRange(pluginId, from, to).executeAsList()

    override suspend fun earliestMetricQueryDate(pluginId: String): String? =
        q.selectEarliestMetricQueryDate(pluginId).executeAsOne().MIN

    override suspend fun deleteMetricQueriesByPlugin(pluginId: String): Int =
        q.deleteMetricQueriesByPlugin(pluginId).await().toInt()

    override suspend fun clearAllMetricQueries(): Int =
        q.clearAllMetricQueries().await().toInt()
}

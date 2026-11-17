package com.lifeboard.shared.repository

import com.lifeboard.shared.repository.model.CaptureRuleRecord
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.model.RawNotificationRecord
import kotlinx.coroutines.flow.Flow

/**
 * 采集规则与自定义数据源的**数据访问**契约。
 *
 * 两者合成一个接口的理由同布局：规则通过 `sourceName` 引用自定义源，
 * 删除源之前必须检查有没有规则还指着它，这个校验天然跨越两张表。
 */
interface RuleStore {

    fun observeRules(): Flow<List<CaptureRuleRecord>>

    /** 采集热路径用：只取启用的规则，避免每次事件都过滤全表 */
    suspend fun getEnabledRules(): List<CaptureRuleRecord>

    suspend fun getEnabledRulesByPackage(packageName: String): List<CaptureRuleRecord>

    suspend fun getAllRules(): List<CaptureRuleRecord>

    suspend fun getRule(id: Long): CaptureRuleRecord?

    /** `id = 0` 时插入并返回新 id，否则按主键覆盖 */
    suspend fun saveRule(rule: CaptureRuleRecord): Long

    suspend fun deleteRule(id: Long)

    /** 统计某个数据源被多少条规则引用，删除自定义源前用来拦截 */
    suspend fun countRulesBySource(sourceName: String): Int

    fun observeCustomSources(): Flow<List<CustomSourceRecord>>

    suspend fun getCustomSources(): List<CustomSourceRecord>

    suspend fun getCustomSource(id: Long): CustomSourceRecord?

    suspend fun saveCustomSource(source: CustomSourceRecord): Long

    suspend fun deleteCustomSource(id: Long)

    suspend fun insertRawNotification(entry: RawNotificationRecord)

    /** 最近的原始通知，倒序 */
    fun observeRecentRawNotifications(limit: Int = 100): Flow<List<RawNotificationRecord>>

    /** 只保留最近 [keep] 条，防止无限膨胀 */
    suspend fun trimRawNotifications(keep: Int)

    suspend fun clearRawNotifications()
}

package com.lifeboard.shared.repository

import com.lifeboard.shared.repository.model.PluginCredentialRecord
import com.lifeboard.shared.repository.model.PluginRawRecord

/**
 * 插件运行时数据的**数据访问**契约：凭据与原始条目。
 *
 * [PluginCredentialRecord.cipherText] 是**已加密**的密文，
 * 加解密由平台侧的凭据保管实现负责，本接口不碰明文。
 */
interface PluginStore {

    /** 取某插件某个槽位的密文；没有返回 null */
    suspend fun getCredential(pluginId: String, slot: String): String?

    /** 某插件名下全部槽位名，用于「清除前告知会影响哪些项」 */
    suspend fun getCredentialSlots(pluginId: String): List<String>

    suspend fun saveCredential(row: PluginCredentialRecord)

    /** 清空某个插件名下全部凭据，返回删除行数 */
    suspend fun deleteCredentialsByPlugin(pluginId: String): Int

    suspend fun deleteCredentialSlot(pluginId: String, slot: String)

    /**
     * 某插件在 [from]..[to] 内的原始条目。
     *
     * 排序由实现按 `(date, metricKey, seq)` 保证：同一秒可能有多条采样点，
     * 光按时间排会让每次展示的顺序都不稳定。
     */
    suspend fun getRawRecords(pluginId: String, from: String, to: String): List<PluginRawRecord>

    suspend fun earliestRawRecordDate(pluginId: String): String?

    suspend fun latestRawRecordDate(pluginId: String): String?

    suspend fun insertRawRecords(rows: List<PluginRawRecord>)

    /**
     * 清掉某插件某天某个指标的快照，准备用新数据整体替换，返回删除行数。
     *
     * 必须带 metricKey：只按天清会让「这天只有步数」的同步
     * 顺手删掉库里已有的睡眠条目，表现为「同步一次原始数据反而变少了」。
     */
    suspend fun deleteRawRecords(pluginId: String, date: String, metricKey: String): Int

    /** 清空全部插件原始条目，返回删除行数 */
    suspend fun clearAllRawRecords(): Int

    /** 记录「某插件在某天查询过某指标」，重复查询同一天同一指标不报冲突 */
    suspend fun recordMetricQueries(pluginId: String, date: String, metricKeys: List<String>)

    /** 某插件在 [from]..[to] 内查过的指标键，去重后的列表 */
    suspend fun getMetricQueries(pluginId: String, from: String, to: String): List<String>

    /** 某插件最早有过查询痕迹的日期，供「全部」时间段算起点 */
    suspend fun earliestMetricQueryDate(pluginId: String): String?

    /**
     * 清空全部查询痕迹，返回删除行数。
     *
     * 必须与 [clearAllRawRecords] 一起清：只清条目不清痕迹的话，
     * 详情页仍会把已清空的指标列成「查过但暂无数据」，看起来像没清干净。
     */
    suspend fun clearAllMetricQueries(): Int
}

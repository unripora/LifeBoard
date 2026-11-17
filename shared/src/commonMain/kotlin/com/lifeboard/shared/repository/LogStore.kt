package com.lifeboard.shared.repository

import com.lifeboard.shared.repository.model.AppLogRecord
import com.lifeboard.shared.repository.model.NotificationLogRecord

/**
 * 应用内日志与提醒触达日志的**数据访问**契约。
 *
 * `NotificationLogRecord` 放在这里而不是任务接口：它记录的是「提醒发没发出去」，
 * 属于可观测性数据，与任务配置的生命周期无关——任务删了日志仍要留作排障依据。
 */
interface LogStore {

    suspend fun insertAppLog(log: AppLogRecord)

    /** 最新在前；[level] 传空表示不按级别过滤，[query] 同时匹配 message 与 tag */
    suspend fun pageAppLogs(level: String, query: String, limit: Int, offset: Int): List<AppLogRecord>

    suspend fun latestAppLogs(limit: Int): List<AppLogRecord>

    suspend fun countAppLogs(): Int

    suspend fun clearAppLogs()

    /**
     * 清空插件采集快照。
     *
     * 日志页的「清空」要连带清掉这两张表：插件详情页的「最近一次」「查过但暂无数据」
     * 读的就是它们，只清日志表用户会以为没清干净。
     * **不动** `metric` 与 `daily_record`——那是正式用户数据，不是排障痕迹。
     */
    suspend fun clearPluginSnapshots()

    /** 只保留最近 [keepLimit] 条，其余删掉 */
    suspend fun trimAppLogs(keepLimit: Int = DEFAULT_APP_LOG_KEEP_LIMIT)

    suspend fun insertNotificationLog(log: NotificationLogRecord): Long

    /** 最近的提醒触达日志，按触发时间倒序 */
    suspend fun recentNotificationLogs(limit: Int = 50): List<NotificationLogRecord>

    companion object {
        /** 一万条足够回溯一次完整的启动 + 采集，又不至于把库撑大 */
        const val DEFAULT_APP_LOG_KEEP_LIMIT = 10_000
    }
}

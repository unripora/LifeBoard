package com.lifeboard.shared.repository.model

/**
 * 应用内日志。
 *
 * 与平台的 `Log` 是**并行**关系：那份照常输出，这里多一份可持久化、可检索的记录。
 */
data class AppLogRecord(
    val id: Long = 0L,
    /** DEBUG / INFO / WARN / ERROR */
    val level: String,
    /** 来源模块，如 PluginLoader、Aggregate */
    val tag: String,
    /** 调用点，形如 `PluginLoader.kt:168` */
    val location: String,
    val message: String,
    val time: Long,
)

/**
 * 提醒触达日志，用于排查「到点没响」。
 *
 * 它记录的是「提醒发没发出去」，属于可观测性数据：
 * 任务删了这些行仍要保留，否则事后无法复盘。
 */
data class NotificationLogRecord(
    val id: Long = 0L,
    val taskId: Long,
    val remindLevel: Int,
    val triggeredAt: Long,
    val delivered: Boolean,
    val note: String = "",
    val createdAt: Long,
)

/**
 * 插件凭据密文。
 *
 * [cipherText] 已由平台侧加密，Repository 只负责存取，不碰明文——
 * 这样桌面端将来可以换成另一套密钥存储而不影响契约。
 *
 * [updatedAt] 用于排查「凭据什么时候被改过」，不是可省略的元数据：
 * 少了它就无法区分「这次登录成功后 token 没换」和「换了但存的是旧的」。
 */
data class PluginCredentialRecord(
    val pluginId: String,
    val slot: String,
    val cipherText: String,
    val updatedAt: Long = 0L,
)

/**
 * 插件上报的原始条目（「上次同步的快照」）。
 *
 * 按 (pluginId, date, metricKey) 整体替换：同一天同一指标再同步就覆盖，
 * 不同日期各存各的。这些条目只用于展示，不参与判定、计分与趋势。
 */
data class PluginRawRecord(
    val id: Long = 0L,
    val pluginId: String,
    val date: String,
    val metricKey: String,
    val timestamp: Long,
    val value: Double,
    val detail: String = "",
    /** 同一指标内的原始顺序，用来稳定排序（时间戳可能相同） */
    val seq: Int = 0,
)

/**
 * 分应用屏幕使用时长。
 *
 * 为什么不塞进 `MetricRecord`：`metric` 的主键是 (sourceType, date, bucket)，
 * 没有「应用」这个维度。总时长与各应用各用了多久是两种粒度，
 * 合并会破坏主键语义。
 */
data class AppUsageRecord(
    val date: String,
    val packageName: String,
    val minutes: Double,
    /** 采集时的应用展示名，避免每次查 PackageManager */
    val label: String = "",
)

/**
 * 闭区间内按应用汇总的用量。
 *
 * 由 SQL 的 `GROUP BY packageName` 直接产出，
 * 不把整段区间的原始行拉进内存再分组——30 天 × 上百个应用会是个可观的数组。
 */
data class AppUsageSum(
    val packageName: String,
    val label: String,
    val minutes: Double,
)

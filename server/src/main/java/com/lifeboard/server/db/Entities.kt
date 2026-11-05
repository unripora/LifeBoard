package com.lifeboard.server.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务配置表。字段与 [com.lifeboard.core.model.TaskConfig] 对应。
 *
 * 时段相关字段有两套：
 * - `windowStartMinute/windowEndMinute`：旧版单时段，保留作为「默认时段」，
 *   新建任务时由 UI 同步写入第一个时段，保证 `ScheduleRule` 为空时行为不变
 * - `windows/startDate/endDate/repeatType/weekdayMask/anchorDay/anchorMonth`：
 *   新版多时段 + 日期范围 + 重复周期，判定统一走 [com.lifeboard.core.schedule.ScheduleRule]
 */
@Entity(tableName = "task")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val sourceType: String,
    val metricBucket: String,
    val threshold: Double,
    val weight: Double,
    val remindLevel: Int,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val enabled: Boolean,
    /** 多时段，格式 "540-570,1080-1110"；空串表示沿用 windowStart/End */
    val windows: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val repeatType: String = "DAILY",
    /** 自定义星期的集合，格式 "1,3,5" */
    val weekdayMask: String = "",
    val anchorDay: Int = 1,
    val anchorMonth: Int = 1,
    /**
     * 关联的采集规则 id，0 表示未关联。
     *
     * 关联后任务不再维护自己的时段：生效时间、提醒时刻全部由规则推导，
     * 避免「规则里设了 09:00-09:30，任务里又要再设一遍检测时段」这种重复配置，
     * 以及两处不一致导致「规则命中了但任务没提醒」。
     */
    val ruleId: Long = 0L,
    /**
     * 任务时间的来源。
     *
     * - `FOLLOW_RULE`（默认）：时段与提醒时刻都取自 [ruleId] 指向的规则，
     *   只有关联了规则才有意义；未关联时自动回落到任务自身时段
     * - `INDEPENDENT`：忽略规则的时间，用任务自己配的时段与提醒时刻。
     *   适合「每天喝 8 杯水」这类与规则采集窗口无关的任务——
     *   规则只是数据来源，提醒时间该由任务自己说了算
     *
     * 为什么要有它：之前任务完全不能自定时间，一旦关联规则就被绑死在
     * 规则的采集窗口上；但采集窗口和提醒时机是两个不同的问题。
     */
    val timeSource: String = "FOLLOW_RULE",
    /**
     * 完成提醒档位，与 [remindLevel]（未完成提醒）相互独立。
     *
     * 0 = 不提醒（默认，保持老任务行为不变）
     * 1 = 弱：完成任务时发顶部横幅通知
     * 2 = 强：横幅通知 + 响铃 + 震动
     */
    val doneRemindLevel: Int = 0,
    /**
     * 任务完成时是否弹应用内小浮窗（Toast），默认开。
     *
     * 与 [doneRemindLevel] 独立：横幅在应用处于前台时会被系统抑制，
     * 此时靠这个浮窗保证「完成了」这件事一定看得见。
     */
    val doneToastEnabled: Boolean = true,
    /**
     * 未达成提醒的判定偏移（分钟），相对每个时段的结束时刻。
     *
     * 负数 = 提前判定（如 -5 表示时段结束前 5 分钟）；
     * 正数 = 延后判定（如 +5 表示结束后 5 分钟，给读屏留出计数缓冲）；
     * 0 = 正好在时段结束时判定。
     *
     * 默认 +5：规则时段同时也是读屏窗口，刚过结束点就判定会把人「锁在门外」
     * ——提醒响了却已经读不到数据。
     */
    val windowOffsetMinutes: Int = 5,
)

/**
 * 每日完成记录表。同一任务同一天只应有一条，故以 (taskId, date) 唯一约束。
 */
@Entity(
    tableName = "daily_record",
    primaryKeys = ["taskId", "date"],
    indices = [Index("date")],
)
data class DailyRecordEntity(
    val taskId: Long,
    val date: String,
    val value: Double,
    val completed: Boolean,
    val score: Double,
)

/**
 * 原始指标表。按 源 + 日期 + 分桶 聚合，全年粒度设计以便后续做年度总结时不改库。
 */
@Entity(
    tableName = "metric",
    primaryKeys = ["sourceType", "date", "bucket"],
    indices = [Index("date")],
)
data class MetricEntity(
    val sourceType: String,
    val date: String,
    val bucket: String,
    val value: Double,
)

/**
 * 连续打卡状态表。
 */
@Entity(tableName = "streak")
data class StreakEntity(
    @PrimaryKey val taskId: Long,
    val currentStreak: Int,
    val bestStreak: Int,
    val lastDate: String?,
)

/**
 * 提醒触达日志，用于排查"到点没响"的问题。
 */
@Entity(tableName = "notification_log", indices = [Index("createdAt")])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val remindLevel: Int,
    val triggeredAt: Long,
    val delivered: Boolean,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 数据源健康度表，面板据此提示断源降级。
 */
@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val sourceType: String,
    val healthy: Boolean,
    val lastSuccessAt: Long,
    val message: String,
)

/**
 * 原始通知流水（M4 校准用）。
 *
 * 记录目标应用的每一条通知原文，不管是否命中规则——
 * "飞书打卡到底发不发通知、文案是什么"只有实机能回答，
 * 关键词校准必须有原始样本可看，而不是靠猜。
 */
@Entity(
    tableName = "raw_notification",
    indices = [Index("postedAt"), Index("packageName")],
)
data class RawNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    /** 命中了哪些规则的源名（逗号分隔），空串 = 未命中 */
    val matchedSources: String = "",
    val postedAt: Long = System.currentTimeMillis(),
)

/**
 * 用户自建的识别规则。
 *
 * 为什么规则要可配：打卡成功的文案因应用、版本、企业而异，
 * 写死在代码里必然出现「关键词对不上、数据悄悄为 0」的情况。
 * 让用户自己选应用、填关键词、划时段，才是可持续的做法。
 *
 * @param packageName 监听哪个应用
 * @param sourceName 命中后写入 metric 的 sourceType（决定面板归到哪个源）
 * @param bucketName 写入哪个量纲（FLAG = 每天只算一次，COUNT = 命几次算几次）
 * @param includeKeywords 必须**全部**出现的词，逗号分隔；空串表示只要该应用的事件就算
 * @param excludeKeywords 出现任意一个就丢弃，逗号分隔
 * @param timeRegex 可选的时间格式约束（正则），如 `\d{1,2}:\d{2}`；空串不约束
 * @param windowStartMinute 采信时段起点（一天内第几分钟），默认 0
 * @param windowEndMinute 采信时段终点，默认 1440（整天）
 * @param windows 多时段，"540-570,1080-1110"；空串表示沿用上面两个旧字段
 * @param startDate 生效起始日期 yyyy-MM-dd，空串不限
 * @param endDate 生效结束日期 yyyy-MM-dd，空串不限
 * @param repeatType 重复周期，见 [com.lifeboard.core.schedule.RepeatType]
 * @param weekdayMask 自定义星期，"1,3,5"（1=周一）
 * @param anchorDay 每月/每年的日
 * @param anchorMonth 每年的月
 * @param dailyOnce 每日幂等：当天命中一次后不再累加
 * @param enabled 规则开关
 * @param createdAt 创建时间，用于稳定排序
 */
@Entity(tableName = "capture_rule", indices = [Index("packageName")])
data class CaptureRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val sourceName: String,
    val bucketName: String = "FLAG",
    val includeKeywords: String = "",
    val excludeKeywords: String = "",
    val timeRegex: String = "",
    val windowStartMinute: Int = 0,
    val windowEndMinute: Int = 24 * 60,
    val dailyOnce: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val windows: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val repeatType: String = "DAILY",
    val weekdayMask: String = "",
    val anchorDay: Int = 1,
    val anchorMonth: Int = 1,
)

/**
 * 用户自建的数据源。
 *
 * 内置源是编译期枚举，覆盖不到"我想把某个应用的通知算成自己的一个维度"这类需求，
 * 所以允许用户自建。自建源没有采集器，数据只能由采集规则命中写入——
 * 面板需要据此标注「仅规则计数」，否则用户看到 0 会以为是采集坏了。
 */
@Entity(tableName = "custom_source")
data class CustomSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 展示名称，如「每日背单词」 */
    val displayName: String,
    /** 量纲，取 [com.lifeboard.core.model.MetricBucket] 的枚举名 */
    val bucket: String = "COUNT",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 分应用屏幕使用时长。
 *
 * 为什么单独一张表：`metric` 的主键是 (sourceType, date, bucket)，没有应用这个维度，
 * 而「屏幕使用」的总时长与「各应用各用了多久」是两种粒度的数据，
 * 塞进同一张表会破坏主键语义。这里用 (date, packageName) 做主键，
 * 同一天同一应用重复采集时直接覆盖，天然幂等。
 *
 * @param date 日期 yyyy-MM-dd
 * @param packageName 应用包名
 * @param minutes 当天该应用的前台时长（分钟）
 * @param label 采集时的应用展示名，用于列表直接渲染，避免每次查 PackageManager
 */
@Entity(tableName = "app_usage", primaryKeys = ["date", "packageName"])
data class AppUsageEntity(
    val date: String,
    val packageName: String,
    val minutes: Double,
    val label: String = "",
)

package com.lifeboard.core.model

import com.lifeboard.core.schedule.ScheduleRule

/**
 * 提醒档位。三级提醒的核心枚举，由用户为每个任务单独配置。
 */
enum class RemindLevel(val code: Int) {
    /** 仅在本地记录完成状态，无任何前台交互 */
    NONE(0),

    /** 弹出系统通知或悬浮窗提示 */
    WEAK(1),

    /** 前台服务播放闹钟铃声 + 震动，强制提醒 */
    STRONG(2),
    ;

    companion object {
        fun fromCode(code: Int): RemindLevel = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * 任务配置，对应面板上的一个待办事项。
 *
 * @property id 主键，0 表示未持久化
 * @property title 任务名称
 * @property sourceType 绑定的数据源
 * @property metricBucket 该数据源的量纲
 * @property threshold 达成阈值，语义为 "value >= threshold 即达成"
 * @property weight 权重，用于计算当日综合得分
 * @property remindLevel 提醒档位
 * @property windowStartMinute 检测时段开始（当日 0 点起的分钟数）
 * @property windowEndMinute 检测时段结束
 * @property schedule 生效规则（多时段 + 日期范围 + 重复周期）；默认全天每日
 * @property ruleId 关联的采集规则 id，0 表示未关联。
 *   关联后 [schedule] 仅作为规则缺失时的兜底，提醒时刻由规则推导
 * @property enabled 是否启用
 */
data class TaskConfig(
    val id: Long = 0L,
    val title: String,
    val source: SourceRef,
    val metricBucket: MetricBucket,
    val threshold: Double,
    val weight: Double = 1.0,
    val remindLevel: RemindLevel = RemindLevel.WEAK,
    val windowStartMinute: Int = 0,
    val windowEndMinute: Int = 24 * 60 - 1,
    val schedule: ScheduleRule = ScheduleRule.ALWAYS,
    val ruleId: Long = 0L,
    val enabled: Boolean = true,
    /**
     * 完成提醒档位，与 [remindLevel]（未完成提醒）相互独立。
     *
     * 默认 NONE：老任务升级后行为不变。
     */
    val doneRemindLevel: RemindLevel = RemindLevel.NONE,
    /** 完成时是否弹应用内小浮窗（Toast），默认开 */
    val doneToastEnabled: Boolean = true,
    /**
     * 未达成提醒相对时段结束的判定偏移（分钟），-60..60。
     * 负数提前、正数延后、0 表示准时；默认 +5 给读屏窗口留缓冲。
     */
    val windowOffsetMinutes: Int = 5,
) {
    /** 检测时段是否为合法区间 */
    val hasValidWindow: Boolean
        get() = windowStartMinute in 0..(24 * 60 - 1) &&
            windowEndMinute in 0..(24 * 60 - 1) &&
            windowStartMinute < windowEndMinute

    /** 数据源键，写进 metric.sourceType */
    val sourceKey: String get() = source.key
}

/**
 * 某任务在某一日的完成记录。
 *
 * @property date 日期，格式 yyyy-MM-dd
 * @property value 当日采集到的原始值
 * @property completed 是否达成阈值
 * @property score 该任务当日贡献的分数（weight * 达成系数）
 */
data class DailyRecord(
    val taskId: Long,
    val date: String,
    val value: Double,
    val completed: Boolean,
    val score: Double,
)

/**
 * 数据源采集到的原始指标，按 源 + 日期 + 分桶 聚合。
 */
data class MetricRecord(
    val source: SourceType,
    val date: String,
    val bucket: MetricBucket,
    val value: Double,
)

/**
 * 任务的连续打卡状态。
 */
data class StreakInfo(
    val taskId: Long,
    val currentStreak: Int,
    val bestStreak: Int,
    val lastDate: String?,
)

/**
 * 数据源健康度，用于面板提示断源降级。
 *
 * @property lastSuccessAt 最后一次成功采集时间戳（毫秒），0 表示从未成功
 * @property message 失败原因或状态说明
 */
data class SourceHealth(
    val source: SourceType,
    val healthy: Boolean,
    val lastSuccessAt: Long = 0L,
    val message: String = "",
)

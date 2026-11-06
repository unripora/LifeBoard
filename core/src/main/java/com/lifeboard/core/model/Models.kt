package com.lifeboard.core.model

import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef

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
 * 任务时间的来源。
 *
 * 采集窗口和提醒时机是两个不同的问题：
 * 规则里的时段决定「什么时候允许从这个应用计数」，
 * 而任务想在什么时候被提醒，应该由任务自己决定。
 *
 * 之前两者被绑在一起，导致任务一旦关联规则就无法自定时间，
 * 「每天喝 8 杯水」这类任务只能被迫跟随飞书的打卡时段。
 */
enum class TimeSource(val displayName: String, val description: String) {
    /** 跟随关联规则的时段与提醒时刻（关联了规则才有意义） */
    FOLLOW_RULE("跟随规则", "用规则里配的时段，规则什么时候生效就什么时候提醒"),

    /** 任务自己配时段与提醒时刻，规则只作为数据来源 */
    INDEPENDENT("自定义时间", "规则只管取数，提醒时间由这个任务自己定"),
    ;

    companion object {
        fun fromName(name: String): TimeSource =
            entries.firstOrNull { it.name == name } ?: FOLLOW_RULE
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
    /** 时间来源：跟随规则，还是任务自己定 */
    val timeSource: TimeSource = TimeSource.FOLLOW_RULE,
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
 *
 * 量纲不再单独存一份：它已经写在 [source] 里（[SourceRef.bucket]）。
 * 历史上这里还有一个 `bucket` 字段，与 `source` 各存一份，
 * 两者不一致时会出现「按源查得到、按量纲查不到」的错位，
 * 而 `metric` 表的主键是 `(sourceType, date, bucket)`，落库时必须用同一个量纲。
 *
 * 一个源要产出多个量纲时（如小米健康同时给步数与睡眠），
 * 由采集器为每个量纲各造一个 [SourceRef]，量纲随源一起确定。
 */
data class MetricRecord(
    val source: SourceRef,
    val date: String,
    val value: Double,
) {
    /** 写入 `metric.bucket` 列的量纲名 */
    val bucket: MetricBucket get() = source.bucket
}

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
 * [source] 用 [SourceRef] 而不是 [SourceType] 枚举：插件源是运行时才存在的，
 * 不可能追加到编译期枚举里。落库时用 [SourceRef.key]，与 `metric.sourceType` 同一套编码。
 *
 * @property lastSuccessAt 最后一次成功采集时间戳（毫秒），0 表示从未成功
 * @property message 失败原因或状态说明
 */
data class SourceHealth(
    val source: SourceRef,
    val healthy: Boolean,
    val lastSuccessAt: Long = 0L,
    val message: String = "",
)

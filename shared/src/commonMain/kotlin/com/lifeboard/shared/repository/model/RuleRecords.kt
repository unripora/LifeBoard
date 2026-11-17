package com.lifeboard.shared.repository.model

import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef

/**
 * 采集规则的持久化形态。
 *
 * 为什么不复用 [ScheduleRule] 而保留 `windows` / `weekdayMask` 这些字符串字段：
 * 这一层是**存储契约**，不是业务模型。落库时它们分别对应 `capture_rule`
 * 表的独立列，把 `ScheduleRule` 拆开再拼回去的转换集中在映射层一处，
 * 而不是让每个调用点各自处理编解码。
 *
 * 需要按「此刻是否生效」判断时，用 [schedule] 拿到强类型对象——
 * 它由这些字符串字段经 [ScheduleRule.decodeWindows] / [ScheduleRule.decodeWeekdays]
 * 还原，判定逻辑只有 `:core` 里那一份。
 */
data class CaptureRuleRecord(
    val id: Long = 0L,
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
    val createdAt: Long = 0L,
    val windows: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val repeatType: String = "DAILY",
    val weekdayMask: String = "",
    val anchorDay: Int = 1,
    val anchorMonth: Int = 1,
) {
    /** 强类型视角：日期范围 / 周期 / 多时段统一收敛到 `:core` 的判定实现 */
    val schedule: ScheduleRule
        get() = ScheduleRule(
            // 老规则没有 windows 列（或写的是空串），回落到旧的单时段字段，
            // 行为与升级前保持一致，不能让历史规则升级后突然变成"全天生效"
            windows = ScheduleRule.decodeWindows(windows).ifEmpty {
                listOf(TimeWindow(windowStartMinute, windowEndMinute.coerceAtLeast(windowStartMinute)))
            },
            startDate = startDate,
            endDate = endDate,
            repeatType = RepeatType.fromName(repeatType),
            weekdays = ScheduleRule.decodeWeekdays(weekdayMask),
            anchorDay = anchorDay,
            anchorMonth = anchorMonth,
        )

    /**
     * 命中后要写入的指标源。
     *
     * 规则的 [sourceName] 与 [bucketName] 是两列，而 [MetricRecord] 要求量纲
     * 随 [SourceRef] 一起给出（`metric` 表主键是 `(sourceType, date, bucket)`）。
     * 这里把两列合成一个引用，写入方就不必各自拼一次、各自错一次。
     *
     * 内置源与自建源的 key 与量纲无关，只有插件源形如 `PLUGIN_id:metric`；
     * 后者按插件 key 反解，反解不出的按内置 key 处理。
     */
    val metricSource: SourceRef
        get() {
            val bucket = MetricSpec.bucketOf(bucketName) ?: MetricBucket.COUNT
            SourceRef.parsePluginKey(sourceName)?.let { (pluginId, metricKey) ->
                return SourceRef.Plugin(
                    pluginId = pluginId,
                    metricKey = metricKey,
                    displayName = sourceName,
                    bucket = bucket,
                )
            }
            return SourceRef.Builtin(
                key = sourceName,
                displayName = sourceName,
                bucket = bucket,
            )
        }
}

/**
 * 用户自建数据源。
 *
 * 不自建 `SourceRef.Custom` 的持久化替代品：源的身份是 [id]，
 * 而 `SourceRef.customKey` 定义了唯一的编码方式，这里只保留原始列。
 */
data class CustomSourceRecord(
    val id: Long = 0L,
    val displayName: String,
    val bucket: String = "COUNT",
    val createdAt: Long = 0L,
)

/** 原始通知流水（校准用）。记录目标应用的每一条通知原文，不管是否命中规则。 */
data class RawNotificationRecord(
    val id: Long = 0L,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    /** 命中了哪些规则的源名（逗号分隔），空串 = 未命中 */
    val matchedSources: String = "",
    val postedAt: Long,
)

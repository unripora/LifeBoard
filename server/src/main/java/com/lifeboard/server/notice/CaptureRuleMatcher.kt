package com.lifeboard.server.notice

import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.server.db.CaptureRuleEntity
import java.time.LocalDateTime

/**
 * 用户自定义规则的匹配引擎。
 *
 * 通知通道（[NoticeListenerService]）、system_server hook 回传
 * （[SystemNoticeReceiver]）、无障碍通道（[ScreenCaptureAccessibilityService]）
 * 共用同一套判定，避免三处各写一份、行为不一致。
 *
 * 判定顺序（顺序本身就是防误判设计）：
 * 1. 生效时间：日期范围 → 重复周期 → 时段，全在 [ScheduleRule.isActive] 内
 * 2. 否定词：命中任一即否，「打卡失败」不会被当成打卡
 * 3. 必含词：全部出现才算命中
 * 4. 时间正则：填了才校验
 */
object CaptureRuleMatcher {

    /** 匹配结果，带命中的时间串便于日志排查 */
    data class Result(
        val rule: CaptureRuleEntity,
        val timeText: String?,
    )

    /**
     * 对一条文本内容跑所有规则，返回命中的规则。
     *
     * @param dateTime 事件发生的本地时刻（日期 + 时刻一起传，因为规则可能只在某些日期生效）
     */
    fun match(
        rules: List<CaptureRuleEntity>,
        content: String,
        dateTime: LocalDateTime,
    ): List<Result> {
        if (content.isBlank() || rules.isEmpty()) return emptyList()
        val haystack = content.lowercase()
        return rules.mapNotNull { rule ->
            if (!rule.toRuleSchedule().isActive(dateTime)) return@mapNotNull null

            val excludes = splitKeywords(rule.excludeKeywords)
            if (excludes.any { haystack.contains(it) }) return@mapNotNull null

            val includes = splitKeywords(rule.includeKeywords)
            if (includes.isNotEmpty() && !includes.all { haystack.contains(it) }) {
                return@mapNotNull null
            }

            val timeText = if (rule.timeRegex.isBlank()) {
                null
            } else {
                runCatching { Regex(rule.timeRegex).find(content)?.value }.getOrNull()
                    ?: return@mapNotNull null
            }
            Result(rule, timeText)
        }
    }

    /** 关键词按逗号拆分，中文逗号也认；去空白、转小写 */
    fun splitKeywords(raw: String): List<String> =
        raw.split(',', '，', ';', '；')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
}

/** 规则实体里的生效字段 → 统一的 [ScheduleRule] */
fun CaptureRuleEntity.toRuleSchedule(): ScheduleRule {
    val decoded = ScheduleRule.decodeWindows(windows)
    return ScheduleRule(
        // 老规则没有 windows 字段，回落到旧的单时段，行为保持不变
        windows = decoded.ifEmpty {
            listOf(TimeWindow(windowStartMinute, windowEndMinute.coerceAtLeast(windowStartMinute)))
        },
        startDate = startDate,
        endDate = endDate,
        repeatType = RepeatType.fromName(repeatType),
        weekdays = ScheduleRule.decodeWeekdays(weekdayMask),
        anchorDay = anchorDay,
        anchorMonth = anchorMonth,
    )
}

package com.lifeboard.core.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 一天内的一个时段，单位为「当日第几分钟」。
 *
 * [endMinute] 小于 [startMinute] 表示跨零点（如 22:00–02:00），
 * 这种写法比额外存一个 `crossMidnight` 开关更省字段，也不会有两处状态打架。
 */
data class TimeWindow(val startMinute: Int, val endMinute: Int) {

    init {
        require(startMinute in 0..MAX_MINUTE) { "startMinute 越界：$startMinute" }
        require(endMinute in 0..MAX_MINUTE) { "endMinute 越界：$endMinute" }
    }

    /** 是否跨零点 */
    val crossMidnight: Boolean get() = endMinute < startMinute

    /** 是否全天（00:00–24:00） */
    val isWholeDay: Boolean get() = startMinute == 0 && endMinute == MAX_MINUTE

    /** 时段长度（分钟），跨零点按 +1440 计算 */
    val lengthMinutes: Int
        get() = if (crossMidnight) endMinute + MAX_MINUTE - startMinute else endMinute - startMinute

    /** 某个时刻是否落在本时段内（含两端，跨零点按「或」判定） */
    fun contains(minuteOfDay: Int): Boolean =
        if (crossMidnight) {
            minuteOfDay >= startMinute || minuteOfDay <= endMinute
        } else {
            minuteOfDay in startMinute..endMinute
        }

    /** 展示用 HH:mm–HH:mm */
    fun label(): String = "${formatMinute(startMinute)}–${formatMinute(endMinute)}"

    companion object {
        /** 24:00 用 1440 表示，允许"到当天结束"这种写法 */
        const val MAX_MINUTE = 24 * 60

        /** 全天时段 */
        val WHOLE_DAY = TimeWindow(0, MAX_MINUTE)

        fun formatMinute(minuteOfDay: Int): String {
            val m = minuteOfDay.coerceIn(0, MAX_MINUTE)
            return "%02d:%02d".format(m / 60, m % 60)
        }

        /** 解析 HH:mm，非法返回 null */
        fun parseHourMinute(text: String): Int? {
            val parts = text.trim().split(':')
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..24 || m !in 0..59) return null
            return (h * 60 + m).coerceIn(0, MAX_MINUTE)
        }
    }
}

/**
 * 重复周期。
 *
 * [WORKDAY] 一期按「周一至周五」判定，不内置节假日表——
 * 中国法定节假日每年不同、需持续维护，内置一份过期日历反而是错的。
 * 界面上会明确标注这一点，避免用户以为春节也会提醒。
 */
enum class RepeatType(val displayName: String, val description: String) {
    DAILY("每日", "每天都生效"),
    WEEKLY("每周", "每周指定的一天生效"),
    MONTHLY("每月", "每月指定日期生效，该月无此日则顺延到月末"),
    YEARLY("每年", "每年指定月日生效"),
    WORKDAY("法定工作日", "周一至周五生效（暂不含调休与节假日）"),
    CUSTOM_WEEKDAYS("自定义星期", "只在勾选的星期生效"),
    ;

    companion object {
        fun fromName(name: String): RepeatType = entries.firstOrNull { it.name == name } ?: DAILY
    }
}

/**
 * 生效规则：日期范围 + 重复周期 + 一天内的多个时段。
 *
 * 采集规则与任务提醒共用此对象。之前三处采集通道各写一份 `inWindow`，
 * 一旦有机会就漂移；收口到这里后，任何"这条规则此刻是否生效"的判断
 * 都只有一个答案。
 *
 * @param windows 一天内的时段列表，空列表视为全天
 * @param startDate 生效起始日期 yyyy-MM-dd，空串表示不限
 * @param endDate 生效结束日期 yyyy-MM-dd，空串表示不限
 * @param repeatType 重复周期
 * @param weekdays 自定义星期 / 每周 时使用的星期集合（1=周一 … 7=周日）
 * @param anchorDay 每月 / 每年 的日期（1-31）
 * @param anchorMonth 每年的月份（1-12）
 */
data class ScheduleRule(
    val windows: List<TimeWindow> = listOf(TimeWindow.WHOLE_DAY),
    val startDate: String = "",
    val endDate: String = "",
    val repeatType: RepeatType = RepeatType.DAILY,
    val weekdays: Set<Int> = emptySet(),
    val anchorDay: Int = 1,
    val anchorMonth: Int = 1,
) {

    /** 实际生效的时段：空列表按全天处理，避免"什么都匹配不上"这种静默失效 */
    val effectiveWindows: List<TimeWindow>
        get() = windows.ifEmpty { listOf(TimeWindow.WHOLE_DAY) }

    /**
     * 指定时刻是否生效。
     *
     * 判定顺序即防误判设计：日期范围 → 周期 → 时段。
     * 日期范围放最前，用户设置的"只在考试周生效"不会被周期规则绕过。
     */
    fun isActive(dateTime: LocalDateTime): Boolean {
        val date = dateTime.toLocalDate()
        if (!inDateRange(date)) return false
        if (!matchRepeat(date)) return false
        val minute = dateTime.hour * 60 + dateTime.minute
        return effectiveWindows.any { it.contains(minute) }
    }

    /** 只要日期是否生效（任务提醒按日期注册闹钟时用，不看时刻） */
    fun isDateActive(date: LocalDate): Boolean =
        inDateRange(date) && matchRepeat(date)

    /**
     * 当天所有时段的结束点里最晚的那个（分钟）。
     *
     * 任务提醒用它推导「什么时候算超时」：一条规则可能配了
     * 09:00-09:30 和 18:00-18:30 两段，提醒应该卡在最后一段结束时，
     * 而不是任意一段——早提醒时用户还有后面的机会，提醒没有意义。
     *
     * 跨零点的时段按 1440 处理（即当天结束），因为跨零点段的结束在次日。
     */
    fun latestEndMinute(): Int =
        effectiveWindows.maxOf { window ->
            if (window.crossMidnight) TimeWindow.MAX_MINUTE else window.endMinute
        }

    private fun inDateRange(date: LocalDate): Boolean {
        val d = date.toString()
        if (startDate.isNotBlank() && d < startDate) return false
        if (endDate.isNotBlank() && d > endDate) return false
        return true
    }

    private fun matchRepeat(date: LocalDate): Boolean = when (repeatType) {
        RepeatType.DAILY -> true
        RepeatType.WORKDAY -> date.dayOfWeek.value in 1..5
        RepeatType.WEEKLY, RepeatType.CUSTOM_WEEKDAYS -> {
            // 没勾任何星期时退化为「每天」，否则规则永远不生效且用户看不出原因
            weekdays.isEmpty() || date.dayOfWeek.value in weekdays
        }
        RepeatType.MONTHLY -> {
            val target = anchorDay.coerceIn(1, 31)
            // 该月没有这一天时落到月末，避免"2 月 30 日"这类规则静默失效
            date.dayOfMonth == target.coerceAtMost(date.lengthOfMonth())
        }
        RepeatType.YEARLY -> {
            val month = anchorMonth.coerceIn(1, 12)
            if (date.monthValue != month) {
                false
            } else {
                val target = anchorDay.coerceIn(1, 31)
                date.dayOfMonth == target.coerceAtMost(date.lengthOfMonth())
            }
        }
    }

    /** 给人看的一句话摘要，列表页直接用 */
    fun summary(): String {
        val repeat = when (repeatType) {
            RepeatType.CUSTOM_WEEKDAYS -> weekdayLabel()
            RepeatType.WEEKLY -> "每周" + (weekdays.firstOrNull()?.let(::weekdayName) ?: "一")
            RepeatType.MONTHLY -> "每月 ${anchorDay} 日"
            RepeatType.YEARLY -> "每年 $anchorMonth 月 $anchorDay 日"
            else -> repeatType.displayName
        }
        val spans = if (effectiveWindows.size == 1 && effectiveWindows[0].isWholeDay) {
            "全天"
        } else {
            effectiveWindows.joinToString("、") { it.label() }
        }
        return "$repeat · $spans"
    }

    /** 自定义星期的展示：工作日 / 周末 / 周一、周三… */
    private fun weekdayLabel(): String {
        if (weekdays.isEmpty()) return "每天"
        if (weekdays == setOf(1, 2, 3, 4, 5)) return "工作日"
        if (weekdays == setOf(6, 7)) return "周末"
        return weekdays.sorted().joinToString("、") { weekdayName(it) }
    }

    companion object {

        /** 每日 + 全天，等价于旧版的默认行为 */
        val ALWAYS = ScheduleRule()

        private fun weekdayName(value: Int): String =
            DayOfWeek.of(value.coerceIn(1, 7)).let {
                listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.value - 1]
            }

        // ---- 编解码 ----
        // 存成字符串而不是子表：三条采集通道每次事件都要读规则，
        // 多一次关联查询不值得；解析是纯函数，可单独单测。

        /** 时段列表 → "540-570,1080-1110"；空列表返回空串 */
        fun encodeWindows(windows: List<TimeWindow>): String =
            windows.joinToString(",") { "${it.startMinute}-${it.endMinute}" }

        /** "540-570,1080-1110" → 时段列表；非法片段丢弃 */
        fun decodeWindows(raw: String): List<TimeWindow> {
            if (raw.isBlank()) return emptyList()
            return raw.split(',')
                .mapNotNull { part ->
                    val seg = part.trim().split('-')
                    if (seg.size != 2) return@mapNotNull null
                    val s = seg[0].toIntOrNull() ?: return@mapNotNull null
                    val e = seg[1].toIntOrNull() ?: return@mapNotNull null
                    if (s !in 0..TimeWindow.MAX_MINUTE || e !in 0..TimeWindow.MAX_MINUTE) {
                        return@mapNotNull null
                    }
                    TimeWindow(s, e)
                }
        }

        /** 星期集合 → "1,3,5" */
        fun encodeWeekdays(days: Set<Int>): String =
            days.filter { it in 1..7 }.sorted().joinToString(",")

        /** "1,3,5" → 星期集合 */
        fun decodeWeekdays(raw: String): Set<Int> {
            if (raw.isBlank()) return emptySet()
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet()
        }
    }
}

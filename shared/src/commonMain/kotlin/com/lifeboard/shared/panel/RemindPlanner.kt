package com.lifeboard.shared.panel

import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.util.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒的排程规划（M4-4.5，从 Android 的 `RemindScheduler` 抽出纯计算部分）。
 *
 * ## 这里只算「什么时候该响」，不管「怎么响」
 *
 * 排程里真正容易出错的不是调 `AlarmManager` 那几行，而是**时刻的算法**：
 * 判定卡在时段结束还是开始、偏移量能不能跨天、周期不匹配的日期要不要注册、
 * 边界值 00:00 与 23:59 怎么处理。这些规则两端必须完全一致，
 * 否则同一个任务在手机和电脑上响的时间不同。
 *
 * 所以把它们收在这里，输出一个纯数据的 [Plan]；平台层照着计划计时即可：
 *
 * - Android：每个 [Plan.Entry] 转成一个精确闹钟
 * - 桌面：每个 [Plan.Entry] 转成一次 `Scheduler.scheduleAt`
 *
 * ## 为什么不做成「一次只算下一个时刻」
 *
 * 单个任务可能有多个时段（早上一段、晚上一段），全算出来才能一次登记完。
 * 若只算最近的，响过之后需要再算一次——那就要求平台侧必须回调
 * 「我响完了」，而 `Scheduler` 契约故意没有这个回调
 * （Android 的 `BroadcastReceiver` 与桌面协程都不方便保证它一定被调用）。
 */
object RemindPlanner {

    /**
     * 单个任务最多注册的时段数。
     *
     * 与界面上的时段上限保持一致，防止用户配了几十段导致闹钟数量爆炸
     * （Android 的闹钟是系统资源，桌面端虽然是协程但也没有理由无上限）。
     */
    const val MAX_WINDOWS = 8

    /**
     * 默认提前 = 延后 5 分钟。
     *
     * 规则里的时段同时是读屏窗口：准点在结束时刻判定，往往「刚提醒就已经读不到数据」。
     * 正数表示延后，所以默认值是 +5 而不是 -5。
     */
    const val DEFAULT_OFFSET_MINUTES = 5

    /** 一个待注册的触发点 */
    data class Entry(
        /** 任务 id */
        val taskId: Long,
        /** 时段序号，用于拼出稳定且互不相同的 tag */
        val windowIndex: Int,
        /** 触发时刻（epoch millis） */
        val triggerAtMillis: Long,
    ) {
        /**
         * 排程标签。
         *
         * **必须带时段序号**：只带 taskId 的话，第二个时段会覆盖第一个，
         * 表现为「配了两个时段却只响一次」。
         */
        val tag: String get() = "remind:$taskId:$windowIndex"
    }

    /** 一个任务的完整排程结果 */
    data class Plan(
        val taskId: Long,
        val entries: List<Entry>,
        /** 被跳过的原因，供日志排查「为什么今天没响」 */
        val skipReason: String? = null,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /**
     * 规划单个任务今天的全部触发点。
     *
     * 不注册已过去的时刻：那会导致「刚打开应用就补响一次昨天该响的提醒」，
     * 用户看到的是莫名奇妙的通知。过点就当今天已经错过，等明天。
     *
     * @param nowMillis 当前时刻，便于测试注入
     * @param offsetMinutes 相对时段结束的偏移，-60..60；正数延后
     */
    fun planTask(
        task: TaskConfig,
        ruleSchedule: ScheduleRule?,
        nowMillis: Long = System.currentTimeMillis(),
        offsetMinutes: Int = task.windowOffsetMinutes,
    ): Plan {
        if (!task.enabled) return Plan(task.id, emptyList(), "task disabled")

        val schedule = resolveSchedule(task, ruleSchedule)
        val today = Instant.ofEpochMilli(nowMillis).atZone(Dates.zone).toLocalDate()
        if (!schedule.isDateActive(today)) {
            return Plan(task.id, emptyList(), "date not active today")
        }

        val windows = schedule.effectiveWindows.take(MAX_WINDOWS)
        if (windows.isEmpty()) return Plan(task.id, emptyList(), "no windows")

        val entries = windows.mapIndexedNotNull { index, window ->
            val trigger = triggerMillisFor(
                endMinute = window.endMinute,
                offsetMinutes = offsetMinutes,
                nowMillis = nowMillis,
                date = today,
            ) ?: return@mapIndexedNotNull null
            Entry(task.id, index, trigger)
        }

        // 全部过点时给出原因，否则日志里只看到「0 个闹钟」不知为何
        val reason = if (entries.isEmpty()) "all windows already passed today" else null
        return Plan(task.id, entries, reason)
    }

    /**
     * 任务的实际生效规则。
     *
     * 两种模式：
     * - [TimeSource.FOLLOW_RULE]：时段与周期取自**关联规则**。
     *   用户在规则里配了 09:00-09:30 和 18:00-18:30，任务就该在这两段之外才提醒
     * - [TimeSource.INDEPENDENT]：忽略规则时间，用任务自己配的时段。
     *   规则只负责取数，提醒时机由任务说了算
     *
     * 未关联规则（或规则已被删除）时回落到任务自身 schedule，老数据行为不变。
     */
    fun resolveSchedule(task: TaskConfig, ruleSchedule: ScheduleRule?): ScheduleRule =
        if (task.timeSource == TimeSource.INDEPENDENT) task.schedule
        else ruleSchedule ?: task.schedule

    /**
     * 计算今天的触发时刻（毫秒），时刻已过或时间非法时返回 null。
     *
     * 三个必须挡住的边界：
     *
     * 1. `endMinute` 超出 0..1439
     * 2. 偏移把时刻推出了当天（如 00:10 再提前 30 分钟）——跨天按「不在今天」处理，不注册
     * 3. 算出来的时刻已经过去
     */
    fun triggerMillisFor(
        endMinute: Int,
        offsetMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        date: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(Dates.zone).toLocalDate(),
    ): Long? {
        if (endMinute !in 0 until MINUTES_PER_DAY) return null
        val triggerMinuteOfDay = endMinute + offsetMinutes
        if (triggerMinuteOfDay !in 0 until MINUTES_PER_DAY) return null

        val trigger = LocalDateTime.of(
            date,
            LocalTime.of(triggerMinuteOfDay / 60, triggerMinuteOfDay % 60),
        )
        val millis = trigger.atZone(Dates.zone).toInstant().toEpochMilli()
        return if (millis <= nowMillis) null else millis
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

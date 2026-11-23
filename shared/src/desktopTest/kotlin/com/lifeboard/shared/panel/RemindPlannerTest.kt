package com.lifeboard.shared.panel

import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.core.util.Dates
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒时刻的规划规则（M4-4.5）。
 *
 * ## 为什么这组测试重要
 *
 * 这些规则原本写在 Android 的 `RemindScheduler` 里，只有手机端在跑。
 * 桌面端接入后，两端必须算出**完全一样**的时刻——同一个任务在手机上
 * 9:35 响、在电脑上 9:30 响，用户只会觉得这个软件坏了。
 *
 * 所以这里逐条钉住边界：偏移的正负、跨天、已过点、时段上限、时间来源。
 */
class RemindPlannerTest {

    // ───────────────────────── triggerMillisFor ─────────────────────────

    @Test
    fun offsetDelaysTrigger() {
        // 时段结束 09:30，偏移 +5 分钟 → 09:35 触发。
        // 为什么默认是延后而不是提前：时段同时是读屏窗口，
        // 准点在结束时刻判定往往「刚提醒就已经读不到数据」
        val now = at(8, 0)
        val trigger = RemindPlanner.triggerMillisFor(endMinute = 9 * 60 + 30, offsetMinutes = 5, nowMillis = now)
        assertNotNull(trigger)
        assertEquals(at(9, 35), trigger)
    }

    @Test
    fun negativeOffsetAdvanceTrigger() {
        val now = at(8, 0)
        val trigger = RemindPlanner.triggerMillisFor(endMinute = 9 * 60 + 30, offsetMinutes = -10, nowMillis = now)
        assertNotNull(trigger)
        assertEquals(at(9, 20), trigger)
    }

    @Test
    fun zeroOffsetTriggersExactlyAtWindowEnd() {
        val now = at(8, 0)
        val trigger = RemindPlanner.triggerMillisFor(endMinute = 9 * 60, offsetMinutes = 0, nowMillis = now)
        assertEquals(at(9, 0), trigger)
    }

    @Test
    fun pastTriggerIsDropped() {
        // 已经 10:00，时段 09:30 结束 + 5 分钟 = 09:35，已经过点。
        // 不补响是刻意的：刚打开应用就冒出一条「昨天该响的提醒」会让用户莫名其妙
        val now = at(10, 0)
        assertNull(RemindPlanner.triggerMillisFor(endMinute = 9 * 60 + 30, offsetMinutes = 5, nowMillis = now))
    }

    @Test
    fun offsetPushingOutOfDayIsDropped() {
        // 00:10 再往前 30 分钟 → 前一天 23:40。跨天按「不在今天」处理
        val now = at(0, 0)
        assertNull(RemindPlanner.triggerMillisFor(endMinute = 10, offsetMinutes = -30, nowMillis = now))
        // 23:50 再延后 30 分钟 → 次日 00:20，同样不注册
        assertNull(
            RemindPlanner.triggerMillisFor(
                endMinute = 23 * 60 + 50,
                offsetMinutes = 30,
                nowMillis = at(0, 0),
            ),
        )
    }

    @Test
    fun invalidEndMinuteIsRejected() {
        val now = at(0, 0)
        // 1440 是次日 00:00，不是当天的合法分钟数
        assertNull(RemindPlanner.triggerMillisFor(endMinute = 1440, offsetMinutes = 0, nowMillis = now))
        assertNull(RemindPlanner.triggerMillisFor(endMinute = -1, offsetMinutes = 0, nowMillis = now))
    }

    @Test
    fun boundaryMinutesAreAccepted() {
        // 00:00 与 23:59 是合法边界，不能因为「看起来像越界」被误拒
        assertNotNull(RemindPlanner.triggerMillisFor(
            endMinute = 0,
            offsetMinutes = 0,
            nowMillis = at(0, 0) - 60_000,
            date = LocalDate.now(),
        ))
        assertNotNull(RemindPlanner.triggerMillisFor(endMinute = 1439, offsetMinutes = 0, nowMillis = at(0, 0)))
    }

    // ───────────────────────── planTask ─────────────────────────

    @Test
    fun disabledTaskProducesNoEntries() {
        val task = task(enabled = false)
        val plan = RemindPlanner.planTask(task, ruleSchedule = null, nowMillis = at(0, 0))
        assertTrue(plan.isEmpty)
        assertEquals("task disabled", plan.skipReason)
    }

    @Test
    fun multipleWindowsProduceDistinctTags() {
        // 配了三个时段的规则。tag 必须各不相同，否则后一个会覆盖前一个，
        // 表现是「配了三段却只响一次」
        val rule = daily(9 * 60 to 9 * 60 + 30, 12 * 60 to 12 * 60 + 30, 18 * 60 to 18 * 60 + 30)
        val plan = RemindPlanner.planTask(task = task(), ruleSchedule = rule, nowMillis = at(0, 0))
        assertEquals(3, plan.entries.size)
        assertEquals(3, plan.entries.map { it.tag }.toSet().size)
        assertEquals(listOf("remind:7:0", "remind:7:1", "remind:7:2"), plan.entries.map { it.tag })
    }

    @Test
    fun independentTimeSourceIgnoresRuleWindows() {
        // 任务自己定时间时，规则里配的时段对提醒时机无效——
        // 规则只负责「什么时候能读数据」，不负责「什么时候该提醒」
        val rule = daily(9 * 60 to 9 * 60 + 30)
        val task = task(
            timeSource = TimeSource.INDEPENDENT,
            schedule = daily(20 * 60 to 20 * 60 + 30),
        )
        val plan = RemindPlanner.planTask(task, ruleSchedule = rule, nowMillis = at(0, 0))
        assertEquals(1, plan.entries.size)
        assertEquals(at(20, 35), plan.entries.single().triggerAtMillis)
    }

    @Test
    fun followRuleUsesRuleWindows() {
        val rule = daily(7 * 60 to 7 * 60 + 30)
        val task = task(
            timeSource = TimeSource.FOLLOW_RULE,
            // 任务自己这份是干扰项：跟随规则时不该被采用
            schedule = daily(20 * 60 to 20 * 60 + 30),
        )
        val plan = RemindPlanner.planTask(task, ruleSchedule = rule, nowMillis = at(0, 0))
        assertEquals(at(7, 35), plan.entries.single().triggerAtMillis)
    }

    @Test
    fun missingRuleFallsBackToOwnSchedule() {
        // 规则被删了但任务还指着那个 id。回落到任务自身 schedule，
        // 而不是「什么都不排」——后者会让用户觉得提醒悄悄消失了
        val task = task(timeSource = TimeSource.FOLLOW_RULE, schedule = daily(10 * 60 to 10 * 60))
        val plan = RemindPlanner.planTask(task, ruleSchedule = null, nowMillis = at(0, 0))
        assertEquals(1, plan.entries.size)
    }

    @Test
    fun windowsAreCappedAtLimit() {
        // 用户配了 12 段，只排前 8 段。无上限会让闹钟数量随配置爆炸
        val windows = (1..12).map { hour -> hour * 60 to hour * 60 + 30 }
        val plan = RemindPlanner.planTask(
            task = task(),
            ruleSchedule = daily(*windows.toTypedArray()),
            nowMillis = at(0, 0),
        )
        assertEquals(RemindPlanner.MAX_WINDOWS, plan.entries.size)
    }

    @Test
    fun allPassedReportsReason() {
        // 一天只剩最后一个时段，且已过点。要给出 skipReason，
        // 否则日志里只看到「0 个闹钟」不知为何
        val plan = RemindPlanner.planTask(
            task = task(),
            ruleSchedule = daily(9 * 60 to 9 * 60 + 30),
            nowMillis = at(23, 0),
        )
        assertTrue(plan.isEmpty)
        assertEquals("all windows already passed today", plan.skipReason)
    }

    @Test
    fun injectedDateControlsWeeklyActivation() {
        val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
        val mondayNow = at(monday, 8, 0)
        val weekly = ScheduleRule(
            windows = listOf(TimeWindow(9 * 60, 9 * 60 + 30)),
            repeatType = RepeatType.WEEKLY,
            weekdays = setOf(1),
        )
        val plan = RemindPlanner.planTask(task(), weekly, nowMillis = mondayNow)
        assertEquals(at(monday, 9, 35), plan.entries.single().triggerAtMillis)

        val sunday = monday.plusDays(6)
        val inactive = RemindPlanner.planTask(task(), weekly, nowMillis = at(sunday, 8, 0))
        assertTrue(inactive.isEmpty)
        assertEquals("date not active today", inactive.skipReason)
    }


    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(Dates.zone)
            .toInstant()
            .toEpochMilli()

    private fun daily(vararg windows: Pair<Int, Int>): ScheduleRule =
        ScheduleRule(windows = windows.map { (s, e) -> TimeWindow(s, e) })

    /** 今天的某个时刻（毫秒），按应用使用的时区算 */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
            .atZone(Dates.zone)
            .toInstant()
            .toEpochMilli()

    private fun task(
        enabled: Boolean = true,
        timeSource: TimeSource = TimeSource.FOLLOW_RULE,
        schedule: ScheduleRule = ScheduleRule.ALWAYS,
    ): TaskConfig = TaskConfig(
        id = 7L,
        title = "背单词",
        source = SourceRef.Builtin(
            key = "builtin.steps",
            displayName = "步数",
            bucket = MetricBucket.COUNT,
        ),
        metricBucket = MetricBucket.COUNT,
        threshold = 30.0,
        remindLevel = RemindLevel.WEAK,
        schedule = schedule,
        enabled = enabled,
        timeSource = timeSource,
        windowOffsetMinutes = 5,
    )
}

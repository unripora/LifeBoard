package com.lifeboard.server.chart

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
// readStreakSeries / SeriesStatus 已随图表业务迁入 shared（M3-3.2/3.7）。
// 本测试原在 :server，靠同包可见直接引用；:server 并入 :app 后需显式 import。
import com.lifeboard.shared.panel.SeriesStatus
import com.lifeboard.shared.panel.readRuleCompletionSeries
import com.lifeboard.shared.panel.readStreakSeries
import com.lifeboard.shared.panel.weightOf
import com.lifeboard.shared.panel.windowRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置业务序列的算法。
 *
 * 这些函数是纯的（输入记录与任务，输出序列），所以能脱离数据库做 JVM 单测。
 * 值得逐个测：它们算的是「用户看到的数字」，算错了不会报错，
 * 只会安静地给出一张看起来合理的错误图表。
 */
class BuiltinSeriesBuilderTest {

    private fun record(
        taskId: Long,
        date: String,
        completed: Boolean,
        score: Double = 1.0,
    ) = DailyRecord(
        taskId = taskId,
        date = date,
        value = 0.0,
        completed = completed,
        score = score,
    )

    private fun task(id: Long, title: String, weight: Double = 1.0) = TaskConfig(
        id = id,
        title = title,
        source = SourceRef.Builtin(
            key = "STEPS",
            displayName = "步数",
            bucket = MetricBucket.STEPS,
        ),
        metricBucket = MetricBucket.STEPS,
        threshold = 1.0,
        weight = weight,
    )

    @Test
    fun streakCountsConsecutiveCompletedDays() {
        val records = listOf(
            record(1, "2026-11-01", true),
            record(1, "2026-11-02", true),
            record(1, "2026-11-03", false),
            record(1, "2026-11-04", true),
            record(1, "2026-11-05", true),
        )
        val series = readStreakSeries(records, 1L, "2026-11-01", "2026-11-05", listOf(task(1, "喝水")))

        assertEquals(listOf(1.0, 2.0, 0.0, 1.0, 2.0), series.points.map { it.value })
        assertEquals(SeriesStatus.Ok, series.status)
    }

    @Test
    fun streakSkipsDaysWithoutRecord() {
        // 没被判定过的日子既不算断链也不出点：11-02 没有记录，
        // 说明那天没跑过判定（任务还没建、被停用），不是「没做到」。
        // 但它确实打断了「连续」，所以 11-03 从 1 重新起算
        val records = listOf(
            record(1, "2026-11-01", true),
            record(1, "2026-11-03", true),
        )
        val series = readStreakSeries(records, 1L, "2026-11-01", "2026-11-03", listOf(task(1, "喝水")))

        assertEquals(listOf("2026-11-01" to 1.0, "2026-11-03" to 1.0), series.points.map { it.date to it.value })
    }

    @Test
    fun streakRecordsZeroOnJudgedButIncompleteDay() {
        // 有记录且未达成是明确的「断了」，0 是有效数据，必须画出来
        val records = listOf(
            record(1, "2026-11-01", true),
            record(1, "2026-11-02", false),
            record(1, "2026-11-03", true),
        )
        val series = readStreakSeries(records, 1L, "2026-11-01", "2026-11-03", listOf(task(1, "喝水")))

        assertEquals(listOf(1.0, 0.0, 1.0), series.points.map { it.value })
    }

    @Test
    fun streakWithNoRecordsHasNoPoints() {
        // 一条记录都没有时不能画出一排 0：那是「连续 0 天」，而事实是「没有可判断的数据」。
        // 序列整体按 NoData 处理，由界面说明时间窗内没有数据
        val series = readStreakSeries(
            emptyList(),
            1L,
            "2026-11-01",
            "2026-11-07",
            listOf(task(1, "喝水")),
        )
        assertTrue(series.points.isEmpty())
        assertEquals(SeriesStatus.NoData, series.status)
    }

    @Test
    fun streakReportsUnavailableWhenTaskMissing() {
        val series = readStreakSeries(emptyList(), 99L, "2026-11-01", "2026-11-02", listOf(task(1, "喝水")))
        assertTrue(series.status is SeriesStatus.SourceUnavailable)
        assertTrue(series.points.isEmpty())
    }

    @Test
    fun ruleCompletionUsesRecordedOutcome() {
        val records = listOf(
            record(1, "2026-11-01", true),
            record(1, "2026-11-02", false),
            // 别的任务的记录不能混进来
            record(2, "2026-11-02", true),
        )
        val series = readRuleCompletionSeries(records, 1L, listOf(task(1, "喝水"), task(2, "走路")))

        assertEquals(listOf("2026-11-01" to 100.0, "2026-11-02" to 0.0), series.points.map { it.date to it.value })
    }

    @Test
    fun ruleCompletionSkipsDaysWithoutRecord() {
        // 没有记录说明那天任务不适用（停用、不在重复范围）。把它算成 0% 会冤枉用户
        val records = listOf(record(1, "2026-11-01", true))
        val series = readRuleCompletionSeries(records, 1L, listOf(task(1, "喝水")))

        assertEquals(1, series.points.size)
        assertEquals("2026-11-01", series.points[0].date)
    }

    @Test
    fun weightOfReturnsZeroForDeletedTask() {
        // 任务删了但记录还在：不该继续影响完成率分母
        val orphan = record(99, "2026-11-01", true)
        assertEquals(0.0, orphan.weightOf(listOf(task(1, "喝水"))), 0.0001)
    }

    @Test
    fun weightOfReadsTaskWeight() {
        val r = record(1, "2026-11-01", true)
        assertEquals(2.5, r.weightOf(listOf(task(1, "喝水", weight = 2.5))), 0.0001)
    }

    @Test
    fun windowRangeIsInclusiveAndCrossesMonthBoundary() {
        val (from, to) = windowRange("2026-11-03", 7)
        assertEquals("2026-10-28", from)
        assertEquals("2026-11-03", to)
    }

    @Test
    fun windowRangeSingleDayIsToday() {
        val (from, to) = windowRange("2026-11-10", 1)
        assertEquals("2026-11-10", from)
        assertEquals("2026-11-10", to)
    }

    @Test
    fun windowRangeHandlesLeapYear() {
        val (from, _) = windowRange("2028-03-01", 2)
        assertEquals("2028-02-29", from)
    }
}

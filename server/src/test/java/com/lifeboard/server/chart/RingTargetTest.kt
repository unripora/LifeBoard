package com.lifeboard.server.chart

import com.lifeboard.server.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 环形进度的目标值查找。
 *
 * 目标值是「用户设过的数字」，不能凭空造。所以这段逻辑的核心不是「算出什么」，
 * 而是**什么时候拒绝算**：没有对应规则、多条规则阈值冲突，都必须返回 null。
 */
class RingTargetTest {

    private fun task(
        id: Long,
        sourceType: String,
        metricBucket: String = "STEPS",
        threshold: Double = 8000.0,
    ) = TaskEntity(
        id = id,
        title = "任务 $id",
        sourceType = sourceType,
        metricBucket = metricBucket,
        threshold = threshold,
        weight = 1.0,
        remindLevel = 0,
        windowStartMinute = 0,
        windowEndMinute = 24 * 60 - 1,
        enabled = true,
    )

    @Test
    fun findsSingleMatchingTaskThreshold() {
        val tasks = listOf(task(1, "STEPS", threshold = 8000.0))
        assertEquals(8000.0, resolveRingTarget("STEPS", null, tasks)!!, 0.0001)
    }

    @Test
    fun returnsNullWhenNoTaskMatches() {
        // 没有对应规则就没有「目标」这回事，不能拿默认值凑一个
        val tasks = listOf(task(1, "SLEEP"))
        assertNull(resolveRingTarget("STEPS", null, tasks))
    }

    @Test
    fun returnsNullForBlankSource() {
        assertNull(resolveRingTarget("", null, listOf(task(1, "STEPS"))))
    }

    @Test
    fun sameThresholdAcrossTasksIsTreatedAsSingleTarget() {
        // 两条任务都要求 8000 步：目标仍然是明确的 8000，不该判为冲突
        val tasks = listOf(
            task(1, "STEPS", threshold = 8000.0),
            task(2, "STEPS", threshold = 8000.0),
        )
        assertEquals(8000.0, resolveRingTarget("STEPS", null, tasks)!!, 0.0001)
    }

    @Test
    fun conflictingThresholdsYieldNull() {
        // 「走 5000 步」和「走 10000 步」并存时，取最大或平均都是编造用户没设过的目标
        val tasks = listOf(
            task(1, "STEPS", threshold = 5000.0),
            task(2, "STEPS", threshold = 10000.0),
        )
        assertNull(resolveRingTarget("STEPS", null, tasks))
    }

    @Test
    fun nonPositiveThresholdYieldsNull() {
        // 阈值为 0 或负数不构成「目标」，按百分比算出来会是除零或负进度
        val tasks = listOf(task(1, "STEPS", threshold = 0.0))
        assertNull(resolveRingTarget("STEPS", null, tasks))
    }

    @Test
    fun metricKeyFiltersTasksOfSameSource() {
        // 同一个插件源下有步数与睡眠两个指标：目标必须按指标区分，
        // 否则睡眠进度会用步数的阈值来算
        val tasks = listOf(
            task(1, "PLUGIN_p:steps", "steps", 8000.0),
            task(2, "PLUGIN_p:sleep", "sleep", 480.0),
        )
        assertEquals(8000.0, resolveRingTarget("PLUGIN_p:steps", "steps", tasks)!!, 0.0001)
        assertEquals(480.0, resolveRingTarget("PLUGIN_p:sleep", "sleep", tasks)!!, 0.0001)
    }

    @Test
    fun ignoresTasksOfOtherSources() {
        val tasks = listOf(
            task(1, "STEPS", threshold = 8000.0),
            task(2, "SLEEP", "SLEEP", threshold = 480.0),
        )
        assertEquals(8000.0, resolveRingTarget("STEPS", null, tasks)!!, 0.0001)
    }

    @Test
    fun disabledTaskStillCountsAsTarget() {
        // 停用只影响「是否判定」，不改变用户设过的目标。
        // 若因停用而丢掉目标，环形图会莫名其妙失去百分比
        val disabled = task(1, "STEPS", threshold = 8000.0).copy(enabled = false)
        assertEquals(8000.0, resolveRingTarget("STEPS", null, listOf(disabled))!!, 0.0001)
    }

    @Test
    fun emptyTaskListYieldsNull() {
        assertNull(resolveRingTarget("STEPS", null, emptyList()))
    }
}

package com.lifeboard.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指标声明的校验与解析。
 *
 * 这些规则决定「同一行数据会不会被两种口径解读」，属于插件加载的准入条件，必须有单测兜住。
 */
class PluginMetricsTest {

    private val steps = MetricSpec("steps", MetricBucket.STEPS, "步数")
    private val sleep = MetricSpec("sleep_minutes", MetricBucket.SLEEP_MINUTES, "睡眠时长")

    @Test
    fun emptyList_isValid() {
        // 允许不产数的插件：只提供登录管理
        assertTrue(PluginMetrics.validate(emptyList()).isEmpty())
        assertTrue(PluginMetrics.isValid(emptyList()))
    }

    @Test
    fun distinctKeys_areValid() {
        assertTrue(PluginMetrics.validate(listOf(steps, sleep)).isEmpty())
    }

    @Test
    fun sameKeyDifferentBuckets_isConflict() {
        val conflict = MetricSpec("steps", MetricBucket.COUNT, "步数")
        val problems = PluginMetrics.validate(listOf(steps, conflict))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("steps"))
        assertTrue(problems.single().contains("STEPS"))
        assertTrue(problems.single().contains("COUNT"))
    }

    @Test
    fun sameKeySameBucket_isDuplicate() {
        val problems = PluginMetrics.validate(listOf(steps, steps))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("重复"))
    }

    @Test
    fun blankMetricKey_isRejected() {
        val blank = MetricSpec("", MetricBucket.STEPS, "空")
        val problems = PluginMetrics.validate(listOf(blank))

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("非法"))
    }

    @Test
    fun metricKeyWithSeparator_isRejected() {
        // `:` 是插件 key 的分隔符，含了就无法往返解析
        val bad = MetricSpec("a:b", MetricBucket.STEPS, "含分隔符")
        assertFalse(PluginMetrics.isValid(listOf(bad)))
    }

    @Test
    fun resolve_fillsBucketFromDeclaration() {
        val resolved = PluginMetrics.resolve(
            declared = listOf(steps, sleep),
            produced = listOf(PluginMetric("steps", 8123.0)),
        )

        assertEquals(1, resolved.accepted.size)
        assertEquals(MetricBucket.STEPS, resolved.accepted.single().first.bucket)
        assertEquals(8123.0, resolved.accepted.single().second.value, 0.0)
        assertTrue(resolved.rejected.isEmpty())
    }

    @Test
    fun resolve_rejectsUndeclaredKey() {
        // 未声明就猜量纲会写进一行没人能解释的数据，因此一律拒绝
        val resolved = PluginMetrics.resolve(
            declared = listOf(steps),
            produced = listOf(
                PluginMetric("steps", 1.0),
                PluginMetric("heart_rate", 70.0),
            ),
        )

        assertEquals(1, resolved.accepted.size)
        assertEquals("heart_rate", resolved.rejected.single().metricKey)
    }

    @Test
    fun resolve_emptyProduced_isEmpty() {
        assertTrue(PluginMetrics.resolve(listOf(steps), emptyList()).isEmpty)
    }
}

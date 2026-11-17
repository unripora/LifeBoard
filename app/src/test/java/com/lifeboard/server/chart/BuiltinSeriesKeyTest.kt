package com.lifeboard.server.chart

import com.lifeboard.shared.dashboard.BuiltinSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置序列的识别规则。
 *
 * 「这条序列查 `metric` 表还是从任务记录里算」全靠 `metricKey` 判断。
 * 判错不会崩，只会安静地画一条空线，所以这条规则必须被钉住：
 * - 每个内置序列的键都能反查回自己
 * - 普通指标的键不会被误认成内置序列
 * - 需要任务的序列被正确标出来（配置界面据此弹出任务选择）
 */
class BuiltinSeriesKeyTest {

    @Test
    fun everyBuiltinSeriesResolvesFromItsKey() {
        BuiltinSeries.entries.forEach { builtin ->
            assertEquals(builtin, BuiltinSeries.fromMetricKey(builtin.metricKey))
        }
    }

    @Test
    fun keysAreUnique() {
        val keys = BuiltinSeries.entries.map { it.metricKey }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun ordinaryMetricKeysAreNotBuiltin() {
        // 插件与内置源的真实指标键不能撞上 BUILTIN_ 前缀
        assertNull(BuiltinSeries.fromMetricKey("STEPS"))
        assertNull(BuiltinSeries.fromMetricKey("steps"))
        assertNull(BuiltinSeries.fromMetricKey(null))
        assertNull(BuiltinSeries.fromMetricKey(""))
    }

    @Test
    fun taskBoundSeriesAreMarkedAsNeedingTask() {
        // 连击与单条规则完成率没有任务就算不出来，配置层必须能提前拦住
        assertTrue(BuiltinSeries.STREAK.needsTask)
        assertTrue(BuiltinSeries.RULE_COMPLETION.needsTask)
        assertTrue(!BuiltinSeries.DAILY_SCORE.needsTask)
        assertTrue(!BuiltinSeries.MANUAL_MARK_COUNT.needsTask)
    }

    @Test
    fun everySeriesDeclaresLabelAndUnit() {
        // 空标签或空单位会让图例和坐标轴出现看不懂的空位
        BuiltinSeries.entries.forEach { builtin ->
            assertTrue("${builtin.name} 缺标签", builtin.label.isNotBlank())
            assertTrue("${builtin.name} 缺单位", builtin.unit.isNotBlank())
            assertTrue("${builtin.name} 的键缺前缀", builtin.metricKey.startsWith("BUILTIN_"))
        }
    }
}

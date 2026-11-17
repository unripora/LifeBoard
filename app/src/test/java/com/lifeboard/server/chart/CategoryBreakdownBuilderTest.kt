package com.lifeboard.server.chart

import com.lifeboard.shared.panel.buildCategorySlices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类占比的整形规则（`buildCategorySlices`）。
 *
 * 测的是**真实实现**，不是复刻一份规则——复刻的测试在实现改动时不会失败，
 * 等于没测。这段规则的价值在于：错了不会抛异常，只会画出一张比例错误的饼。
 */
class CategoryBreakdownBuilderTest {

    private fun raw(vararg items: Triple<String, String, Double>) = items.toList()

    private fun item(key: String, minutes: Double, label: String = key) =
        Triple(key, label, minutes)

    @Test
    fun keepsAllWhenFewerThanLimit() {
        val result = buildCategorySlices(raw(item("A", 10.0), item("B", 5.0)), 5)
        assertEquals(listOf("A", "B"), result.map { it.label })
    }

    @Test
    fun foldsRemainderIntoOther() {
        val result = buildCategorySlices(
            raw(item("A", 10.0), item("B", 5.0), item("C", 3.0)),
            2,
        )
        assertEquals(3, result.size)
        assertEquals("其他", result.last().label)
        assertEquals(3.0, result.last().value, 0.0001)
    }

    @Test
    fun sortsByValueDescending() {
        // 「前 N」必须是「最大的 N 个」，不能依赖输入顺序。
        // 第三项会被归入「其他」，所以只断言前两项
        val result = buildCategorySlices(
            raw(item("small", 1.0), item("big", 100.0), item("mid", 50.0)),
            2,
        )
        assertEquals(listOf("big", "mid", "其他"), result.map { it.label })
        assertEquals(1.0, result.last().value, 0.0001)
    }

    @Test
    fun zeroAndNegativeValuesAreExcluded() {
        // 负值会让扇区角度反向，0 值生成不可见扇区
        val result = buildCategorySlices(
            raw(item("A", 10.0), item("zero", 0.0), item("neg", -5.0)),
            5,
        )
        assertEquals(listOf("A"), result.map { it.label })
    }

    @Test
    fun allZeroYieldsEmpty() {
        assertTrue(buildCategorySlices(raw(item("A", 0.0), item("B", 0.0)), 5).isEmpty())
    }

    @Test
    fun totalIsPreservedAcrossFolding() {
        // 折叠不能改变总额：饼图的比例完全依赖这个总和
        val input = raw(item("A", 10.0), item("B", 5.0), item("C", 3.0), item("D", 2.0))
        val before = input.sumOf { it.third }
        val after = buildCategorySlices(input, 2).sumOf { it.value }
        assertEquals(before, after, 0.0001)
    }

    @Test
    fun limitOfOneStillProducesOtherSlice() {
        val result = buildCategorySlices(raw(item("A", 10.0), item("B", 5.0)), 1)
        assertEquals(2, result.size)
        assertEquals("A", result[0].label)
        assertEquals("其他", result[1].label)
    }

    @Test
    fun blankLabelFallsBackToKey() {
        // 采集时没取到应用名，退回包名至少能认出来源，而不是显示一个空标签
        val result = buildCategorySlices(raw(Triple("com.a", "", 10.0)), 5)
        assertEquals("com.a", result.single().label)
    }

    @Test
    fun nonPositiveTopNFallsBackToOne() {
        // topN 配成 0 或负数不能让结果为空，也不能抛异常
        val result = buildCategorySlices(raw(item("A", 10.0), item("B", 5.0)), 0)
        assertEquals(2, result.size)
        assertEquals("A", result[0].label)
    }
}

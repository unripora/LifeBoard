package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.UnsupportedNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单字段限额测试（对应计划 5.3）。
 *
 * 两类字段的处理必须相反：
 * - **展示文本**截断（少显示几个字，语义不坏）
 * - **机器字段**丢弃整条（截断会产生指向不存在目标的 id，比丢弃更难排查）
 */
class JsPayloadLimitTest {

    private fun parse(json: String) = JsTypeConverter.toUiTree(json)

    @Test
    fun oversizedDisplayTextIsTruncated() {
        val huge = "题".repeat(40_000) // 约 120 KB（UTF-8 每字 3 字节）
        val json = """{"type":"column","children":[{"type":"text","body":"$huge"}]}"""
        val root = parse(json).root as ColumnNode
        val text = root.children.single() as TextNode
        val body = (text.body as Bound.Static).value
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024, "展示文本应被截断到 64 KiB 内")
        assertTrue(body.length < huge.length, "确实发生了截断")
        assertTrue(body.isNotEmpty() && body.all { it == '题' }, "截断点必须落在字符边界")
    }

    @Test
    fun oversizedMachineIdDropsNode() {
        val hugeId = "x".repeat(5_000) // 超过 4 KiB
        val json = """{"type":"column","children":[
            {"type":"text","id":"$hugeId","body":"正常内容"},
            {"type":"text","body":"保留这条"}
        ]}"""
        val root = parse(json).root as ColumnNode
        assertEquals(1, root.children.size, "超限 id 的节点应被丢弃")
        assertEquals("保留这条", ((root.children.single() as TextNode).body as Bound.Static).value)
    }

    @Test
    fun oversizedActionIdDropsButton() {
        val huge = "a".repeat(5_000)
        val json = """{"type":"column","children":[{"type":"button","label":"点我","actionId":"$huge"}]}"""
        val root = parse(json).root as ColumnNode
        assertTrue(root.children.single() is UnsupportedNode, "非法 actionId 的按钮应退化为占位")
    }

    @Test
    fun normalFieldsPassThroughUntouched() {
        val json = """{"type":"column","children":[
            {"type":"button","label":"同步","actionId":"sync_now"},
            {"type":"text","body":"短文本"}
        ]}"""
        val root = parse(json).root as ColumnNode
        assertEquals(2, root.children.size)
        assertEquals("短文本", ((root.children[1] as TextNode).body as Bound.Static).value)
    }

    @Test
    fun arrayRootKeepsUnsupportedPlaceholder() {
        val huge = "a".repeat(5_000)
        val json = """[{"type":"button","label":"x","actionId":"$huge"}]"""
        val result = parse(json)
        // 按钮退化为占位而不是静默消失：用户至少能看到「这里本来有东西」
        val root = result.root as ColumnNode
        assertEquals(1, root.children.size)
        assertTrue(root.children.single() is UnsupportedNode)
    }
}

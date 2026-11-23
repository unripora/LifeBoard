package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.InputBoxBlock
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 区块顺序的纯函数测试。
 *
 * 覆盖四条规则：id 兜底、锁定块不动、新块排最后、拖动落点换算。
 * 这些规则决定了用户调好的顺序在插件刷新后是否还能保住，值得钉死。
 */
class PluginBlockOrderTest {

    @Test
    fun blockKeyPrefersPluginIdAndFallsBackToTypeAndIndex() {
        assertEquals("steps", blockKey(StatusCardBlock("步数", "1000", id = "steps"), 3))
        assertEquals("statusCard#3", blockKey(StatusCardBlock("步数", "1000"), 3))
        assertEquals("text#0", blockKey(TextBlock("说明"), 0))
    }

    @Test
    fun interactiveBlocksAreNotReorderable() {
        assertFalse(isReorderableBlock(InputBoxBlock("send")))
        assertTrue(isReorderableBlock(TextBlock("说明")))
    }

    @Test
    fun savedOrderReordersCardsButKeepsInputInPlace() {
        val blocks = listOf(
            TextBlock("A", id = "a"),
            TextBlock("B", id = "b"),
            InputBoxBlock("send", id = "input"),
            TextBlock("C", id = "c"),
        )

        val ordered = applyBlockOrder(blocks, listOf("c", "a", "b"))

        assertEquals("C", (ordered[0] as TextBlock).title)
        assertEquals("A", (ordered[1] as TextBlock).title)
        assertEquals("send", (ordered[2] as InputBoxBlock).actionId)
        assertEquals("B", (ordered[3] as TextBlock).title)
    }

    @Test
    fun newBlocksGoLastAndMissingOnesAreIgnored() {
        val blocks = listOf(
            TextBlock("A", id = "a"),
            TextBlock("B", id = "b"),
            TextBlock("新增", id = "new"),
        )

        // 老块按保存顺序，新块排最后；"gone" 已不存在，忽略
        val ordered = applyBlockOrder(blocks, listOf("b", "a", "gone"))

        assertEquals(listOf("B", "A", "新增"), ordered.map { (it as TextBlock).title })
    }

    @Test
    fun moveBlockSwapsOnlyReorderableSlots() {
        val blocks = listOf(
            TextBlock("A", id = "a"),
            InputBoxBlock("send", id = "input"),
            TextBlock("B", id = "b"),
        )

        // 把 A 拖到 B 的位置：输入框保持第 2 位
        val moved = moveBlock(blocks, from = 0, to = 2)
        assertEquals(listOf("B", "send", "A"), moved.map {
            when (it) {
                is TextBlock -> it.title
                is InputBoxBlock -> it.actionId
                else -> ""
            }
        })
    }

    @Test
    fun droppingOnLockedBlockPicksNearestReorderableSlot() {
        val blocks = listOf(
            TextBlock("A", id = "a"),
            InputBoxBlock("send", id = "input"),
            TextBlock("B", id = "b"),
        )

        // 目标落在输入框（下标 1）上，取最近的 B（下标 2）
        val moved = moveBlock(blocks, from = 0, to = 1)
        assertEquals("B", (moved[0] as TextBlock).title)
        assertEquals("A", (moved[2] as TextBlock).title)
    }

    @Test
    fun orderKeysOnlyContainReorderableBlocks() {
        val blocks = listOf(
            TextBlock("A", id = "a"),
            InputBoxBlock("send", id = "input"),
            TextBlock("B"),
        )

        assertEquals(listOf("a", "text#2"), blockOrderKeys(blocks))
    }
}

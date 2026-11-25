package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SelectOption
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.Bound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 节点顺序的纯函数测试。
 *
 * 覆盖五条规则：id 兜底、锁定节点不动、新节点排最后、拖动落点换算、
 * **旧排序 key 仍然生效**。最后一条最容易被忽略：这些 key 是用户排序偏好的
 * 持久化形式，改了字符串就等于让所有人的自定义顺序静默失效。
 */
class PluginBlockOrderTest {

    private fun text(title: String, id: String? = null) = TextNode(title = title, id = id)

    private fun input(actionId: String, id: String? = null) = InputNode(actionId = actionId, id = id)

    @Test
    fun nodeKeyPrefersPluginIdAndFallsBackToTypeAndIndex() {
        assertEquals("steps", nodeKey(StatusCardNode("步数", Bound.Static("1000"), id = "steps"), 3))
        assertEquals("statusCard#3", nodeKey(StatusCardNode("步数", Bound.Static("1000")), 3))
        assertEquals("text#0", nodeKey(text("说明"), 0))
    }

    /**
     * 旧区块时代写进设置的 key 必须继续被认出来。
     *
     * 老用户存的是 `statusCard#0` / `text#2` 这种，若把标签改成
     * `StatusCardNode` 之类，顺序会静默重置——且没有任何报错。
     */
    @Test
    fun legacyTypeTagsArePreserved() {
        assertEquals("text#0", nodeKey(text("说明"), 0))
        assertEquals("statusCard#1", nodeKey(StatusCardNode("标签", Bound.Static("值")), 1))
        assertEquals("inputBox#2", nodeKey(input("send"), 2))
        assertEquals("messageList#3", nodeKey(com.lifeboard.plugin.api.ListNode(), 3))
        assertEquals("customImage#4", nodeKey(com.lifeboard.plugin.api.ImageNode("data:,x"), 4))
    }

    @Test
    fun interactiveNodesAreNotReorderable() {
        assertFalse(isReorderableNode(input("send")))
        assertFalse(isReorderableNode(SelectNode("pick", listOf(SelectOption("a")))))
        assertTrue(isReorderableNode(text("说明")))
    }

    @Test
    fun lockedNodeIsNotReorderable() {
        // 插件可以显式锁定某块（如紧贴图表的说明），锁了就不该被拖走
        assertFalse(isReorderableNode(text("说明", id = "note").copy(locked = true)))
    }

    @Test
    fun containerIsReorderableButItsChildrenAreNotCounted() {
        val container = ColumnNode(
            children = listOf(text("内层 A"), text("内层 B")),
            id = "group",
        )
        // 容器整块可拖；它的 children 出现在 rootChildren 之外，不参与排序
        assertTrue(isReorderableNode(container))
        assertEquals(listOf("内层 A", "内层 B"), (container.children.map { (it as TextNode).title }))
    }

    @Test
    fun rootChildrenOnlyReturnsTopLevelCards() {
        val root = ColumnNode(children = listOf(text("A"), input("send"), text("B")))
        assertEquals(3, rootChildren(root).size)
        // 根不是 column 时没有可拖的东西
        assertTrue(rootChildren(text("单个节点")).isEmpty())
        assertTrue(rootChildren(null).isEmpty())
    }

    @Test
    fun savedOrderReordersCardsButKeepsInputInPlace() {
        val children = listOf(
            text("A", id = "a"),
            text("B", id = "b"),
            input("send", id = "input"),
            text("C", id = "c"),
        )

        val ordered = applyNodeOrder(children, listOf("c", "a", "b"))

        assertEquals("C", (ordered[0] as TextNode).title)
        assertEquals("A", (ordered[1] as TextNode).title)
        // 锁定节点位置完全不动
        assertEquals("send", (ordered[2] as InputNode).actionId)
        assertEquals("B", (ordered[3] as TextNode).title)
    }

    @Test
    fun newNodesGoLastAndMissingOnesAreIgnored() {
        val children = listOf(
            text("A", id = "a"),
            text("B", id = "b"),
            text("新增", id = "new"),
        )

        // 老节点按保存顺序，新节点排最后；"gone" 已不存在，忽略
        val ordered = applyNodeOrder(children, listOf("b", "a", "gone"))

        assertEquals(listOf("B", "A", "新增"), ordered.map { (it as TextNode).title })
    }

    @Test
    fun moveNodeSwapsOnlyReorderableSlots() {
        val children = listOf(
            text("A", id = "a"),
            input("send", id = "input"),
            text("B", id = "b"),
        )

        // 把 A 拖到 B 的位置：输入框保持第 2 位
        val moved = moveNode(children, from = 0, to = 2)
        assertEquals(listOf("B", "send", "A"), moved.map {
            when (it) {
                is TextNode -> it.title
                is InputNode -> it.actionId
                else -> ""
            }
        })
    }

    @Test
    fun droppingOnLockedNodePicksNearestReorderableSlot() {
        val children = listOf(
            text("A", id = "a"),
            input("send", id = "input"),
            text("B", id = "b"),
        )

        // 目标落在输入框（下标 1）上，取最近的 B（下标 2）
        val moved = moveNode(children, from = 0, to = 1)
        assertEquals("B", (moved[0] as TextNode).title)
        assertEquals("A", (moved[2] as TextNode).title)
    }

    @Test
    fun orderKeysOnlyContainReorderableNodes() {
        val children = listOf(
            text("A", id = "a"),
            input("send", id = "input"),
            text("B"),
        )

        assertEquals(listOf("a", "text#2"), nodeOrderKeys(children))
    }

    @Test
    fun withRootChildrenKeepsNonColumnRootUnchanged() {
        val leaf = text("单个节点")
        assertNull(withRootChildren(null, emptyList()))
        // 根不是 column 时硬塞 children 会改变插件布局意图，所以原样返回
        assertEquals(leaf, withRootChildren(leaf, listOf(text("X"))))
    }
}

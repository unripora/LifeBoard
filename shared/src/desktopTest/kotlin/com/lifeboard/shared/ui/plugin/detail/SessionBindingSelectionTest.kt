package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PickerNode
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBindingSelectionTest {
    private fun tree(vararg keys: String): PluginNode = ColumnNode(children = keys.map {
        ChatNode(messages = Bound.Ref("session:$it:messages"))
    })

    @Test
    fun refreshTreeWithUniqueNewKeySwitchesFromActiveSession() {
        val requested = sessionBindingRequirements(tree("b"))
        assertEquals("b", requested.selectKey(activeKey = "a", isActive = true))
        assertEquals("b", requested.selectKey(activeKey = "b", isActive = true))
    }

    @Test
    fun duplicateBindingsStillExpressOneRequestedSession() {
        val root = ColumnNode(children = listOf(
            ChatNode(messages = Bound.Ref("session:b:messages")),
            TextNode(body = Bound.Ref("session:b:status")),
            InputNode("send", submitTo = BindingRef("session", "b", "input")),
        ))
        val requested = sessionBindingRequirements(root)
        assertEquals(setOf("b"), requested.sessionKeys)
        assertEquals("b", requested.selectKey(activeKey = "a", isActive = true))
    }

    @Test
    fun multipleDisplayedSessionsRetainActiveKeyRegardlessOfTreeOrder() {
        for (root in listOf(tree("b", "a"), tree("a", "b"), tree("b", "c"))) {
            assertEquals("a", sessionBindingRequirements(root).selectKey("a", isActive = true))
        }
    }

    @Test
    fun inactiveControllerStartsFirstSessionButDoesNotReuseStaleActiveKey() {
        assertEquals("b", sessionBindingRequirements(tree("b", "c")).selectKey("a", isActive = false))
    }

    @Test
    fun bindingInsideStaticListParticipatesInProductionSelection() {
        val root = ListNode(items = Bound.Static(listOf(listOf(
            InputNode("send", submitTo = BindingRef("session", "b", "input")),
        ))))
        assertEquals("b", sessionBindingRequirements(root).selectKey("a", isActive = true))
    }

    @Test
    fun listOnlyRegistersSourceWithoutInventingSessionKey() {
        val requested = sessionBindingRequirements(PickerNode(
            actionId = "pick",
            candidates = Bound.Ref("sessionList::items"),
        ))
        assertTrue(requested.hasSessionList)
        assertTrue(requested.sessionKeys.isEmpty())
        assertNull(requested.selectKey("a", isActive = true))
        assertNull(sessionBindingRequirements(null).selectKey("", isActive = false))
    }
}

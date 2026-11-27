package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SelectOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginSelectionTest {
    private fun node(
        multiple: Boolean = false,
        selected: List<String> = listOf("a"),
        submitLabel: String = "确认",
    ) = SelectNode(
        actionId = "select",
        options = listOf(SelectOption("a"), SelectOption("b"), SelectOption("c")),
        multiple = multiple,
        selectedValues = selected,
        submitLabel = submitLabel,
    )

    @Test
    fun controlledSingleSelectionWithLabelUsesVisibleDraftAndConfirmation() {
        val node = node()
        assertTrue(selectNeedsConfirmation(node), "受控单选也必须显示确认按钮")
        val initial = selectDisplayedValues(node, draft = null)
        assertEquals(setOf("a"), initial)

        val change = selectOption(node, initial, "b")
        assertNull(change.payload, "点击只改草稿，确认前不能回传")
        val displayed = selectDisplayedValues(node, change.selected)
        assertEquals(setOf("b"), displayed)
        assertEquals("b", selectionPayload(displayed, node.multiple))
        assertEquals(setOf("c"), selectDisplayedValues(node.copy(selectedValues = listOf("c")), null))
    }

    @Test
    fun controlledMultipleSelectionStillSubmitsImmediatelyWithLabel() {
        val node = node(multiple = true)
        assertFalse(selectNeedsConfirmation(node))
        val change = selectOption(node, selectDisplayedValues(node, null), "b")
        assertEquals("[\"a\",\"b\"]", change.payload)
        assertEquals(setOf("a"), selectDisplayedValues(node, change.selected), "即时受控仍以插件回显为准")
        assertEquals("[]", selectOption(node, setOf("a"), "a").payload)
    }

    @Test
    fun uncontrolledMultipleSelectionKeepsDraftUntilConfirmation() {
        val node = node(multiple = true, selected = emptyList(), submitLabel = "")
        assertTrue(selectNeedsConfirmation(node))
        val first = selectOption(node, selectDisplayedValues(node, null), "b")
        val second = selectOption(node, selectDisplayedValues(node, first.selected), "a")
        assertNull(first.payload)
        assertNull(second.payload)
        assertEquals("[\"a\",\"b\"]", selectionPayload(second.selected, node.multiple))
    }

    @Test
    fun singleSelectionWithoutLabelSubmitsRawValueImmediately() {
        for (selected in listOf(emptyList(), listOf("a"))) {
            val node = node(selected = selected, submitLabel = "")
            assertFalse(selectNeedsConfirmation(node))
            assertEquals("b", selectOption(node, selectDisplayedValues(node, null), "b").payload)
        }
    }

    @Test
    fun sharedSelectAndPickerPayloadEscapesAllJsonStringCharacters() {
        val values = setOf("quote\"", "slash\\", "line\nfeed", "tab\t", "control\u0001", "中文,值")
        val payload = selectionPayload(values, multiple = true)
        val decoded = Json.parseToJsonElement(payload).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(values.sorted(), decoded)
        assertEquals("[]", selectionPayload(emptySet(), multiple = true))
        assertEquals("quote\"", selectionPayload(setOf("quote\""), multiple = false))
    }
}

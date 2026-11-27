package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.SelectNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Select 与 Picker 共用回传契约；标准序列化负责引号、反斜杠与控制字符转义。 */
internal fun selectionPayload(values: Set<String>, multiple: Boolean): String =
    if (multiple) Json.encodeToString(values.sorted()) else values.firstOrNull().orEmpty()

/** 受控多选沿用即时提交；单选显式声明按钮时，无论受控与否都先保留草稿。 */
internal fun selectNeedsConfirmation(node: SelectNode): Boolean =
    if (node.multiple) node.selectedValues.isEmpty() else node.submitLabel.isNotBlank()

internal fun selectDisplayedValues(node: SelectNode, draft: Set<String>?): Set<String> {
    val controlled = node.selectedValues.isNotEmpty()
    val values = if (controlled && !selectNeedsConfirmation(node)) {
        node.selectedValues.toSet()
    } else {
        draft ?: node.selectedValues.toSet()
    }
    return values.intersect(node.options.map { it.value }.toSet())
}

internal data class SelectChange(val selected: Set<String>, val payload: String?)

internal fun selectOption(node: SelectNode, selected: Set<String>, value: String): SelectChange {
    val next = when {
        !node.multiple -> setOf(value)
        value in selected -> selected - value
        else -> selected + value
    }
    return SelectChange(
        selected = next,
        payload = if (selectNeedsConfirmation(node)) null else selectionPayload(next, node.multiple),
    )
}

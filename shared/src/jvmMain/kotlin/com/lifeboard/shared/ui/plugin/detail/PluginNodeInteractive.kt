package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.ToggleNode
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 按钮。
 *
 * [actionId] 为空时渲染成**禁用态**而不是丢弃：丢弃会让插件「少了一个按钮」
 * 却不自知，禁用至少看得见。
 *
 * 按钮不套卡片：卡片里再嵌一排按钮会显得像「一个可以整块点的区域」，
 * 而实际只有按钮本身可点。
 */
@Composable
internal fun RenderButton(node: ButtonNode, context: RenderContext) {
    val enabled = node.enabled && !context.busy && !node.actionId.isNullOrBlank()
    val onClick: () -> Unit = { node.actionId?.let { context.onAction(it, node.payload) } }
    if (node.primary) {
        Button(onClick = onClick, enabled = enabled) { Text(node.label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(node.label) }
    }
}

/**
 * 文本输入。
 *
 * ## 两条提交路径
 *
 * - 声明了 `submitTo`：输入直接投给绑定数据源（会话就是这么收输入的），
 *   **不进** `onAction`——那两种语义不同：一个是「一次插件调用」，
 *   另一个是「投给常驻会话」。
 * - 没有 `submitTo`：走 `onAction`，插件在下一次 `detailUi` 里体现这条输入。
 */
@Composable
internal fun RenderInput(node: InputNode, context: RenderContext) {
    // 按 actionId 记忆输入内容：同一次页面停留期间重绘不该清空用户正在打的字
    var text by remember(node.actionId) { mutableStateOf("") }
    val enabled = (node.enabled as? Bound.Static)?.value ?: true
    val canInteract = enabled && !context.busy
    val canSubmit = canInteract && text.isNotBlank()

    fun submit() {
        val content = text.trim()
        if (content.isEmpty()) return
        val target = node.submitTo
        if (target != null) {
            context.onSessionSubmit(target.key, content)
        } else {
            node.actionId?.let { context.onAction(it, content) }
        }
        text = ""
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(node.placeholder) },
            enabled = canInteract,
            maxLines = if (node.multiline) 4 else 1,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = ::submit, enabled = canSubmit) {
            Text(node.submitLabel)
        }
    }
}

/**
 * 下拉选择。
 *
 * ## value 回传格式
 *
 * - 单选：直接回传选中项的 `value`
 * - 多选：回传 JSON 数组文本（如 `["a","c"]`）。不用逗号拼接，因为
 *   选项 value 本身可能含逗号，拼接后无法无歧义还原
 */
@Composable
internal fun RenderSelect(node: SelectNode, context: RenderContext) {
    val enabled = node.enabled && !context.busy && !node.actionId.isNullOrBlank()
    var selected by remember(node.actionId) { mutableStateOf(emptySet<String>()) }

    fun toggle(value: String) {
        if (node.multiple) {
            selected = if (value in selected) selected - value else selected + value
        } else {
            selected = setOf(value)
            // 单选留空 submitLabel 表示「选中即提交」，省一次点击
            if (node.submitLabel.isBlank()) {
                node.actionId?.let { context.onAction(it, value) }
            }
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (node.label.isNotBlank()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.options.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = option.value in selected,
                        onCheckedChange = { if (enabled) toggle(option.value) },
                        enabled = enabled,
                    )
                    Text(
                        option.label.ifBlank { option.value },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (node.multiple || node.submitLabel.isNotBlank()) {
            Button(
                onClick = {
                    // 多选要保证顺序稳定：Set 的迭代顺序在 Kotlin 里是插入序，
                    // 但用 sorted 更保险，避免插件侧比较时因顺序不同而误判
                    val payload = if (node.multiple) {
                        selected.sorted().joinToString(",", "[", "]") { "\"$it\"" }
                    } else {
                        selected.firstOrNull().orEmpty()
                    }
                    if (payload.isNotBlank()) node.actionId?.let { context.onAction(it, payload) }
                },
                enabled = enabled && selected.isNotEmpty(),
            ) {
                Text(node.submitLabel.ifBlank { "确定" })
            }
        }
    }
}

/** 开关：value 回传 `"true"` / `"false"` */
@Composable
internal fun RenderToggle(node: ToggleNode, context: RenderContext) {
    val enabled = node.enabled && !context.busy && !node.actionId.isNullOrBlank()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(node.label, style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = node.checked,
                enabled = enabled,
                onCheckedChange = { checked ->
                    node.actionId?.let { context.onAction(it, if (checked) "true" else "false") }
                },
            )
        }
    }
}

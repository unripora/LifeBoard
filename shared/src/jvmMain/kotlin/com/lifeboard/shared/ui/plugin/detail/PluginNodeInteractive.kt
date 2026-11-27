package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.PickerMode
import com.lifeboard.plugin.api.PickerNode
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
 *
 * ## 选区归属：受控 / 非受控
 *
 * - `selectedValues` 非空时，插件给定选区；即时提交后等待新树回显。
 * - 单选的 `submitLabel` 非空时，点击先更新本地草稿，按确认才回传。
 *   插件更新选区时重置草稿，避免旧草稿覆盖新的受控值。
 * - `selectedValues` 为空时由本地记忆；多选保留确认提交。
 * - 受控多选沿用每次勾选即时回传，不额外显示确认按钮。
 */
@Composable
internal fun RenderSelect(node: SelectNode, context: RenderContext) {
    val enabled = node.enabled && !context.busy && !node.actionId.isNullOrBlank()
    var draft by remember(node.id, node.actionId, node.selectedValues) {
        mutableStateOf<Set<String>?>(null)
    }
    val selected = selectDisplayedValues(node, draft)

    fun toggle(value: String) {
        val change = selectOption(node, selected, value)
        draft = change.selected
        change.payload?.let { payload -> node.actionId?.let { context.onAction(it, payload) } }
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
        if (selectNeedsConfirmation(node)) {
            Button(
                onClick = {
                    val payload = selectionPayload(selected, node.multiple)
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

/**
 * 清单选择器。
 *
 * ## 两种形态
 *
 * - **MODAL**：一个按钮 + 当前选中项摘要，点击弹出 `AlertDialog`。候选多时用它，
 *   不占版面。
 * - **INLINE**：候选直接铺在页面上，点一行即选中。候选三五个时用它，
 *   少一次点击，也让用户一眼看全有哪些。
 *
 * ## 回传格式（与 `select` 保持一致，避免插件处理两套约定）
 *
 * - 单选：`onAction(actionId, candidate.id)`
 * - 多选：`onAction(actionId, JSON 数组文本)`，如 `["a","c"]`
 *
 * ## 选中态由插件回传
 *
 * `node.selectedIds` 是「现在选中了谁」的唯一真相。本地只在**弹层打开期间**
 * 记一份临时多选集合，关闭弹层即丢弃——否则插件刷新一次树，
 * 界面上的选中态和插件认知就会分叉。
 */
@Composable
internal fun RenderPicker(node: PickerNode, context: RenderContext) {
    val candidates = (node.candidates as? Bound.Static)?.value.orEmpty()
    val enabled = !context.busy && !node.actionId.isNullOrBlank()
    var dialogOpen by remember(node.id, node.actionId) { mutableStateOf(false) }
    // 多选时弹层内的临时选择：只有在按下确认时才开始生效
    var draft by remember(node.id, node.actionId) { mutableStateOf(emptySet<String>()) }

    fun commit(ids: Set<String>) {
        val actionId = node.actionId ?: return
        val payload = selectionPayload(ids, node.multiple)
        if (payload.isNotBlank()) context.onAction(actionId, payload)
    }

    if (node.mode == PickerMode.INLINE) {
        InlinePicker(node, candidates, enabled) { id ->
            if (node.multiple) {
                val next = if (id in node.selectedIds) node.selectedIds.toSet() - id else node.selectedIds.toSet() + id
                commit(next)
            } else {
                commit(setOf(id))
            }
        }
        return
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (node.title.isNotBlank()) {
            Text(
                node.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (candidates.isEmpty()) {
            Text(
                node.emptyHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        // 当前选中项摘要：弹层关着的时候，用户也要能看出现在选的是哪一条
        Text(
            selectionSummary(node, candidates),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(
            onClick = {
                // 打开时把插件给的选中态灌进草稿，保证「先看到现在的选择，再改」
                draft = node.selectedIds.toSet()
                dialogOpen = true
            },
            enabled = enabled,
        ) {
            Text(if (node.selectedIds.isEmpty()) "选择" else "更换")
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(node.title.ifBlank { "请选择" }) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 候选可能很多：弹层内部滚动，而不是把对话框撑到屏幕外
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    candidates.forEach { candidate ->
                        PickerRow(
                            candidate = candidate,
                            selected = candidate.id in draft,
                            multiple = node.multiple,
                            enabled = !candidate.disabled,
                            onClick = {
                                if (node.multiple) {
                                    draft = if (candidate.id in draft) {
                                        draft - candidate.id
                                    } else {
                                        draft + candidate.id
                                    }
                                } else {
                                    // 单选：点即生效并关闭，不必再点确认
                                    dialogOpen = false
                                    commit(setOf(candidate.id))
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                if (node.multiple) {
                    TextButton(
                        onClick = {
                            dialogOpen = false
                            commit(draft)
                        },
                    ) {
                        Text(node.submitLabel.ifBlank { "确定" })
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("取消") }
            },
        )
    }
}

/** 就地展开的清单：标题 + 一组可点行 */
@Composable
private fun InlinePicker(
    node: PickerNode,
    candidates: List<com.lifeboard.plugin.api.PickerCandidate>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (node.title.isNotBlank()) {
            Text(
                node.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (candidates.isEmpty()) {
            Text(
                node.emptyHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            candidates.forEach { candidate ->
                PickerRow(
                    candidate = candidate,
                    selected = candidate.id in node.selectedIds,
                    multiple = node.multiple,
                    enabled = enabled && !candidate.disabled,
                    onClick = { onPick(candidate.id) },
                )
            }
        }
    }
}

/**
 * 一行候选。
 *
 * 选中标记用文字前缀（`●` / `○`）而不是 Checkbox/RadioButton：
 * 与详情页其它地方「用文字代替图标」的处理保持一致（桌面端不引
 * material-icons 依赖），同时避免多选/单选两套控件的视觉分裂。
 */
@Composable
private fun PickerRow(
    candidate: com.lifeboard.plugin.api.PickerCandidate,
    selected: Boolean,
    multiple: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .background(
                if (selected) colors.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (selected) "●" else if (multiple) "○" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                candidate.label,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    candidate.tone == com.lifeboard.plugin.api.Tone.ERROR -> colors.error
                    else -> colors.onSurface
                },
            )
            if (candidate.detail.isNotBlank()) {
                Text(
                    candidate.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 选中项摘要：多选时直接罗列 label，单选时取那一条的 label */
private fun selectionSummary(
    node: PickerNode,
    candidates: List<com.lifeboard.plugin.api.PickerCandidate>,
): String {
    if (node.selectedIds.isEmpty()) return "尚未选择"
    val labels = node.selectedIds.map { id ->
        candidates.firstOrNull { it.id == id }?.label ?: id
    }
    return if (node.multiple) labels.joinToString("、") else labels.first()
}

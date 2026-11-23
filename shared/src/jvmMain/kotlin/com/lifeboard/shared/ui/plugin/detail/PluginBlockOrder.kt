package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ApprovalBlock
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.ConversationBlock
import com.lifeboard.plugin.api.CustomImageBlock
import com.lifeboard.plugin.api.InputBoxBlock
import com.lifeboard.plugin.api.MessageListBlock
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.UiBlock
import kotlin.math.abs

/** 区块顺序策略：插件默认顺序 / 用户自定义顺序 */
enum class BlockOrderMode {
    DEFAULT,
    CUSTOM,
}

/** 设置键前缀。按插件区分，避免不同插件的顺序互相污染 */
internal const val BLOCK_ORDER_KEY_PREFIX = "plugin.blockOrder."
internal const val BLOCK_ORDER_MODE_KEY_PREFIX = "plugin.blockOrderMode."

/** 区块的稳定引用：插件声明了 id 就用 id，否则用「类型 + 位置」兜底 */
fun blockKey(block: UiBlock, index: Int): String =
    block.id?.takeIf { it.isNotBlank() } ?: "${blockTypeTag(block)}#$index"

/**
 * 是否允许拖动。
 *
 * 输入框、审批、会话框是有行为依赖的交互块：它们的位置跟上下文绑定，
 * 单独拖走会让「正在等的输入」和「正在等的审批」脱离说明它的卡片。
 * 这里把它们锁定在插件给的位置，只让展示类卡片参与排序。
 */
fun isReorderableBlock(block: UiBlock): Boolean = when (block) {
    is InputBoxBlock, is ApprovalBlock, is ConversationBlock -> false
    else -> true
}

/** 可拖动区块在完整列表中的下标，按原顺序 */
fun reorderableIndices(blocks: List<UiBlock>): List<Int> =
    blocks.indices.filter { isReorderableBlock(blocks[it]) }

/**
 * 按保存的顺序重排可拖动区块。
 *
 * - 锁定区块的位置完全不动：把重排后的卡片填回原来的槽位；
 * - 保存顺序里没有的（插件新增的）区块排到最后，保持插件给的相对顺序；
 * - 保存顺序里多余的（插件已删除的）忽略。
 */
fun applyBlockOrder(blocks: List<UiBlock>, savedOrder: List<String>): List<UiBlock> {
    val slots = reorderableIndices(blocks)
    if (slots.isEmpty()) return blocks

    val current = slots.mapIndexed { position, slot ->
        Triple(blockKey(blocks[slot], slot), position, blocks[slot])
    }
    val rank = savedOrder.withIndex().associate { it.value to it.index }
    val sorted = current.sortedWith(
        compareBy({ rank[it.first] ?: Int.MAX_VALUE }, { it.second })
    )

    val result = blocks.toMutableList()
    slots.forEachIndexed { position, slot -> result[slot] = sorted[position].third }
    return result
}

/** 当前展示顺序下可拖动区块的 key，用于落盘 */
fun blockOrderKeys(blocks: List<UiBlock>): List<String> =
    reorderableIndices(blocks).map { blockKey(blocks[it], it) }

/**
 * 把完整列表中的 from → to 拖动换算到可拖动子序列。
 *
 * 目标落在锁定区块上时，选距离最近的可拖动槽位：否则会出现「拖到输入框上没反应」。
 */
fun moveBlock(blocks: List<UiBlock>, from: Int, to: Int): List<UiBlock> {
    val slots = reorderableIndices(blocks)
    if (slots.isEmpty()) return blocks
    val fromPosition = slots.indexOf(from)
    if (fromPosition < 0) return blocks
    val target = if (to in slots) {
        to
    } else {
        // 落点在锁定块上时取最近的可拖动槽位；距离相同则按拖动方向取「越过」的那一侧，
        // 否则向下拖会被判成原地不动
        slots.sortedWith(
            compareBy({ abs(it - to) }, { if (to > from) it else -it })
        ).firstOrNull { it != from } ?: return blocks
    }
    val toPosition = slots.indexOf(target)
    if (toPosition < 0 || toPosition == fromPosition) return blocks

    val movable = slots.map { blocks[it] }.toMutableList()
    val moved = movable.removeAt(fromPosition)
    movable.add(toPosition, moved)

    val result = blocks.toMutableList()
    slots.forEachIndexed { position, slot -> result[slot] = movable[position] }
    return result
}

/** 兜底 key 用的类型名，保持稳定，不依赖类名反射 */
private fun blockTypeTag(block: UiBlock): String = when (block) {
    is TextBlock -> "text"
    is StatusCardBlock -> "statusCard"
    is ButtonRowBlock -> "buttonRow"
    is ChartBlock -> "chart"
    is CustomImageBlock -> "customImage"
    is MessageListBlock -> "messageList"
    is InputBoxBlock -> "inputBox"
    is ApprovalBlock -> "approval"
    is ConversationBlock -> "conversation"
}

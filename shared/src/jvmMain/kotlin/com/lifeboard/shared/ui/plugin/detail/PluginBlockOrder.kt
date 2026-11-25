package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.AudioNode
import com.lifeboard.plugin.api.ButtonNode
import com.lifeboard.plugin.api.ChartNode
import com.lifeboard.plugin.api.CodeNode
import com.lifeboard.plugin.api.CollapsibleNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.DividerNode
import com.lifeboard.plugin.api.ImageNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.KeyValueNode
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.ProgressNode
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.SpacerNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TableNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.ToggleNode
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.plugin.api.VideoNode
import kotlin.math.abs

/** 区块顺序策略：插件默认顺序 / 用户自定义顺序 */
enum class BlockOrderMode {
    DEFAULT,
    CUSTOM,
}

/** 设置键前缀。按插件区分，避免不同插件的顺序互相污染 */
internal const val BLOCK_ORDER_KEY_PREFIX = "plugin.blockOrder."
internal const val BLOCK_ORDER_MODE_KEY_PREFIX = "plugin.blockOrderMode."

/**
 * 根节点下参与排序的子节点列表。
 *
 * 只有根是 [ColumnNode] 时才有排序可言：那时它的 children 就是「一列卡片」。
 * 根是别的东西（单个文本、一个列表）时返回空——没有任何可拖的东西。
 */
fun rootChildren(root: PluginNode?): List<PluginNode> =
    (root as? ColumnNode)?.children ?: emptyList()

/**
 * 用新的子节点列表替换根节点的 children。
 *
 * 根不是 column 时原样返回：没有可替换的位置，硬塞会改变插件的布局意图。
 */
fun withRootChildren(root: PluginNode?, children: List<PluginNode>): PluginNode? {
    if (root !is ColumnNode) return root
    return root.copy(children = children)
}

/** 节点的稳定引用：插件声明了 id 就用 id，否则用「类型 + 位置」兜底 */
fun nodeKey(node: PluginNode, index: Int): String =
    node.id?.takeIf { it.isNotBlank() } ?: "${nodeTypeTag(node)}#$index"

/**
 * 是否允许拖动。
 *
 * 两条规则：
 * 1. 交互节点（输入、审批式的选择、按钮）的位置跟上下文强绑定——
 *    把「正在等的输入框」单独拖走，用户就找不到它在等什么了。
 * 2. 插件可以显式用 `locked` 锁定某个节点（例如紧贴图表的说明文字）。
 *
 * 注意容器（[ColumnNode] / [RowNode] / [CollapsibleNode]）**可以**拖：
 * 整块搬走是合理需求。只有根节点的直接子节点参与排序，容器内部的
 * children 不参与——嵌套排序需要为每个容器维护独立顺序偏好，
 * 设置项会指数膨胀，且插件每次调整结构都会让保存的顺序错位。
 */
fun isReorderableNode(node: PluginNode): Boolean =
    !node.locked && node !is InputNode && node !is SelectNode && node !is ToggleNode

/** 可拖动节点在根 children 中的下标，按原顺序 */
fun reorderableIndices(children: List<PluginNode>): List<Int> =
    children.indices.filter { isReorderableNode(children[it]) }

/**
 * 按保存的顺序重排可拖动节点。
 *
 * - 锁定节点位置完全不动：把重排后的节点填回原来的槽位；
 * - 保存顺序里没有的（插件新增的）排到最后，保持插件给的相对顺序；
 * - 保存顺序里多余的（插件已删除的）忽略。
 */
fun applyNodeOrder(children: List<PluginNode>, savedOrder: List<String>): List<PluginNode> {
    val slots = reorderableIndices(children)
    if (slots.isEmpty()) return children

    val current = slots.mapIndexed { position, slot ->
        Triple(nodeKey(children[slot], slot), position, children[slot])
    }
    val rank = savedOrder.withIndex().associate { it.value to it.index }
    val sorted = current.sortedWith(
        compareBy({ rank[it.first] ?: Int.MAX_VALUE }, { it.second })
    )

    val result = children.toMutableList()
    slots.forEachIndexed { position, slot -> result[slot] = sorted[position].third }
    return result
}

/** 当前展示顺序下可拖动节点的 key，用于落盘 */
fun nodeOrderKeys(children: List<PluginNode>): List<String> =
    reorderableIndices(children).map { nodeKey(children[it], it) }

/**
 * 把完整列表中的 from → to 拖动换算到可拖动子序列。
 *
 * 目标落在锁定节点上时，选距离最近的可拖动槽位：否则会出现「拖到输入框上没反应」。
 */
fun moveNode(children: List<PluginNode>, from: Int, to: Int): List<PluginNode> {
    val slots = reorderableIndices(children)
    if (slots.isEmpty()) return children
    val fromPosition = slots.indexOf(from)
    if (fromPosition < 0) return children
    val target = if (to in slots) {
        to
    } else {
        // 落点在锁定节点上时取最近的可拖动槽位；距离相同则按拖动方向取「越过」的那一侧，
        // 否则向下拖会被判成原地不动
        slots.sortedWith(
            compareBy({ abs(it - to) }, { if (to > from) it else -it })
        ).firstOrNull { it != from } ?: return children
    }
    val toPosition = slots.indexOf(target)
    if (toPosition < 0 || toPosition == fromPosition) return children

    val movable = slots.map { children[it] }.toMutableList()
    val moved = movable.removeAt(fromPosition)
    movable.add(toPosition, moved)

    val result = children.toMutableList()
    slots.forEachIndexed { position, slot -> result[slot] = movable[position] }
    return result
}

/**
 * 兜底 key 用的类型名。
 *
 * ## ⚠️ 已有类型的字符串不能改
 *
 * 这些字符串会连同 `#下标` 一起写进 `plugin.blockOrder.<pluginId>`，
 * 是**用户排序偏好的持久化 key**。改名不会编译报错，但所有已保存的顺序
 * 会在一瞬间失效——用户只会看到「我调好的顺序又乱了」，毫无线索。
 *
 * 所以历史上存在过的 9 个标签（`text` / `statusCard` / `buttonRow` / `chart` /
 * `customImage` / `messageList` / `inputBox` / `approval` / `conversation`）
 * 即使对应类型已被节点树取代，也仍然保留在这里：老用户存的就是这些 key。
 * 新增节点只能**追加**新标签。
 */
private fun nodeTypeTag(node: PluginNode): String = when (node) {
    is TextNode -> "text"
    is StatusCardNode -> "statusCard"
    is ChartNode -> "chart"
    is ImageNode -> "customImage"
    is ListNode -> "messageList"
    is InputNode -> "inputBox"
    is ButtonNode -> "buttonRow"
    is SelectNode -> "select"
    is ToggleNode -> "toggle"
    is ColumnNode -> "column"
    is RowNode -> "row"
    is CollapsibleNode -> "collapsible"
    is SpacerNode -> "spacer"
    is DividerNode -> "divider"
    is ProgressNode -> "progress"
    is CodeNode -> "code"
    is TableNode -> "table"
    is KeyValueNode -> "keyValue"
    is UnsupportedNode -> "unsupported"
    is AudioNode -> "audio"
    is VideoNode -> "video"
}

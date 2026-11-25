package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
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

/**
 * 把节点树里的绑定点求值成具体值，得到一棵「可直接渲染」的树。
 *
 * ## 为什么先求值再渲染
 *
 * 渲染层的每个节点都应该只关心「怎么画」，不该在画的过程中还要判断
 * 「这个字段是不是绑定、绑定的数据来了没有」。求值一步做完，渲染代码
 * 就退化成纯粹的布局逻辑，也更容易单测。
 *
 * ## 合并规则（每一条都是踩过的坑）
 *
 * 1. **求值为不可用 → 该节点整个不渲染。** 渲染一个空卡片比不显示更让人
 *    困惑：用户会以为「数据是空的」，而实际是「数据源没接上」。
 * 2. **绑定优先于静态值。** [Bound] 是二选一，理论上不会并存，这条只是
 *    把优先级写死，避免将来有人加「静态兜底」时产生歧义。
 * 3. **类型不符 → 退回静态默认值，绝不强转。** 插件可能把 `session:main:status`
 *    （字符串）绑到 `items`（消息列表）上，此时渲染一个空列表 + 空态提示，
 *    而不是崩溃或画出乱码。
 * 4. 同一个绑定被多处引用是**合法**的：这正是通用绑定相对专用会话节点
 *    的优势——标题、状态卡、列表可以同时引用同一份数据。
 */
fun resolveNodes(
    root: PluginNode?,
    resolve: (BindingRef) -> BoundValue,
): PluginNode? = root?.let { resolveNode(it, resolve) }

private fun resolveNode(node: PluginNode, resolve: (BindingRef) -> BoundValue): PluginNode? =
    when (node) {
        is ColumnNode -> node.copy(children = resolveChildren(node.children, resolve))
        is RowNode -> node.copy(children = resolveChildren(node.children, resolve))
        is CollapsibleNode -> node.copy(children = resolveChildren(node.children, resolve))

        // 内容节点：只有 TextNode / StatusCardNode 带绑定点
        is TextNode -> when (val body = node.body.resolveString(resolve)) {
            null -> null              // 绑定不可用：整块不画
            else -> node.copy(body = Bound.Static(body))
        }
        is StatusCardNode -> when (val value = node.value.resolveString(resolve)) {
            null -> null
            else -> node.copy(value = Bound.Static(value))
        }
        is ListNode -> node.copy(items = node.items.resolveItems(resolve))
        is InputNode -> node.copy(
            // 布尔绑定不可用时退回「可用」：禁用输入框会让用户以为连接断了
            enabled = Bound.Static(node.enabled.resolveFlag(resolve) ?: true),
        )

        // 其余节点没有绑定点，原样返回
        is SpacerNode, is DividerNode, is ChartNode, is ImageNode, is ProgressNode,
        is CodeNode, is TableNode, is KeyValueNode, is ButtonNode, is SelectNode,
        is ToggleNode, is com.lifeboard.plugin.api.AudioNode,
        is com.lifeboard.plugin.api.VideoNode, is UnsupportedNode -> node
    }

/**
 * 递归求值子节点，并剔除「因绑定不可用而消失」的节点。
 *
 * 返回类型是可空列表而不是可空节点：子节点的消失不该让父容器一起消失
 * （一个卡片里有三块，其中一块没数据，另外两块仍要显示）。
 */
private fun resolveChildren(
    children: List<PluginNode>,
    resolve: (BindingRef) -> BoundValue,
): List<PluginNode> = children.mapNotNull { resolveNode(it, resolve) }

/**
 * 字符串绑定求值；返回 null 表示「这块不该渲染」。
 *
 * 两种情况要分开：
 * - **数据源不可用**（源未知 / key 不存在）→ null，节点整块消失
 * - **绑定到了非字符串字段**（把消息列表绑成文案）→ 空串，文案区缺了
 *   不影响主流程，整块消失反而更奇怪
 */
private fun Bound<String>.resolveString(resolve: (BindingRef) -> BoundValue): String? =
    when (this) {
        is Bound.Static -> value
        is Bound.Ref -> when (val v = BindingRef.parse(ref)?.let(resolve)) {
            is BoundValue.Text -> v.value
            BoundValue.Unavailable -> null
            else -> ""
        }
    }

/**
 * 布尔绑定求值；返回 null 表示绑定不可用。
 *
 * 与字符串不同，这里用 null 表示「不可用」而不是默认值——调用方需要区分
 * 「数据源说不可输入」与「数据源没接上」。
 */
private fun Bound<Boolean>.resolveFlag(resolve: (BindingRef) -> BoundValue): Boolean? =
    when (this) {
        is Bound.Static -> value
        is Bound.Ref -> when (val v = BindingRef.parse(ref)?.let(resolve)) {
            is BoundValue.Flag -> v.value
            else -> null
        }
    }

/**
 * 消息列表绑定求值。
 *
 * 会话消息（[BoundValue.Messages]）在这里被翻译成「每项一段文本」：
 * 插件拿到的是通用的节点列表，不需要为「消息」再造一种节点类型。
 * 角色与时间通过 [TextNode.tone] 与标题体现，气泡的具体样式由渲染层决定。
 */
private fun Bound<List<List<PluginNode>>>.resolveItems(
    resolve: (BindingRef) -> BoundValue,
): Bound.Static<List<List<PluginNode>>> =
    when (this) {
        is Bound.Static -> Bound.Static(value)
        is Bound.Ref -> when (val v = BindingRef.parse(ref)?.let(resolve)) {
            is BoundValue.Messages -> Bound.Static(
                v.value.map { message ->
                    listOf(
                        TextNode(
                            title = message.timeLabel.orEmpty(),
                            body = Bound.Static(message.text),
                            tone = message.role.toTone(),
                        ),
                    )
                },
            )
            // 绑定到非消息源，或源不可用：空列表 + 空态提示，而不是整块消失
            else -> Bound.Static(emptyList())
        }
    }

/** 消息角色 → 语义色调：系统提示弱化，其余用中性色由渲染层加气泡 */
private fun com.lifeboard.plugin.api.ChatRole.toTone(): com.lifeboard.plugin.api.Tone =
    when (this) {
        com.lifeboard.plugin.api.ChatRole.SYSTEM -> com.lifeboard.plugin.api.Tone.NEUTRAL
        com.lifeboard.plugin.api.ChatRole.USER -> com.lifeboard.plugin.api.Tone.OK
        com.lifeboard.plugin.api.ChatRole.ASSISTANT -> com.lifeboard.plugin.api.Tone.NEUTRAL
    }

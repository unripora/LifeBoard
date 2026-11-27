package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.PluginNode

/**
 * 从节点树里收集所有绑定引用（深度优先）。
 *
 * 用途是判断「要不要装配某个数据源」：树里出现 `session:*` 就说明插件
 * 想要会话数据，此时才需要启动会话控制器。做成纯函数以便直接单测
 * 「嵌套在可折叠块里的绑定也能被发现」。
 */
fun collectBindingRefs(root: PluginNode?): Set<BindingRef> {
    val refs = linkedSetOf<BindingRef>()
    fun visit(node: PluginNode) {
        when (node) {
            is com.lifeboard.plugin.api.ColumnNode -> node.children.forEach(::visit)
            is com.lifeboard.plugin.api.RowNode -> node.children.forEach(::visit)
            is com.lifeboard.plugin.api.CollapsibleNode -> node.children.forEach(::visit)
            is com.lifeboard.plugin.api.TextNode -> node.body.addRefTo(refs)
            is com.lifeboard.plugin.api.StatusCardNode -> node.value.addRefTo(refs)
            is com.lifeboard.plugin.api.InputNode -> {
                (node.enabled as? Bound.Ref)?.let { it.ref.toRefOrNull()?.let(refs::add) }
                node.submitTo?.let(refs::add)
            }
            is com.lifeboard.plugin.api.ListNode -> {
                (node.items as? Bound.Ref)?.let { it.ref.toRefOrNull()?.let(refs::add) }
                (node.items as? Bound.Static)?.value?.forEach { item -> item.forEach(::visit) }
                // 整行点击的回传标识不参与「要不要装配数据源」的判定，
                // 所以这里只收绑定，不收 actionId
            }
            is com.lifeboard.plugin.api.ChatNode -> {
                node.messages.addRefTo(refs)
            }
            is com.lifeboard.plugin.api.PickerNode -> {
                node.candidates.addRefTo(refs)
            }
            else -> Unit
        }
    }
    root?.let(::visit)
    return refs
}

private fun Bound<*>.addRefTo(into: MutableSet<BindingRef>) {
    if (this is Bound.Ref) (this as Bound.Ref<*>).ref.toRefOrNull()?.let(into::add)
}

private fun String.toRefOrNull(): BindingRef? = BindingRef.parse(this)

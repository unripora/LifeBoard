package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 列表。
 *
 * ## 为什么有 maxHeightDp
 *
 * 详情页整页纵向滚动。消息列表若随内容撑开，聊到几十条后整页会被它撑成几米长，
 * 下面的输入框被挤到最底、每次都要滑很远。给一个高度上限让列表内部自己滚，
 * 上下相邻的控件位置才稳定。
 *
 * 上限由插件声明（默认 0 = 不限制）。**0 的语义是「跟随内容」**：
 * 早先这里硬顶 2000dp，一个普通指标列表超过 2000dp 就会被截断且没有任何
 * 滚动入口——插件没声明限高却出现内容看不到，是纯粹的缺陷。现在不限制时
 * 直接跟随内容，由外层整页滚动承接。
 *
 * ## 整行可点
 *
 * `itemActionId` 非空时整行可点，回传该行的 `itemPayloads[i]`。判断点击归属
 * 由 Compose 的手势分发决定：行内按钮会先消费点击，不会误触整行。
 */
@Composable
internal fun RenderList(node: ListNode, context: RenderContext) {
    val items = (node.items as? Bound.Static)?.value.orEmpty()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            Text(
                node.emptyHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        val scrollModifier = if (node.maxHeightDp > 0) {
            Modifier.fillMaxWidth().heightIn(max = node.maxHeightDp.dp)
        } else {
            Modifier.fillMaxWidth()
        }

        // 无上限时也要用 LazyColumn 的 state 以便"贴底"？不需要：
        // 参与整页滚动时没有独立滚动区，贴底交给整页滚动的持有者。
        // 有上限时才需要内部滚动与自动贴底。
        if (node.maxHeightDp > 0) {
            val listState = rememberLazyListState()
            // 新条目进来时贴到底部：聊天场景里用户最关心最新的那条，
            // 停在顶部会让人以为「没反应」
            LaunchedEffect(items.size) {
                listState.scrollToItem(items.lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = scrollModifier,
                verticalArrangement =
                    Arrangement.spacedBy(if (node.dense) 4.dp else 8.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    RowSlot(node, index, item, context)
                    if (node.separator && index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            Column(
                modifier = scrollModifier,
                verticalArrangement = Arrangement.spacedBy(if (node.dense) 4.dp else 8.dp),
            ) {
                items.forEachIndexed { index, item ->
                    RowSlot(node, index, item, context)
                    if (node.separator && index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/**
 * 列表的一行（含可选的整行点击）。
 *
 * 抽成一个 composable 是为了让两条渲染分支（有/无高度上限）共用同一份
 * 点击逻辑——点击行为在两处不一致，是「滚动状态下能点、展开状态下不能点」
 * 这类怪问题最常见的来源。
 */
@Composable
private fun RowSlot(
    node: ListNode,
    index: Int,
    item: List<com.lifeboard.plugin.api.PluginNode>,
    context: RenderContext,
) {
    val actionId = node.itemActionId?.takeIf { it.isNotBlank() }
    val clickable = actionId != null && !context.busy
    val payload = node.itemPayloads.getOrNull(index).orEmpty()

    RenderListItem(
        item = item,
        context = context,
        modifier = if (clickable) {
            Modifier.clickable { context.onAction(actionId!!, payload) }
        } else {
            Modifier
        },
    )
}

/**
 * 渲染列表的一项。
 *
 * 一项可以有多个节点；它们横向并排（`Row` + 两端对齐），这样「左边标签 +
 * 右边数值」这类常见形态不用插件自己写容器。
 */
@Composable
private fun RenderListItem(
    item: List<com.lifeboard.plugin.api.PluginNode>,
    context: RenderContext,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.forEach { RenderNode(it, context) }
    }
}

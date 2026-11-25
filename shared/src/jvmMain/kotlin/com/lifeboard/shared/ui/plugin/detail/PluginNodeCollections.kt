package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * 上限由插件声明（默认 0 = 不限制）：普通状态列表本来就不该限高，
 * 只有消息这种会无限增长的才需要。
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

        if (node.maxHeightDp > 0) {
            val listState = rememberLazyListState()
            // 新条目进来时贴到底部：聊天场景里用户最关心最新的那条，
            // 停在顶部会让人以为「没反应」
            LaunchedEffect(items.size) {
                listState.scrollToItem(items.lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = node.maxHeightDp.dp),
                verticalArrangement = Arrangement.spacedBy(if (node.dense) 4.dp else 8.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    RenderListItem(item, context)
                    if (node.separator && index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp),
                verticalArrangement = Arrangement.spacedBy(if (node.dense) 4.dp else 8.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    RenderListItem(item, context)
                    if (node.separator && index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/**
 * 渲染列表的一项。
 *
 * 一项可以有多个节点；它们横向并排（`Row` + 两端对齐），这样「左边标签 +
 * 右边数值」这类常见形态不用插件自己写容器。
 */
@Composable
private fun RenderListItem(item: List<com.lifeboard.plugin.api.PluginNode>, context: RenderContext) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.forEach { RenderNode(it, context) }
    }
}

package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.CollapsibleNode
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.DividerNode
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.SpacerNode
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 纵向排列。
 *
 * `card` 是整棵树里唯一决定「套不套卡片」的字段。嵌套容器默认不套，
 * 从机制上避免卡片套娃——旧实现对每个区块各写一次卡片，嵌套后两层边框
 * 叠在一起，看起来像表格。
 */
@Composable
internal fun RenderColumn(node: ColumnNode, context: RenderContext) {
    val content: @Composable () -> Unit = {
        Column(verticalArrangement = spacingOf(node.spacing)) {
            node.children.forEach { RenderNode(it, context) }
        }
    }
    if (node.card) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            if (node.title.isNotBlank()) {
                Text(
                    node.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (node.title.isNotBlank()) {
                Text(
                    node.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/**
 * 横向排列。
 *
 * 永远不套卡片：一排按钮或一排状态本来就该贴着页面背景，
 * 套上卡片会显得像「一个可以整块点的区域」，而实际只有里面的控件可点。
 */
@Composable
internal fun RenderRow(node: RowNode, context: RenderContext) {
    val spacing = spacingDp(node.spacing)
    if (node.wrap) {
        // 窄屏折行：一排按钮在手机上会挤爆，折行比压缩字号好
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            node.children.forEach { RenderNode(it, context) }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { RenderNode(it, context) }
        }
    }
}

/**
 * 可折叠区块。
 *
 * 折叠状态归宿主持有：插件每次刷新可以重置初始值，但用户的展开/收起
 * 在本次页面停留期间会保留——否则每次同步都弹回去，用户会烦。
 */
@Composable
internal fun RenderCollapsible(node: CollapsibleNode, context: RenderContext) {
    var expanded by remember(node.id, node.title) { mutableStateOf(!node.collapsed) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                node.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (expanded) "收起" else node.summary.ifBlank { "展开" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.children.forEach { RenderNode(it, context) }
            }
        }
    }
}

@Composable
internal fun RenderSpacer(node: SpacerNode) {
    Spacer(modifier = Modifier.height(spacingDp(node.size)))
}

/** 分隔线：卡片内部划分组用，替代「卡片里再套一张卡片」 */
@Composable
internal fun RenderDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

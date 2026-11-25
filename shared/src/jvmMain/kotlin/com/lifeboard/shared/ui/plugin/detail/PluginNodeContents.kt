package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChartNode
import com.lifeboard.plugin.api.CodeNode
import com.lifeboard.plugin.api.Emphasis
import com.lifeboard.plugin.api.ImageNode
import com.lifeboard.plugin.api.KeyValueNode
import com.lifeboard.plugin.api.ProgressNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TableNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UnsupportedNode
import com.lifeboard.shared.ui.common.DataUriImage
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 一段文字。
 *
 * 标题走语义色（`tone`），正文一律次要色：正文染色会让整块看起来像警告，
 * 而插件传 tone 的意图通常是「标题/关键值需要强调」。
 */
@Composable
internal fun RenderText(node: TextNode) {
    val body = (node.body as? Bound.Static)?.value.orEmpty()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (node.title.isNotBlank()) {
            Text(
                node.title,
                style = emphasisStyle(node.emphasis),
                fontWeight = FontWeight.SemiBold,
                color = toneColor(node.tone),
            )
        }
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun emphasisStyle(emphasis: Emphasis) = when (emphasis) {
    Emphasis.HEADING -> MaterialTheme.typography.titleSmall
    Emphasis.BODY -> MaterialTheme.typography.bodySmall
    Emphasis.CAPTION -> MaterialTheme.typography.labelSmall
}

/** 状态卡：左右两栏的「标签 + 值」 */
@Composable
internal fun RenderStatusCard(node: StatusCardNode) {
    val value = (node.value as? Bound.Static)?.value.orEmpty()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = toneColor(node.tone),
            )
        }
        if (node.detail.isNotBlank()) {
            Text(
                node.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图表：标题行 + 宿主内置渲染器（自动跟随深浅色） */
@Composable
internal fun RenderChart(node: ChartNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (node.title.isNotBlank()) {
            Text(
                node.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ChartRenderer(node.chart)
    }
}

@Composable
internal fun RenderImage(node: ImageNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        DataUriImage(
            dataUri = node.dataUri,
            contentDescription = node.contentDescription.ifBlank { "插件提供的图片" },
            errorText = "图片加载失败",
        )
    }
}

/**
 * 线性进度。
 *
 * 与环形进度并存：环形用于「一张卡的中心数据」，线性用于「这一项做到多少了」
 * 这种窄条场景，两者占的版面形状不同。
 */
@Composable
internal fun RenderProgress(node: ProgressNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.valueText.isNotBlank()) {
                Text(
                    node.valueText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = toneColor(node.tone),
                )
            }
        }
        // 越界由这里收敛：插件传出 1.5 或 -0.2 都不该画出越界的条
        LinearProgressIndicator(
            progress = { node.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun RenderCode(node: CodeNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            node.code,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(6.dp)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 表格：列宽按 weight 分配，weight<=0 的列按内容自适应 */
@Composable
internal fun RenderTable(node: TableNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        node.rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                node.columns.forEachIndexed { colIndex, column ->
                    val cell = row.getOrNull(colIndex).orEmpty()
                    val weight = if (column.weight > 0f) column.weight else 1f
                    Text(
                        cell,
                        modifier = Modifier.weight(weight),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (rowIndex == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** 键值对组：比一排状态卡更紧凑 */
@Composable
internal fun RenderKeyValue(node: KeyValueNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        entry.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = toneColor(entry.tone),
                    )
                }
            }
        }
    }
}

/**
 * 宿主不认识的节点。
 *
 * 画一个弱化占位而不是静默丢弃：用户能明白「这里本来有东西，是这个版本画不出来」，
 * 而不是以为插件坏了。
 */
@Composable
internal fun RenderUnsupported(node: UnsupportedNode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "此版本暂不支持：${node.type}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

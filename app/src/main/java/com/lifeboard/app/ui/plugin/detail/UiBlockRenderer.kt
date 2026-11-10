package com.lifeboard.app.ui.plugin.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.app.ui.common.DataUriImage
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.CustomImageBlock
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UiBlock

/**
 * 把插件给的 [UiBlock] 列表渲染成一列卡片。
 *
 * 所有颜色、字体、圆角都在这里决定，插件只说语义（[Tone]）。
 * 这是「详情页看起来像同一个应用」的关键：插件拿到的是宿主的设计语言，
 * 而不是各自往宿主里塞的小程序。
 *
 * @param busy 有动作正在执行，此时禁用全部按钮，防止连点并发调插件
 */
@Composable
fun UiBlockList(
    blocks: List<UiBlock>,
    busy: Boolean,
    onAction: (actionId: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is TextBlock -> TextSection(block)
                is StatusCardBlock -> StatusCard(block)
                is ButtonRowBlock -> ButtonSection(block, busy, onAction)
                is ChartBlock -> ChartSection(block)
                is CustomImageBlock -> CustomImageSection(block)
            }
        }
    }
}

@Composable
private fun TextSection(block: TextBlock) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            block.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = toneColor(block.tone),
        )
        if (block.body.isNotBlank()) {
            Text(
                block.body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(block: StatusCardBlock) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                block.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = toneColor(block.tone),
            )
        }
        block.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChartSection(block: ChartBlock) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            block.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        ChartRenderer(block.chart)
    }
}

@Composable
private fun CustomImageSection(block: CustomImageBlock) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        DataUriImage(
            dataUri = block.dataUri,
            contentDescription = "插件提供的图表",
            errorText = "插件图表加载失败",
        )
    }
}

@Composable
private fun ButtonSection(
    block: ButtonRowBlock,
    busy: Boolean,
    onAction: (String) -> Unit,
) {
    if (block.buttons.isEmpty()) return

    // 按钮不放进 GlassCard：卡片里再嵌一排按钮会显得像一个可以整块点的区域，
    // 而实际只有按钮本身可点
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        block.buttons.forEach { button ->
            val enabled = button.enabled && !busy
            if (button.primary) {
                Button(onClick = { onAction(button.actionId) }, enabled = enabled) {
                    Text(button.label)
                }
            } else {
                OutlinedButton(onClick = { onAction(button.actionId) }, enabled = enabled) {
                    Text(button.label)
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: Tone) = when (tone) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    Tone.OK -> MaterialTheme.colorScheme.primary
    Tone.WARN -> MaterialTheme.colorScheme.tertiary
    Tone.ERROR -> MaterialTheme.colorScheme.error
}

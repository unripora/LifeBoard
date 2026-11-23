package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.ApprovalBlock
import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ButtonSpec
import com.lifeboard.plugin.api.ChartBlock
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.ConversationBlock
import com.lifeboard.shared.panel.CardDragState
import com.lifeboard.shared.panel.dragHandle
import com.lifeboard.plugin.api.CustomImageBlock
import com.lifeboard.plugin.api.InputBoxBlock
import com.lifeboard.plugin.api.MessageListBlock
import com.lifeboard.plugin.api.StatusCardBlock
import com.lifeboard.plugin.api.TextBlock
import com.lifeboard.plugin.api.Tone
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.shared.ui.common.DataUriImage
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 把插件给的 [UiBlock] 列表渲染成一列卡片。
 *
 * 所有颜色、字体、圆角都在这里决定，插件只说语义（[Tone]）。
 * 这是「详情页看起来像同一个应用」的关键：插件拿到的是宿主的设计语言，
 * 而不是各自往宿主里塞的小程序。
 *
 * @param busy 有动作正在执行，此时禁用全部按钮，防止连点并发调插件
 * @param onAction 交互回调；输入类区块带 [value]，按钮/选项类为 `null`
 * @param conversation 会话区块的绑定；未接入连接能力时保持默认实现，区块显示「未连接」
 */
@Composable
fun UiBlockList(
    blocks: List<UiBlock>,
    busy: Boolean,
    onAction: (actionId: String, value: String?) -> Unit,
    conversation: ConversationBinding = ConversationBinding.Unavailable,
    orderMode: BlockOrderMode = BlockOrderMode.DEFAULT,
    drag: CardDragState? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { index, block ->
            // 只有展示类卡片可拖；输入/审批/会话保持插件给的位置
            Column(
                modifier = if (drag != null && orderMode == BlockOrderMode.CUSTOM && isReorderableBlock(block)) {
                    Modifier.dragHandle(true, index, blocks.size, drag)
                } else {
                    Modifier
                },
            ) {
                BlockContent(block, busy, onAction, conversation)
            }
        }
    }
}

/** 单个区块的内容；拆出来是为了让拖动外壳只管手势与位移 */
@Composable
private fun BlockContent(
    block: UiBlock,
    busy: Boolean,
    onAction: (actionId: String, value: String?) -> Unit,
    conversation: ConversationBinding,
) {
    when (block) {
        is TextBlock -> TextSection(block)
        is StatusCardBlock -> StatusCard(block)
        is ButtonRowBlock -> ButtonSection(block, busy, onAction)
        is ChartBlock -> ChartSection(block)
        is CustomImageBlock -> CustomImageSection(block)
        is MessageListBlock -> MessageSection(block)
        is InputBoxBlock -> InputSection(block, busy, onAction)
        is ApprovalBlock -> ApprovalSection(block, busy, onAction)
        is ConversationBlock -> ConversationSection(block, busy, conversation)
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
    onAction: (String, String?) -> Unit,
) {
    if (block.buttons.isEmpty()) return

    // 按钮不放进 GlassCard：卡片里再嵌一排按钮会显得像一个可以整块点的区域，
    // 而实际只有按钮本身可点
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        block.buttons.forEach { button ->
            ActionButton(button = button, busy = busy) { onAction(button.actionId, null) }
        }
    }
}

/**
 * 消息列表。
 *
 * ## 为什么固定高度
 *
 * 详情页整页是纵向滚动的。消息列表若随内容撑开，聊到几十条后整页会被
 * 它撑成几米长，输入框被挤到最底下、每次都要滑很远。固定一个高度让
 * 消息区自己滚，输入框稳定在列表下方。
 *
 * 长度上限 320dp：桌面端窗口够高，能同时看到输入框和最后几条；手机端
 * 也不会占掉整屏。
 */
@Composable
private fun MessageSection(block: MessageListBlock, emptyHint: String = "还没有消息") {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        if (block.messages.isEmpty()) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

        val listState = rememberLazyListState()
        // 新消息进来时贴到底部：聊天场景里用户最关心最新的那条，
        // 停在顶部会让人以为「没反应」
        LaunchedEffect(block.messages.size) {
            listState.scrollToItem(block.messages.lastIndex)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(block.messages) { _, message -> MessageBubble(message) }
        }
    }
}

/**
 * 会话区块：标题 + 实时消息 + 输入框。
 *
 * 消息与可用状态都从 [ConversationBinding] 读，本函数不持有任何会话状态——
 * 离开页面后会话仍由控制器的生命周期决定，界面只是它的一层投影。
 */
@Composable
private fun ConversationSection(
    block: ConversationBlock,
    busy: Boolean,
    conversation: ConversationBinding,
) {
    val state by conversation.state(block.sessionId).collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.title.ifBlank { "会话" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    state.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.inputEnabled) toneColor(Tone.OK) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MessageSection(MessageListBlock(state.messages), block.emptyHint)
        InputSection(
            InputBoxBlock(
                actionId = "conversation:${block.sessionId}",
                placeholder = block.placeholder,
                submitLabel = block.submitLabel,
                enabled = state.inputEnabled,
            ),
            busy = busy,
            // actionId 只用于让输入框保持独立状态，真正的去向由绑定决定
            onAction = { _, value -> conversation.submit(block.sessionId, value.orEmpty()) },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val text = if (message.pending) "${message.text}…" else message.text

    if (message.role == ChatRole.SYSTEM) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontStyle = if (message.pending) FontStyle.Italic else FontStyle.Normal,
        )
        return
    }

    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            // weight(1f, fill=false)：气泡按内容收缩，但超过可用宽度时换行，
            // 不会撑出屏幕
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (message.pending) FontStyle.Italic else FontStyle.Normal,
                )
            }
            message.timeLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 输入框。
 *
 * 输入内容由宿主持有并在提交后清空：插件下一次 [com.lifeboard.plugin.api.SourcePlugin.detailUi]
 * 会给回一条 [ChatRole.USER] 的消息，两边不会重复显示。
 */
@Composable
private fun InputSection(
    block: InputBoxBlock,
    busy: Boolean,
    onAction: (String, String?) -> Unit,
) {
    var text by remember(block.actionId) { mutableStateOf("") }
    val canSubmit = block.enabled && !busy && text.isNotBlank()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(block.placeholder) },
            enabled = block.enabled && !busy,
            maxLines = if (block.multiline) 4 else 1,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                val content = text.trim()
                if (content.isNotEmpty()) {
                    onAction(block.actionId, content)
                    text = ""
                }
            },
            enabled = canSubmit,
        ) {
            Text(block.submitLabel)
        }
    }
}

/**
 * 审批区块。
 *
 * [ApprovalBlock.waiting] 时只显示「等待处理」——此时选项已经提交出去，
 * 再让按钮可点等于允许重复提交。
 */
@Composable
private fun ApprovalSection(
    block: ApprovalBlock,
    busy: Boolean,
    onAction: (String, String?) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            block.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (block.detail.isNotBlank()) {
            Text(
                block.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (block.waiting) {
            Text(
                "等待处理…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
            return@GlassCard
        }

        if (block.options.isEmpty()) return@GlassCard
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.options.forEach { option ->
                ActionButton(button = option, busy = busy) { onAction(option.actionId, null) }
            }
        }
    }
}

/** 按钮/选项共用的样式：主操作实心、其余描边 */
@Composable
private fun ActionButton(button: ButtonSpec, busy: Boolean, onClick: () -> Unit) {
    val enabled = button.enabled && !busy
    if (button.primary) {
        Button(onClick = onClick, enabled = enabled) { Text(button.label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(button.label) }
    }
}

@Composable
private fun toneColor(tone: Tone) = when (tone) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    Tone.OK -> MaterialTheme.colorScheme.primary
    Tone.WARN -> MaterialTheme.colorScheme.tertiary
    Tone.ERROR -> MaterialTheme.colorScheme.error
}

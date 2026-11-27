package com.lifeboard.shared.ui.plugin.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatNode
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 对话流渲染。
 *
 * ## 职责边界
 *
 * 插件只给「谁说了什么」，这里负责**全部视觉**：谁在左谁在右、气泡什么颜色、
 * 名字什么时候显示、时间什么时候显示、流式时末尾的光标、新消息贴底。
 * 这是刻意的：气泡样式若由插件决定，各家插件就又回到「一堆各不相同的
 * 文字框」——正是本节点要消灭的形态。
 *
 * ## 分组规则（[ChatGroup]）
 *
 * 连续的同角色消息合成一组，避免「三句话三个气泡」。分组的额外边界是
 * 轮次变化：`turnId` 从有值变成另一个值时断开，让「上一轮问答」与
 * 「这一轮问答」在视觉上分开。
 *
 * ## 为什么不用一层卡片包住整个对话
 *
 * 对话本身通常已经是主体内容，再套一层卡片会出现「卡片里的一条缝」。
 * 本节点自绘表面（[GlassCard] 只在给内嵌场景时才有必要），这里直接铺开：
 * 气泡自己就是视觉单元。
 */
@Composable
internal fun RenderChat(node: ChatNode) {
    val messages = (node.messages as? Bound.Static)?.value.orEmpty()

    if (messages.isEmpty()) {
        EmptyChatHint(node.emptyHint)
        return
    }

    val groups = remember(node.messages) { groupMessages(messages) }

    if (node.maxHeightDp > 0) {
        val listState = rememberLazyListState()
        // 新消息进来贴底：聊天里用户最关心最新那条
        LaunchedEffect(groups.size) {
            if (groups.isNotEmpty()) listState.scrollToItem(groups.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = node.maxHeightDp.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(groups) { _, group -> ChatGroupBlock(group, node) }
        }
    } else {
        // 不限高时跟随内容：详情页整页滚动。这里不用 LazyColumn，
        // 避免「内层列表 + 外层滚动」两层滚动手势互相抢
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { group -> ChatGroupBlock(group, node) }
        }
    }
}

/** 空态提示：与 [ChatNode] 的设计一致，弱化、居中，不占大块版面 */
@Composable
private fun EmptyChatHint(hint: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 渲染一组（同角色、同轮次连续的若干条消息）。
 *
 * 对齐方式按角色决定：
 * - USER 靠右，主色气泡（「这是我说的」）
 * - ASSISTANT 靠左，表面色气泡
 * - SYSTEM 居中、无气泡，只是一行弱化文字（系统提示不属于任何一方）
 */
@Composable
private fun ChatGroupBlock(group: ChatGroup, node: ChatNode) {
    val role = group.role
    val horizontal = when (role) {
        ChatRole.USER -> Alignment.End
        ChatRole.ASSISTANT -> Alignment.Start
        ChatRole.SYSTEM -> Alignment.CenterHorizontally
    }
    val name = when (role) {
        ChatRole.USER -> node.selfName
        ChatRole.ASSISTANT -> node.peerName
        ChatRole.SYSTEM -> ""
    }.trim()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 名字每组建模显示一次：同角色连续发言不重复叫名字
        if (name.isNotEmpty()) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.showTime) {
            group.timeLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        group.messages.forEach { message ->
            ChatBubble(message, role)
        }
    }
}

/**
 * 一条消息的气泡。
 *
 * 宽度上限 320dp：满宽气泡在宽屏上会让一行文字长得难以阅读，
 * 且失去「左右两侧」的视觉区分。
 */
@Composable
private fun ChatBubble(message: ChatMessage, role: ChatRole) {
    if (role == ChatRole.SYSTEM) {
        Text(
            message.text,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isUser = role == ChatRole.USER
    val background = if (isUser) colors.primaryContainer else colors.surfaceVariant
    val contentColor = if (isUser) colors.onPrimaryContainer else colors.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        // 靠说话人一侧的角收一点，制造「从这一侧发出」的方向感
        bottomStart = if (isUser) 12.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 12.dp,
    )

    Box(
        modifier = Modifier.widthIn(max = 320.dp).background(background, shape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                message.text,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
            // 流式进行中：末尾给一个固定字符做光标。不用动画是刻意的——
            // 详情页可能同时有多个对话块，动画会让整页持续重组
            if (message.pending) {
                Text(
                    "▍",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * 一组连续消息。
 *
 * @property role 组内统一的角色
 * @property messages 组内消息，按原顺序
 * @property timeLabel 组首的时间文案（取组内第一条的时间）
 */
internal data class ChatGroup(
    val role: ChatRole,
    val messages: List<ChatMessage>,
    val timeLabel: String?,
)

/**
 * 把消息流切成组。
 *
 * ## 分组规则
 *
 * 1. 角色变化必然断开——这是最基本的一条，也是「分不清谁说的」的根治点。
 * 2. `turnId` 变化且新组是 USER 时断开：新的一轮问答从用户提问开始，
 *    与上一轮之间要留出可感知的间隔。
 * 3. SYSTEM 消息每条自成一组：系统提示不该和任何一方合并。
 *
 * 纯函数，便于直接单测「切段结果」。
 */
internal fun groupMessages(messages: List<ChatMessage>): List<ChatGroup> {
    if (messages.isEmpty()) return emptyList()
    val groups = mutableListOf<ChatGroup>()
    var current = mutableListOf<ChatMessage>()

    fun flush() {
        if (current.isEmpty()) return
        groups += ChatGroup(
            role = current.first().role,
            messages = current.toList(),
            timeLabel = current.first().timeLabel,
        )
        current = mutableListOf()
    }

    messages.forEach { message ->
        val last = current.lastOrNull()
        val sameRole = last != null && last.role == message.role
        val turnChanged = last != null &&
            last.turnId.isNotBlank() &&
            message.turnId.isNotBlank() &&
            last.turnId != message.turnId
        val startNewTurn = turnChanged && message.role == ChatRole.USER

        if (current.isEmpty()) {
            current += message
        } else if (!sameRole || startNewTurn || message.role == ChatRole.SYSTEM) {
            flush()
            current += message
        } else {
            current += message
        }
    }
    flush()
    return groups
}

package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.MessageListBlock
import com.lifeboard.plugin.api.UiBlock

/**
 * 把一段流式增量拼进区块列表。
 *
 * 规则只有三条，全部围绕「用户正在看一条回复」这个事实：
 * 1. 列表里最后一块是消息列表，且末条是**还在生成**的助手消息 → 续写它；
 * 2. 最后一块是消息列表但末条不是待生成的助手消息（例如用户刚提问）→ 新开一条；
 * 3. 压根没有消息列表（插件只有别的区块）→ 补一块出来，否则增量无处可落。
 *
 * 只在最后一个消息列表上动手：上面的历史对话是已经定稿的内容，
 * 增量属于「这一次调用」，塞到中间任何位置都是错的。
 *
 * 这里是纯函数而非成员：状态变换不碰协程、不读磁盘，可以被单测直接钉住。
 */
internal fun appendStreamDelta(blocks: List<UiBlock>, delta: String): List<UiBlock> {
    if (delta.isEmpty()) return blocks

    val index = blocks.indexOfLast { it is MessageListBlock }
    if (index < 0) {
        return blocks + MessageListBlock(listOf(ChatMessage(ChatRole.ASSISTANT, delta, pending = true)))
    }

    val block = blocks[index] as MessageListBlock
    val last = block.messages.lastOrNull()
    val messages = if (last != null && last.role == ChatRole.ASSISTANT && last.pending) {
        // 去掉末条再用新内容补回，避免直接从 last 上改
        block.messages.dropLast(1) + last.copy(text = last.text + delta)
    } else {
        block.messages + ChatMessage(ChatRole.ASSISTANT, delta, pending = true)
    }
    return blocks.toMutableList().apply { this[index] = block.copy(messages = messages) }
}

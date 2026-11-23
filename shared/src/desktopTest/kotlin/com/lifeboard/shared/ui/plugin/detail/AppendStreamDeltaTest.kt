package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ButtonRowBlock
import com.lifeboard.plugin.api.ButtonSpec
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.MessageListBlock
import com.lifeboard.plugin.api.UiBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式增量拼接规则。
 *
 * 这是纯函数，但错法都很隐蔽：把增量接到上一轮回复后面、或每段新开一条气泡，
 * 界面看起来「有东西在动」，实际对话已经是错的。所以逐条钉住。
 */
class AppendStreamDeltaTest {

    private fun messagesOf(blocks: List<UiBlock>): List<ChatMessage> =
        (blocks.last { it is MessageListBlock } as MessageListBlock).messages

    @Test
    fun 待生成的助手消息会被续写而不是新开一条() {
        val blocks: List<UiBlock> = listOf(
            MessageListBlock(
                listOf(
                    ChatMessage(ChatRole.USER, "写首诗", pending = false),
                    ChatMessage(ChatRole.ASSISTANT, "床前", pending = true),
                ),
            ),
        )

        val merged = appendStreamDelta(appendStreamDelta(blocks, "明月光"), "，")

        val messages = messagesOf(merged)
        assertEquals(2, messages.size)
        assertEquals("床前明月光，", messages.last().text)
        assertTrue("生成中的消息应保持 pending", messages.last().pending)
    }

    @Test
    fun 已定稿的助手消息后面另起一条() {
        val blocks: List<UiBlock> = listOf(
            MessageListBlock(listOf(ChatMessage(ChatRole.ASSISTANT, "上一轮回答", pending = false))),
        )

        val messages = messagesOf(appendStreamDelta(blocks, "新一轮"))

        assertEquals(2, messages.size)
        assertEquals("上一轮回答", messages[0].text)
        assertEquals("新一轮", messages[1].text)
        assertTrue(messages[1].pending)
    }

    @Test
    fun 用户刚提问时新的增量属于助手() {
        val blocks: List<UiBlock> = listOf(
            MessageListBlock(listOf(ChatMessage(ChatRole.USER, "在吗", pending = false))),
        )

        val messages = messagesOf(appendStreamDelta(blocks, "在的"))

        assertEquals(2, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages.last().role)
        assertEquals("在的", messages.last().text)
    }

    @Test
    fun 没有消息列表时补一块出来() {
        val blocks: List<UiBlock> = listOf(
            ButtonRowBlock(listOf(ButtonSpec(label = "立即同步", actionId = "sync"))),
        )

        val merged = appendStreamDelta(blocks, "开始处理")

        // 原有区块不能被吞掉：它们承载着用户的可用操作
        assertEquals(2, merged.size)
        assertEquals("开始处理", messagesOf(merged).single().text)
    }

    @Test
    fun 空片段不改动状态() {
        val blocks: List<UiBlock> = listOf(
            MessageListBlock(listOf(ChatMessage(ChatRole.USER, "你好", pending = false))),
        )

        assertEquals(blocks, appendStreamDelta(blocks, ""))
    }

    @Test
    fun 只写最后一个消息列表() {
        val blocks: List<UiBlock> = listOf(
            MessageListBlock(listOf(ChatMessage(ChatRole.USER, "历史", pending = false))),
            MessageListBlock(listOf(ChatMessage(ChatRole.ASSISTANT, "现在", pending = true))),
        )

        val merged = appendStreamDelta(blocks, "继续")

        val first = (merged[0] as MessageListBlock).messages.single()
        assertEquals("历史", first.text)
        assertEquals("现在继续", (merged[1] as MessageListBlock).messages.single().text)
    }
}
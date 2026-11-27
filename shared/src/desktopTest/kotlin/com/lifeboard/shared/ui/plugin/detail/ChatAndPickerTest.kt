package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatNode
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.PickerCandidate
import com.lifeboard.plugin.api.PickerNode
import com.lifeboard.plugin.api.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对话节点（[ChatNode]）与清单选择节点（[PickerNode]）的求值与分组规则。
 *
 * 这两块是本轮「用户/AI 分不清」与「不能选会话」两个现象的落点，
 * 全部是不依赖 Compose 的纯逻辑，因此可以逐条钉死。
 */
class ChatAndPickerTest {

    private fun resolver(vararg pairs: Pair<String, BoundValue>): (BindingRef) -> BoundValue {
        val map = pairs.associate { (raw, value) -> requireNotNull(BindingRef.parse(raw)) to value }
        return { ref -> map[ref] ?: BoundValue.Unavailable }
    }

    // ---------- 消息求值 ----------

    /**
     * 消息绑定后**原样保留**为 [ChatMessage]。
     *
     * 这是与旧行为的关键差异：旧实现把每条消息降级成一个 [com.lifeboard.plugin.api.TextNode]，
     * 角色信息在渲染链路上被磨平，用户只看到一堆一样的文字框。
     */
    @Test
    fun chatMessagesKeepRoleAndTurn() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "你好", id = "m1", turnId = "t1", timeLabel = "10:00"),
            ChatMessage(
                ChatRole.ASSISTANT,
                "在的",
                id = "m2",
                turnId = "t1",
                pending = true,
            ),
        )
        val root = ChatNode(messages = Bound.Ref("session:main:messages"))
        val resolved = resolveNodes(
            root,
            resolver("session:main:messages" to BoundValue.Messages(messages)),
        ) as ChatNode

        val result = (resolved.messages as Bound.Static).value
        assertEquals(messages, result, "角色、轮次、流式标记都不能在求值时丢失")
    }

    /** 绑到非消息源时退回空列表：对话区仍在，显示「暂无对话」，而不是整块消失 */
    @Test
    fun chatBoundToWrongTypeFallsBackToEmpty() {
        val root = ChatNode(messages = Bound.Ref("session:main:status"))
        val resolved = resolveNodes(
            root,
            resolver("session:main:status" to BoundValue.Text("已连接")),
        ) as ChatNode
        assertTrue((resolved.messages as Bound.Static).value.isEmpty())
    }

    /** 源不可用时同样退回空列表而不是 null：对话是主体内容，整块消失会让用户以为页面坏了 */
    @Test
    fun chatWithUnavailableSourceKeepsTheNode() {
        val root = ChatNode(messages = Bound.Ref("session:main:messages"))
        val resolved = resolveNodes(root, resolver())
        assertEquals(true, resolved is ChatNode)
    }

    // ---------- 分组 ----------

    @Test
    fun consecutiveSameRoleMessagesMergeIntoOneGroup() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.ASSISTANT, "第一句"),
                ChatMessage(ChatRole.ASSISTANT, "第二句"),
                ChatMessage(ChatRole.USER, "回应"),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].messages.size)
        assertEquals(ChatRole.USER, groups[1].role)
    }

    @Test
    fun roleChangeAlwaysBreaksTheGroup() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.USER, "问"),
                ChatMessage(ChatRole.ASSISTANT, "答"),
                ChatMessage(ChatRole.USER, "再问"),
            ),
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            groups.map { it.role },
        )
    }

    /** 同一轮的连续 assistant 消息仍合并：分段依据是「角色」与「轮次」，不是条数 */
    @Test
    fun sameTurnKeepsAssistantMessagesTogether() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.ASSISTANT, "先看 A", turnId = "t1"),
                ChatMessage(ChatRole.ASSISTANT, "再看 B", turnId = "t1"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].messages.size)
    }

    /**
     * `turnId` 变化且新一轮从 USER 开始时断开。
     *
     * 这是「这一组是一问一答」的判定：没有它，用户连续两轮提问会看起来像一段。
     */
    @Test
    fun turnChangeStartsANewGroup() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.USER, "问一", turnId = "t1"),
                ChatMessage(ChatRole.USER, "问二", turnId = "t2"),
            ),
        )
        assertEquals(2, groups.size, "不同轮次的同一角色消息不能合并")
    }

    /** 系统提示每条自成一组：它不属于任何一方，不该被合并进气泡 */
    @Test
    fun systemMessagesNeverMerge() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "已连接"),
                ChatMessage(ChatRole.SYSTEM, "对方正在处理"),
            ),
        )
        assertEquals(2, groups.size)
    }

    /** 组首的时间取该组第一条：时间属于「这一组什么时候开始的」 */
    @Test
    fun groupTakesTimeFromItsFirstMessage() {
        val groups = groupMessages(
            listOf(
                ChatMessage(ChatRole.USER, "第一句", timeLabel = "10:00"),
                ChatMessage(ChatRole.USER, "第二句", timeLabel = "10:01"),
            ),
        )
        assertEquals("10:00", groups.single().timeLabel)
    }

    @Test
    fun emptyMessagesProduceNoGroups() {
        assertTrue(groupMessages(emptyList()).isEmpty())
    }

    // ---------- 候选清单 ----------

    @Test
    fun pickerCandidatesResolveFromListSource() {
        val candidates = listOf(
            PickerCandidate("s1", "昨天的对话", detail = "12 条", tone = Tone.NEUTRAL),
            PickerCandidate("s2", "今天的对话", disabled = true),
        )
        val root = PickerNode(
            actionId = "switchSession",
            // 清单源没有实例 key：用空 key 的三段式（见 BindingRef.keyless）
            candidates = Bound.Ref("sessionList::items"),
        )
        val resolved = resolveNodes(
            root,
            resolver("sessionList::items" to BoundValue.Candidates(candidates)),
        ) as PickerNode
        assertEquals(candidates, (resolved.candidates as Bound.Static).value)
    }

    /**
     * 把「消息」绑到候选清单上必须退回空清单。
     *
     * [BoundValue.Messages] 与 [BoundValue.Candidates] 刻意分成两种类型，
     * 就是为了让这种误写不会静默生效——两者在视觉上是完全不同的东西。
     */
    @Test
    fun pickerWithMessageBindingFallsBackToEmpty() {
        val root = PickerNode(
            actionId = "switchSession",
            candidates = Bound.Ref("session:main:messages"),
        )
        val resolved = resolveNodes(
            root,
            resolver(
                "session:main:messages" to
                    BoundValue.Messages(listOf(ChatMessage(ChatRole.USER, "你好"))),
            ),
        ) as PickerNode
        assertTrue((resolved.candidates as Bound.Static).value.isEmpty())
    }

    /** 候选与对话的绑定都要被收集：详情页据此决定装配哪些数据源 */
    @Test
    fun collectBindingRefsFindsChatAndPickerSources() {
        val root = ColumnNode(
            children = listOf(
                ChatNode(messages = Bound.Ref("session:main:messages")),
                PickerNode(
                    actionId = "switch",
                    candidates = Bound.Ref("sessionList::items"),
                ),
            ),
        )
        assertEquals(
            listOf(
                BindingRef("session", "main", "messages"),
                // 清单源没有实例 key：key 是空串
                BindingRef("sessionList", "", "items"),
            ),
            collectBindingRefs(root).toList(),
        )
    }
}

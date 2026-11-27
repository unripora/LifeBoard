package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.PickerCandidate
import com.lifeboard.plugin.api.Tone
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 两个绑定源：`session`（含本轮新增的 `turnState`）与 `sessionList`（本轮新增）。
 *
 * 绑定源是「插件写的 binding」与「宿主持有的活数据」之间唯一的一层适配。
 * 它出错的表现是「界面上一片空白」或「字段永远是默认值」，没有任何报错——
 * 所以每个字段都要钉住。
 */
class BindingSourceTest {

    /** 只实现读的假绑定：写路径在本测试里不关心；未知 key 现建一个空态 */
    private class FakeBinding(private val states: Map<String, MutableStateFlow<ConversationUiState>>) :
        ConversationBinding {
        private val fallback = mutableMapOf<String, MutableStateFlow<ConversationUiState>>()

        override fun state(sessionId: String): StateFlow<ConversationUiState> =
            states[sessionId] ?: fallback.getOrPut(sessionId) { MutableStateFlow(ConversationUiState()) }
    }

    @Test
    fun sessionSourceExposesMessagesForEachKey() = runBlocking {
        val s1 = MutableStateFlow(ConversationUiState(messages = listOf(ChatMessage(ChatRole.USER, "一"))))
        val s2 = MutableStateFlow(ConversationUiState(messages = listOf(ChatMessage(ChatRole.USER, "二"))))
        val source = SessionBindingSource(FakeBinding(mapOf("a" to s1, "b" to s2)))

        val a = source.observe("a", SessionBindingSource.FIELD_MESSAGES).value
        val b = source.observe("b", SessionBindingSource.FIELD_MESSAGES).value

        assertEquals("一", (a as BoundValue.Messages).value.single().text)
        assertEquals("二", (b as BoundValue.Messages).value.single().text)
    }

    /**
     * `turnState` 是**机器可读**的状态值。
     *
     * 加它是因为插件原先只能解析 `status` 文案（「正在回复」）来猜状态，
     * 而文案随时可能改。这条测试同时钉住了中文文案与英文状态值之间的映射。
     */
    @Test
    fun sessionSourceExposesTurnStateAsMachineReadable() = runBlocking {
        val cases = mapOf(
            ConversationTurnState.IDLE to "idle",
            ConversationTurnState.WAITING to "waiting",
            ConversationTurnState.ENDED to "ended",
            ConversationTurnState.DISCONNECTED to "disconnected",
        )
        for ((turn, expected) in cases) {
            val state = MutableStateFlow(ConversationUiState(turn = turn))
            val source = SessionBindingSource(FakeBinding(mapOf("k" to state)))
            val value = source.observe("k", SessionBindingSource.FIELD_TURN_STATE).value
            assertEquals(expected, (value as BoundValue.Text).value, "turn=$turn")
        }
    }

    @Test
    fun sessionSourceSearchableFieldsAreDeclared() {
        val source = SessionBindingSource(FakeBinding(emptyMap()))
        assertEquals("session", source.source)
        // 字段名是插件写在 binding 里的字面量，改了就是破坏性变更
        assertEquals(
            setOf("messages", "status", "inputEnabled", "turnState"),
            source.fields,
        )
    }

    @Test
    fun sessionListSourceReadsPublishedItems() {
        val store = SessionListStore()
        val source = SessionListBindingSource(store)

        // 未推送时是空清单（不是 Unavailable）：选择器要显示「暂无可选项」
        val before = source.observe("", SessionListBindingSource.FIELD_ITEMS).value
        assertTrue((before as BoundValue.Candidates).value.isEmpty())

        val candidates = listOf(
            PickerCandidate("dsh-aaa", "项目 A", detail = "运行中", tone = Tone.NEUTRAL),
            PickerCandidate("dsh-bbb", "项目 B", disabled = true),
        )
        store.publish(candidates)

        val after = source.observe("", SessionListBindingSource.FIELD_ITEMS).value
        assertEquals(candidates, (after as BoundValue.Candidates).value)
    }

    /** 清单源没有实例 key：字段名与源名同样是对外契约 */
    @Test
    fun sessionListSourceNameAndFieldAreStable() {
        val source = SessionListBindingSource(SessionListStore())
        assertEquals("sessionList", source.source)
        assertEquals(setOf("items"), source.fields)
    }

    /** 未知字段退回不可用，而不是「空清单」：两者语义不同，前者该整块不渲染 */
    @Test
    fun unknownFieldIsUnavailable() {
        val source = SessionListBindingSource(SessionListStore())
        assertEquals(BoundValue.Unavailable, source.observe("", "nope").value)
    }

    /** 清理后再读不到旧清单：卸载重装不能看到上一个身份的会话 */
    @Test
    fun storeClearDropsItems() {
        val store = SessionListStore()
        store.publish(listOf(PickerCandidate("a", "A")))
        store.clear()
        assertTrue(store.items.value.isEmpty())
    }
}

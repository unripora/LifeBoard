package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatNode
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PickerMode
import com.lifeboard.plugin.api.PickerNode
import com.lifeboard.plugin.api.SelectNode
import com.lifeboard.plugin.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本轮新增 / 变更的节点解析。
 *
 * 解析层是「不可信 JSON」与「封闭契约」之间的唯一关口，每一条兜底都对应
 * 一种插件可能写错的方式。这里只测「写错时降级成什么」，不测正常路径之外
 * 的语义——语义在渲染层与求值层。
 */
class JsNodeParseV5Test {

    private fun parse(json: String) = JsTypeConverter.toUiTree(json).root

    // ---------- chat ----------

    @Test
    fun parsesChatNodeWithRolesAndTurns() {
        val root = parse(
            """
            {"type":"chat","selfName":"我","peerName":"助手","maxHeightDp":240,
             "messages":[
               {"role":"user","text":"你好","id":"m1","turnId":"t1","timeLabel":"10:00"},
               {"role":"assistant","text":"在的","pending":true},
               {"role":"system","text":"已连接"}
             ]}
            """.trimIndent(),
        ) as ChatNode

        val messages = (root.messages as Bound.Static).value
        assertEquals(3, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("m1", messages[0].id)
        assertEquals("t1", messages[0].turnId)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertTrue(messages[1].pending)
        assertEquals(ChatRole.SYSTEM, messages[2].role)
        assertEquals("我", root.selfName)
        assertEquals(240, root.maxHeightDp)
    }

    /** 角色认不出时按 SYSTEM 处理：最弱化的呈现，不会冒充任何一方 */
    @Test
    fun unknownChatRoleFallsBackToSystem() {
        val root = parse(
            """{"type":"chat","messages":[{"role":"robot","text":"?"}]}""",
        ) as ChatNode
        assertEquals(
            ChatRole.SYSTEM,
            (root.messages as Bound.Static).value.single().role,
        )
    }

    @Test
    fun chatMessagesCanBindToSession() {
        val root = parse(
            """{"type":"chat","messages":{"binding":"session:main:messages"}}""",
        ) as ChatNode
        assertEquals(
            "session:main:messages",
            (root.messages as Bound.Ref).ref,
        )
    }

    /**
     * 两段式的绑定写法在这里是**合法**的（无实例 key 的源）。
     *
     * 因此「格式错误」的判定只剩「段数既不是 2 也不是 3」或「有空段」这两种。
     * 四段式仍然被拒，退回空列表而不是让整页解析失败。
     */
    @Test
    fun malformedChatBindingFallsBackToEmpty() {
        val root = parse(
            """{"type":"chat","messages":{"binding":"session:main:messages:extra"}}""",
        ) as ChatNode
        assertTrue((root.messages as Bound.Static).value.isEmpty())
    }

    // ---------- picker ----------

    @Test
    fun parsesPickerWithStaticCandidates() {
        val root = parse(
            """
            {"type":"picker","actionId":"switchSession","title":"选择会话",
             "mode":"inline","selectedIds":["s2"],
             "candidates":[
               {"id":"s1","label":"昨天的对话","detail":"12 条"},
               {"id":"s2","label":"今天的对话"},
               {"label":"缺少 id 的候选"}
             ]}
            """.trimIndent(),
        ) as PickerNode

        val candidates = (root.candidates as Bound.Static).value
        assertEquals(2, candidates.size, "缺 id 的候选必须被丢弃：点了也没人认")
        assertEquals("昨天的对话", candidates[0].label)
        assertEquals("12 条", candidates[0].detail)
        assertEquals(PickerMode.INLINE, root.mode)
        assertEquals(listOf("s2"), root.selectedIds)
    }

    @Test
    fun pickerCandidatesCanBindToListSource() {
        val root = parse(
            """{"type":"picker","actionId":"switch","candidates":{"binding":"sessionList::items"}}""",
        ) as PickerNode
        // 清单源没有实例 key：中间段留空，规范化后仍是同一形态
        assertEquals("sessionList::items", (root.candidates as Bound.Ref).ref)
    }

    /**
     * 两段式绑定仍然是**格式错**。
     *
     * 这条守的是既有契约：`session:only-two` 这种拼错会静默变成「会话 only-two
     * 的字段 two」，排查方向全错。无 key 的源必须写 `source::field`。
     */
    @Test
    fun twoSegmentBindingIsStillRejected() {
        val root = parse(
            """{"type":"picker","actionId":"switch","candidates":{"binding":"sessionList:items"}}""",
        ) as PickerNode
        assertTrue((root.candidates as Bound.Static).value.isEmpty())
    }

    /** 认不出的 mode 退回 MODAL：弹层比就地展开更不容易被误认为静态内容 */
    @Test
    fun unknownPickerModeFallsBackToModal() {
        val root = parse(
            """{"type":"picker","actionId":"switch","mode":"popup","candidates":[]}""",
        ) as PickerNode
        assertEquals(PickerMode.MODAL, root.mode)
    }

    /** 没有 actionId 的选择器点了没反应，与 select 的处理一致：整块不出 */
    @Test
    fun pickerWithoutActionIdIsNotRendered() {
        assertNull(parse("""{"type":"picker","candidates":[{"id":"a","label":"A"}]}"""))
    }

    // ---------- 既有节点的修订 ----------

    @Test
    fun textHonoursCardFlag() {
        val default = parse("""{"type":"text","body":"A"}""") as TextNode
        assertTrue(default.card, "默认仍套卡片，保持既有观感")

        val flat = parse("""{"type":"text","body":"A","card":false}""") as TextNode
        assertTrue(!flat.card)
    }

    @Test
    fun listParsesItemActionAndPayloads() {
        val root = parse(
            """
            {"type":"list","itemActionId":"pick",
             "itemPayloads":["p1","p2"],
             "items":[[{"type":"text","body":"A"}],[{"type":"text","body":"B"}]]}
            """.trimIndent(),
        ) as ListNode
        assertEquals("pick", root.itemActionId)
        assertEquals(listOf("p1", "p2"), root.itemPayloads)
    }

    @Test
    fun selectParsesSelectedValuesAndOptionPayload() {
        val root = parse(
            """
            {"type":"select","actionId":"pick","multiple":true,
             "selectedValues":["a"],
             "options":[{"value":"a","label":"A","payload":"{\"id\":1}"}]}
            """.trimIndent(),
        ) as SelectNode
        assertEquals(listOf("a"), root.selectedValues)
        assertEquals("""{"id":1}""", root.options.single().payload)
    }

    /**
     * `code.language` 已从契约删除：插件仍写了也不报错，只是不再有任何效果。
     *
     * 这里断言「能正常解析」而非「抛异常」——旧包不该因为一个多余字段整页打不开，
     * 它会走「此版本暂不支持」以外的正常路径，只是少了一个从未生效的属性。
     */
    @Test
    fun codeLanguageIsIgnoredNotRejected() {
        val root = parse("""{"type":"code","code":"val a = 1","language":"kotlin"}""")
        assertTrue(root is com.lifeboard.plugin.api.CodeNode)
    }

    /** 未知 type 仍走占位，不会被当成新节点静默吞掉 */
    @Test
    fun unknownTypeStillBecomesUnsupported() {
        val root = parse("""{"type":"conversation"}""")
        assertTrue(root is com.lifeboard.plugin.api.UnsupportedNode)
    }
}

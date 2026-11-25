package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.BindingRef
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ChatMessage
import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 绑定求值的五条合并规则。
 *
 * 这些规则决定了「数据没来时界面长什么样」，而宿主侧的每个渲染分支都
 * 依赖它，所以必须钉死。`resolveNodes` 是纯函数，可以不启 Compose 直接测。
 */
class BoundNodeTreeTest {

    /** 一个按 ref 返回预设值的求值器；未列出的 ref 一律不可用 */
    private fun resolver(vararg pairs: Pair<String, BoundValue>): (BindingRef) -> BoundValue {
        val map = pairs.associate { (raw, value) -> requireNotNull(BindingRef.parse(raw)) to value }
        return { ref -> map[ref] ?: BoundValue.Unavailable }
    }

    private fun text(body: String, id: String? = null): TextNode =
        TextNode(body = Bound.Static(body), id = id)

    @Test
    fun staticValuesPassThroughUnchanged() {
        val root = ColumnNode(children = listOf(text("A"), text("B")))
        val resolved = resolveNodes(root, resolver())
        assertEquals(root, resolved)
    }

    /**
     * 绑定不可用时该节点**整块不渲染**。
     *
     * 渲染一个空卡片比不显示更让人困惑：用户会以为「数据是空的」，
     * 而实际是「数据源没接上」。
     */
    @Test
    fun unavailableBindingRemovesTheNode() {
        val root = TextNode(body = Bound.Ref("session:main:status"))
        assertNull(resolveNodes(root, resolver()))
    }

    /** 一个卡片里三块、其中一块没数据：另外两块仍要显示 */
    @Test
    fun unavailableBindingDoesNotRemoveSiblings() {
        val root = ColumnNode(
            children = listOf(
                text("A"),
                TextNode(body = Bound.Ref("session:main:status")),
                text("B"),
            ),
        )
        val resolved = resolveNodes(root, resolver()) as ColumnNode
        assertEquals(2, resolved.children.size)
        assertEquals(
            listOf("A", "B"),
            resolved.children.map { ((it as TextNode).body as Bound.Static).value },
        )
    }

    @Test
    fun textBindingResolvesToHostString() {
        val root = TextNode(body = Bound.Ref("session:main:status"))
        val resolved = resolveNodes(root, resolver("session:main:status" to BoundValue.Text("已连接")))
        assertEquals("已连接", ((resolved as TextNode).body as Bound.Static).value)
    }

    @Test
    fun statusCardBindingResolvesToHostString() {
        val root = StatusCardNode("电脑", Bound.Ref("session:main:status"))
        val resolved = resolveNodes(root, resolver("session:main:status" to BoundValue.Text("未连接")))
        assertEquals("未连接", ((resolved as StatusCardNode).value as Bound.Static).value)
    }

    /**
     * 类型不符（把字符串绑到消息列表上）退回空列表，而不是强转或崩溃。
     *
     * 插件可能把 `session:main:status` 绑到 `items` 上，这时渲染一个空列表
     * + 空态提示，比抛异常让整页打不开好。
     */
    @Test
    fun mistypedListBindingFallsBackToEmpty() {
        val root = ListNode(items = Bound.Ref("session:main:status"))
        val resolved = resolveNodes(root, resolver("session:main:status" to BoundValue.Text("x"))) as ListNode
        assertTrue((resolved.items as Bound.Static).value.isEmpty())
    }

    /**
     * 消息绑定被翻译成「每项一段文本」：列表项复用通用内容节点，
     * 不为「消息」再造一种节点类型。
     */
    @Test
    fun messageBindingBecomesListItems() {
        val messages = listOf(
            ChatMessage(ChatRole.USER, "你好", timeLabel = "10:00"),
            ChatMessage(ChatRole.ASSISTANT, "在的"),
        )
        val root = ListNode(items = Bound.Ref("session:main:messages"))
        val resolved = resolveNodes(
            root,
            resolver("session:main:messages" to BoundValue.Messages(messages)),
        ) as ListNode
        val items = (resolved.items as Bound.Static).value
        assertEquals(2, items.size)
        val first = items[0][0] as TextNode
        assertEquals("10:00", first.title)
        assertEquals("你好", (first.body as Bound.Static).value)
    }

    /**
     * 布尔绑定不可用时退回「可用」。
     *
     * 禁用输入框会让用户以为连接断了；连接状态本身由状态文案表达，
     * 不该连带把输入框锁死。
     */
    @Test
    fun unavailableFlagBindingFallsBackToEnabled() {
        val root = InputNode("send", enabled = Bound.Ref("session:main:inputEnabled"))
        val resolved = resolveNodes(root, resolver()) as InputNode
        assertEquals(true, (resolved.enabled as Bound.Static).value)
    }

    @Test
    fun flagBindingResolvesToHostValue() {
        val root = InputNode("send", enabled = Bound.Ref("session:main:inputEnabled"))
        val resolved = resolveNodes(
            root,
            resolver("session:main:inputEnabled" to BoundValue.Flag(false)),
        ) as InputNode
        assertEquals(false, (resolved.enabled as Bound.Static).value)
    }

    @Test
    fun collectBindingRefsFindsNestedRefs() {
        val root = ColumnNode(
            children = listOf(
                Collapsible(
                    children = listOf(
                        TextNode(body = Bound.Ref("session:main:status")),
                        ListNode(items = Bound.Ref("session:main:messages")),
                    ),
                ),
                InputNode("send", submitTo = BindingRef("session", "main", "input")),
            ),
        )
        val refs = collectBindingRefs(root)
        assertEquals(
            setOf(
                BindingRef("session", "main", "status"),
                BindingRef("session", "main", "messages"),
                BindingRef("session", "main", "input"),
            ),
            refs,
        )
    }

    @Test
    fun collectBindingRefsOnNullIsEmpty() {
        assertTrue(collectBindingRefs(null).isEmpty())
    }

    /** 折叠块里也能被找到：这正是通用绑定相对专用会话节点的优势 */
    private fun Collapsible(children: List<PluginNode>): PluginNode =
        com.lifeboard.plugin.api.CollapsibleNode(title = "详情", children = children)

    @Test
    fun unknownSourceDegradesToUnavailable() {
        // source 未知时解析层仍能解析出三段式，但求值器查不到 → 不可用 → 不渲染
        val root = TextNode(body = Bound.Ref("weather:beijing:temp"))
        assertNull(resolveNodes(root, resolver()))
        assertNotNull(Bound.Ref<Any>("weather:beijing:temp"))
    }
}

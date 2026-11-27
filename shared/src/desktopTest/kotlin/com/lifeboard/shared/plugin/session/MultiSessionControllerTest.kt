package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.shared.ui.plugin.detail.ConversationTurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多会话隔离。
 *
 * 本轮修的缺陷是：[ConversationBinding.state(sessionId)] 与
 * `submit(sessionId, text)` 签名接受 sessionId 却**忽略它**，永远返回/操作
 * 第一条会话。后果是插件写多个 key 也看不出区别，用户切会话时消息互相串。
 *
 * 这里逐条钉死「按 key 隔离」：消息不串、状态不串、提交只落在当前会话上、
 * 切会话不重启连接（认证只做一次）。
 */
class MultiSessionControllerTest {

    private class FakeEndpoint : SessionEndpoint {
        val events = CopyOnWriteArrayList<SessionEvent>()
        val deltaFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var uiListener: ((SessionUiEvent) -> Unit)? = null

        /** 每次 start 收到的 sessionKey，用于验证「切会话会通知插件」 */
        val sessionKeys = CopyOnWriteArrayList<String>()
        var startCalls = 0
        var stopCalls = 0

        /** 让 start 失败，用于验证切换失败时回滚 */
        var failStart = false

        override suspend fun start(config: SessionConfig): Boolean {
            startCalls += 1
            sessionKeys += config.options[SESSION_KEY] ?: ""
            return !failStart
        }

        override suspend fun event(event: SessionEvent): SessionAction {
            events += event
            return SessionAction.Continue
        }

        override suspend fun stop() {
            stopCalls += 1
        }

        override fun setListener(listener: ((SessionUiEvent) -> Unit)?) {
            uiListener = listener
        }

        override val deltas: Flow<String> = deltaFlow
    }

    private fun controller(endpoint: FakeEndpoint) =
        PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))

    private companion object {
        const val SESSION_KEY = "sessionKey"
    }

    @Test
    fun differentKeysKeepSeparateMessageTables() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        c.apply(SessionUiEvent.Message(ChatRole.USER, "会话一的消息"))

        c.start("s2")
        c.apply(SessionUiEvent.Message(ChatRole.USER, "会话二的消息"))

        assertEquals(
            listOf("会话一的消息"),
            c.state("s1").value.messages.map { it.text },
            "切到 s2 后，s1 的消息不能被改写",
        )
        assertEquals(
            listOf("会话二的消息"),
            c.state("s2").value.messages.map { it.text },
            "s2 只应收到自己那条消息",
        )
    }

    @Test
    fun unknownKeyReturnsItsOwnEmptyState() {
        val c = controller(FakeEndpoint())
        // 从未启动过的 key 拿到的是空态，而不是「别的会话的状态」
        assertEquals(emptyList(), c.state("never-started").value.messages)
        assertFalse(c.state("never-started").value.inputEnabled)
    }

    /** 首次启动要把 key 交给插件：插件据此决定订阅哪一条会话 */
    @Test
    fun startPassesSessionKeyToPlugin() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        assertEquals(listOf("s1"), endpoint.sessionKeys)
    }

    /**
     * 切会话**不重启连接**：控制器只换 key、再通知插件一次，
     * 不调用 `stop()`。这正是「认证一次、多个历史对话」的实现方式。
     */
    @Test
    fun switchingSessionsDoesNotRestartTheConnection() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        c.start("s2")
        c.start("s3")

        assertEquals(0, endpoint.stopCalls, "切会话不该断开连接")
        assertEquals(
            listOf("s1", "s2", "s3"),
            endpoint.sessionKeys,
            "每次切换都要告诉插件现在看的是哪一条",
        )
        assertEquals("s3", c.activeSessionKey)
    }

    /** 同一条会话重复 start 是幂等的：插件只被调起一次 */
    @Test
    fun startingTheSameSessionTwiceCallsPluginOnce() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        assertTrue(c.start("s1"))
        assertTrue(c.start("s1"))
        assertEquals(1, endpoint.startCalls)
    }

    /** 提交只落在当前会话：在非活跃 key 里打字不该发到别人那儿 */
    @Test
    fun submitOnInactiveKeyIsIgnored() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        c.start("s2")

        c.submit("s1", "发给会话一的话")

        assertTrue(endpoint.events.isEmpty(), "非活跃会话的提交必须被忽略")

        c.submit("s2", "发给会话二的话")
        assertEquals(1, endpoint.events.size)
        assertEquals("发给会话二的话", endpoint.events.first().payload)
    }

    /**
     * 提交后该会话进入等待态，其余会话的状态不受影响。
     *
     * 提交后状态由协程推进（先置 WAITING，插件返回后再回到 IDLE），
     * 因此这里在同一帧内读两次一定读不到中间态。改用「插件侧收到的输入」
     * 判断路由，用「另一条会话的状态不变」判断隔离。
     */
    @Test
    fun waitingStateOnlyAffectsTheCurrentSession() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        c.start("s2")

        c.submit("s2", "问一句")

        assertEquals(1, endpoint.events.size, "只有 s2 的那句话被发出")
        assertEquals("问一句", endpoint.events.first().payload)
        assertEquals(
            ConversationTurnState.IDLE,
            c.state("s1").value.turn,
            "s1 没有收发任何东西，状态必须保持空闲",
        )
        assertEquals(
            emptyList(),
            c.state("s1").value.messages,
            "发给 s2 的话不能出现在 s1 的消息里",
        )
    }

    /** 切换失败要退回原 key，不能把用户留在一个接不通的会话上 */
    @Test
    fun failedSwitchRollsBackToThePreviousKey() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        endpoint.failStart = true

        assertFalse(c.start("s2"))
        assertEquals("s1", c.activeSessionKey, "切换失败必须回到原来的会话")
        assertEquals(ConversationTurnState.DISCONNECTED, c.state("s2").value.turn)
    }

    /** `sessionKey` 是宿主与插件之间的保留键，插件自己写的同名键必须被覆盖 */
    @Test
    fun hostSessionKeyOverridesPluginSuppliedOne() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1", SessionConfig(options = mapOf(SESSION_KEY to "伪造的值")))
        assertEquals(listOf("s1"), endpoint.sessionKeys)
    }

    /** 清理后所有 key 的状态都要消失：否则卸载重装会看到上一个身份的历史 */
    @Test
    fun clearAllDropsEveryKey() = runBlocking {
        val endpoint = FakeEndpoint()
        val c = controller(endpoint)

        c.start("s1")
        c.apply(SessionUiEvent.Message(ChatRole.USER, "旧消息"))
        c.clearAll()

        assertEquals(emptyList(), c.state("s1").value.messages)
        assertFalse(c.isActive)
    }
}

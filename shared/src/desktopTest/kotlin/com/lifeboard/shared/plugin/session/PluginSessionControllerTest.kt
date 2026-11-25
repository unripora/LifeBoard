package com.lifeboard.shared.plugin.session

import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话控制器的行为验证。
 *
 * 重点不是「能不能存消息」，而是四条容易退化的性质：
 * 启动失败不假装已连接、用户输入先落界面再发插件、流式片段接在同一条回复上、
 * 插件主动关闭后输入必须禁用。
 */
class PluginSessionControllerTest {

    private class FakeEndpoint(
        private val startResult: Boolean = true,
    ) : SessionEndpoint {
        val events = CopyOnWriteArrayList<SessionEvent>()
        val deltaFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var uiListener: ((SessionUiEvent) -> Unit)? = null
        var stopped = false

        /** 模拟插件在 `sessionStart` 里同步推送事件 */
        var onStart: (() -> Unit)? = null

        /** 模拟 `sessionEvent` 抛异常，用于验证「发送失败不结束会话」 */
        var eventError: Throwable? = null

        /** 真正执行 start 的次数，用于验证并发去重 */
        var startCalls = 0

        override suspend fun start(config: SessionConfig): Boolean {
            startCalls += 1
            if (startResult) onStart?.invoke()
            return startResult
        }

        override suspend fun event(event: SessionEvent): SessionAction {
            eventError?.let { throw it }
            events += event
            return SessionAction.Continue
        }

        override suspend fun stop() {
            stopped = true
        }

        override fun setListener(listener: ((SessionUiEvent) -> Unit)?) {
            this.uiListener = listener
        }

        override val deltas: Flow<String> = this.deltaFlow
    }

    @Test
    fun startFailureKeepsInputDisabled() = runBlocking {
        val endpoint = FakeEndpoint(startResult = false)
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))

        assertFalse(controller.start("main"))
        assertFalse(controller.state("main").value.inputEnabled)
        assertEquals(null, endpoint.uiListener)
    }

    @Test
    fun startEnablesInputAndStopClearsSession() = runBlocking {
        val endpoint = FakeEndpoint()
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))

        assertTrue(controller.start("main"))
        assertTrue(controller.state("main").value.inputEnabled)

        controller.stop()
        assertFalse(controller.state("main").value.inputEnabled)
        assertTrue(endpoint.stopped)
        assertEquals(null, endpoint.uiListener)
    }

    @Test
    fun submitAppendsUserMessageThenRestoresInput() = runBlocking {
        val endpoint = FakeEndpoint()
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))
        controller.start("main")

        controller.submit("main", "  你好  ")

        // Unconfined 下 launch 会立即执行完，最终应为「已连接 + 可输入」
        withTimeout(2_000) {
            while (!controller.state("main").value.inputEnabled) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(ChatRole.USER, controller.state("main").value.messages.first().role)
        assertEquals("你好", controller.state("main").value.messages.first().text)
        assertEquals("input", endpoint.events.first().type)
        assertEquals("你好", endpoint.events.first().payload)
    }

    @Test
    fun deltasAppendToTheSameAssistantMessage() = runBlocking {
        val endpoint = FakeEndpoint()
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))
        controller.start("main")

        controller.apply(SessionUiEvent.Delta("你"))
        controller.apply(SessionUiEvent.Delta("好"))

        val messages = controller.state("main").value.messages
        assertEquals(1, messages.size)
        assertEquals(ChatRole.ASSISTANT, messages[0].role)
        assertEquals("你好", messages[0].text)
    }

    @Test
    fun eventsPushedDuringStartAreKept() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            onStart = {
                uiListener?.invoke(SessionUiEvent.Message(ChatRole.ASSISTANT, "历史消息"))
                uiListener?.invoke(SessionUiEvent.Status("已连接", inputEnabled = true))
                uiListener?.invoke(SessionUiEvent.Delta("…"))
            }
        }
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))

        assertTrue(controller.start("main"))

        val state = controller.state("main").value
        assertEquals(
            listOf("历史消息", "…"),
            state.messages.map { it.text },
            "启动阶段推的消息与增量都不能被「已连接」的整体赋值冲掉",
        )
        assertEquals("已连接", state.statusText)
        assertTrue(state.inputEnabled)
    }

    @Test
    fun closedEventDisablesInputAndUnregistersListener() = runBlocking {
        val endpoint = FakeEndpoint()
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))
        controller.start("main")

        controller.apply(SessionUiEvent.Closed("对方断开了"))

        val state = controller.state("main").value
        assertFalse(state.inputEnabled)
        assertEquals("会话已结束", state.statusText)
        assertEquals("对方断开了", state.messages.last().text)
        assertEquals(null, endpoint.uiListener)
    }

    @Test
    fun sendFailureKeepsSessionAlive(): Unit = runBlocking {
        val endpoint = FakeEndpoint().apply { eventError = IllegalStateException("网络断开") }
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))
        controller.start("main")

        controller.submit("main", "你好")

        withTimeout(2_000) {
            while (!controller.state("main").value.inputEnabled) {
                kotlinx.coroutines.delay(10)
            }
        }
        val state = controller.state("main").value
        // 插件抛一次异常不等于用户要结束会话：输入必须恢复，用户才能重试
        assertTrue(state.inputEnabled, "发送失败后输入应恢复可用")
        assertEquals("已连接", state.statusText)
        assertTrue(
            state.messages.any { it.text.contains("发送失败") },
            "应有一条发送失败的提示，实际：${state.messages.map { it.text }}",
        )
        assertTrue(controller.isActive, "会话仍在活跃")
    }

    @Test
    fun concurrentStartsOnlyBeginOnce(): Unit = runBlocking {
        val endpoint = FakeEndpoint()
        val controller = PluginSessionController({ endpoint }, CoroutineScope(Dispatchers.Unconfined))

        // 详情页 load() 与动作后的补启动会同时发起；插件只应被真正调起一次。
        // 第二次返回 true 是正确语义（会话确实可用），但不能再调一次插件，
        // 否则会多出一条 deltaJob 与一次 setListener，消息被重复追加。
        val results = listOf(
            async<Boolean> { controller.start("main") },
            async<Boolean> { controller.start("main") },
        ).awaitAll()

        assertEquals(1, endpoint.startCalls, "插件只应被启动一次，实际：$results")
        assertTrue(controller.isActive, "会话仍在活跃")
    }
}

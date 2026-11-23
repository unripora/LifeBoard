package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ChatRole
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话生命周期（M3）的行为验证。
 *
 * 三条要守住的性质：
 * 1. **老插件零影响**：没实现 `sessionStart/Event/Stop` 时按默认值降级，不抛异常
 * 2. **无活动超时**：调用可以跑得比超时上限久，但停止推进后必须被放弃
 * 3. **后台循环不挡路**：插件在 `sessionStart` 里起的常驻循环不能堵住短时调用
 *
 * 第 3 条是本次改动的核心：会话的消息收发靠插件自己的 `while (await ...)` 循环，
 * 循环由运行时在专用线程上持续推进；只要这个循环不以「一次调用」的形态占住锁，
 * `health()` 之类的短时调用就能照常插进来。
 */
class JsSessionLifecycleTest {

    private lateinit var tempDir: File
    private lateinit var fakeContext: FakePluginContext
    private var harness: Harness? = null
    private var server: MockWebServer? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("js-session").toFile()
        fakeContext = FakePluginContext(
            pluginId = PLUGIN_ID,
            dir = File(tempDir, "data").apply { mkdirs() },
        )
    }

    @After
    fun tearDown() {
        harness?.close()
        server?.shutdown()
        tempDir.deleteRecursively()
    }

    // ---- 默认实现 ----

    @Test
    fun sessionMethodsDegradeWhenPluginDoesNotImplementThem() = runBlocking {
        val h = buildHarness("export function health() { return { state: 'ok' } }")

        assertFalse(
            "未实现 sessionStart 时必须报告「不支持」，而不是假装已启动",
            h.plugin.onSessionStart(fakeContext, SessionConfig(mapOf("gateway" to "wss://x"))),
        )
        assertEquals(
            SessionAction.Continue,
            h.plugin.onSessionEvent(fakeContext, SessionEvent("input", "{}")),
        )
        h.plugin.onSessionStop(fakeContext)
    }

    // ---- 参数与返回值 ----

    @Test
    fun sessionArgumentsAndStopReasonReachThePlugin() = runBlocking {
        val h = buildHarness(
            """
            export function sessionStart(config) {
                ctx.credentials.put('session.gateway', config.gateway || '')
                return true
            }
            export function sessionEvent(ev) {
                if (ev.type === 'cancel') return { action: 'stop', reason: ev.payload }
                return { action: 'continue' }
            }
            export function sessionStop() {
                ctx.credentials.put('session.stopped', '1')
            }
            """.trimIndent(),
        )

        assertTrue(
            h.plugin.onSessionStart(
                fakeContext,
                SessionConfig(mapOf("gateway" to "wss://relay.example")),
            ),
        )
        assertEquals("wss://relay.example", fakeContext.credentials.get("session.gateway"))

        assertEquals(
            SessionAction.Continue,
            h.plugin.onSessionEvent(fakeContext, SessionEvent("input", "你好")),
        )
        assertEquals(
            SessionAction.Stop("用户取消"),
            h.plugin.onSessionEvent(fakeContext, SessionEvent("cancel", "用户取消")),
        )

        h.plugin.onSessionStop(fakeContext)
        assertEquals("1", fakeContext.credentials.get("session.stopped"))
    }

    // ---- 超时语义 ----

    @Test
    fun sessionEventGivesUpWhenNothingProgresses() = runBlocking {
        // 服务端收下请求但永不回包：插件侧会一直挂在 await 上，属于「无进展」
        val srv = startServer { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val h = buildHarness(
            """
            export function sessionEvent(ev) {
                return ctx.http.get('${srv.url("/hang")}').then(function () {
                    return { action: 'stop', reason: '不应该到达' }
                })
            }
            """.trimIndent(),
            inactivityTimeoutMillis = 300L,
        )

        val started = System.currentTimeMillis()
        val result = withTimeout(10_000) {
            h.plugin.onSessionEvent(fakeContext, SessionEvent("input", "hi"))
        }

        assertEquals(
            "无进展时必须放弃并回到安全默认值（会话继续），而不是把异常抛给宿主",
            SessionAction.Continue,
            result,
        )
        assertTrue(
            "放弃得太晚：${System.currentTimeMillis() - started} ms",
            System.currentTimeMillis() - started < 5_000,
        )
    }

    @Test
    fun sessionCallOutlivesTheLimitAsLongAsItKeepsProgressing() = runBlocking {
        // 每个请求 120 ms 落地一次，总耗时 ~600 ms，远大于 250 ms 的无活动上限。
        // 只要计时按「无进展」算，这次调用就应该正常完成。
        val srv = startServer {
            MockResponse().setBody("{}").setBodyDelay(120, TimeUnit.MILLISECONDS)
        }
        val h = buildHarness(
            """
            export async function sessionEvent(ev) {
                for (let i = 0; i < 5; i++) {
                    await ctx.http.get('${srv.url("/ok")}')
                }
                return { action: 'stop', reason: 'done' }
            }
            """.trimIndent(),
            inactivityTimeoutMillis = 250L,
        )

        val started = System.currentTimeMillis()
        val result = withTimeout(20_000) {
            h.plugin.onSessionEvent(fakeContext, SessionEvent("input", "hi"))
        }

        assertEquals(SessionAction.Stop("done"), result)
        assertTrue(
            "用例前提不成立：总耗时没有超过无活动上限",
            System.currentTimeMillis() - started > 250,
        )
    }

    // ---- 后台循环 ----

    @Test
    fun backgroundSessionLoopDoesNotBlockShortCalls() = runBlocking {
        val srv = startServer {
            MockResponse().setBody("{}").setBodyDelay(20, TimeUnit.MILLISECONDS)
        }
        val h = buildHarness(
            """
            globalThis.ticks = 0
            export function sessionStart() {
                void (async function loop() {
                    while (true) {
                        await ctx.http.get('${srv.url("/tick")}')
                        globalThis.ticks++
                    }
                })()
                return true
            }
            export function health() { return { state: 'ok' } }
            export function sessionEvent(ev) {
                return { action: 'stop', reason: 'ticks=' + globalThis.ticks }
            }
            export function sessionStop() {}
            """.trimIndent(),
        )

        assertTrue(h.plugin.onSessionStart(fakeContext, SessionConfig()))

        // 后台循环已经在跑，短时调用必须能立刻插进来
        val started = System.currentTimeMillis()
        h.plugin.health(fakeContext)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("短时调用被后台循环堵住了：$elapsed ms", elapsed < 3_000)

        // 再等一会儿，确认循环真的在推进（而不是停在第一个 await 上）
        delay(500)
        val action = h.plugin.onSessionEvent(fakeContext, SessionEvent("probe", ""))
        val ticks = (action as SessionAction.Stop).reason.removePrefix("ticks=").toInt()
        assertTrue("后台循环没有推进，ticks=$ticks", ticks > 0)

        h.plugin.onSessionStop(fakeContext)
    }

    // ---- 主动推送 ----

    @Test
    fun sessionEmitPushesUiEventsToListener() = runBlocking {
        val h = buildHarness(
            """
            export function sessionStart(config) {
                ctx.session.emit(JSON.stringify({ type: 'message', role: 'assistant', text: '你好' }));
                ctx.session.emit(JSON.stringify({ type: 'status', text: '正在回复', inputEnabled: false }));
                ctx.session.emit(JSON.stringify({ type: 'delta', text: '片' }));
                return true;
            }
            export function sessionStop() {}
            """.trimIndent(),
        )

        val events = mutableListOf<SessionUiEvent>()
        h.plugin.setSessionUiListener { events += it }

        assertTrue(h.plugin.onSessionStart(fakeContext, SessionConfig()))
        h.plugin.onSessionStop(fakeContext)
        h.plugin.setSessionUiListener(null)

        assertEquals(3, events.size)
        assertEquals(SessionUiEvent.Message(ChatRole.ASSISTANT, "你好"), events[0])
        assertEquals(SessionUiEvent.Status("正在回复", inputEnabled = false), events[1])
        assertEquals(SessionUiEvent.Delta("片"), events[2])
    }

    // ---- 脚手架 ----

    private class Harness(
        val plugin: JsSourcePlugin,
        private val runtime: JsPluginRuntime,
        private val http: JsHttpBridge,
    ) : AutoCloseable {
        override fun close() {
            http.cancelAll()
            runtime.close()
        }
    }

    private fun buildHarness(
        mainJs: String,
        inactivityTimeoutMillis: Long = 60_000L,
    ): Harness {
        val runtime = JsPluginRuntime.create(PLUGIN_ID)
        val contextRef = arrayOfNulls<JsPluginContextBridge>(1)
        val httpRef = arrayOfNulls<JsHttpBridge>(1)
        // 会话推送桥要在插件实例创建后才能转发，用 holder 兜住先后顺序
        val pluginRef = arrayOfNulls<JsSourcePlugin>(1)
        val httpTokenSeq = AtomicLong(0)

        val started = runtime.start(
            modules = mapOf("main.js" to mainJs),
            entry = "main.js",
        ) { _ ->
            val bridge = JsPluginContextBridge(
                contextProvider = { fakeContext },
                pluginIdProvider = { PLUGIN_ID },
            )
            // 超时与后台循环两条用例要靠真实的 HTTP 挂起/落地来推进，
            // 只装 context 桥的话 ctx.http 不存在，测的就不是同一件事了
            val http = JsHttpBridge(
                runtime = runtime,
                tokenProvider = { "session-test#${httpTokenSeq.incrementAndGet()}" },
            )
            contextRef[0] = bridge
            httpRef[0] = http
            val session = JsSessionBridge { event -> pluginRef[0]?.emitSessionUiEvent(event) }
            CompositeHostDispatcher(
                listOf(
                    bridge.functionNames() to bridge,
                    http.functionNames() to http,
                    session.functionNames() to session,
                ),
            )
        }
        check(started.isSuccess) {
            "插件启动失败：${started.exceptionOrNull()?.message}"
        }

        val plugin = JsSourcePlugin(
            id = PLUGIN_ID,
            displayName = "会话测试插件",
            credentialSpec = CredentialSpec.NONE,
            metrics = listOf(MetricSpec("steps", MetricBucket.STEPS, "步数")),
            runtime = runtime,
            entryModule = "main.js",
            contextBridge = contextRef[0]!!,
            // 桥接已持有 fakeContext，测试里不再需要逐次绑定
            bindContext = { },
            inactivityTimeoutMillis = inactivityTimeoutMillis,
        )
        pluginRef[0] = plugin
        return Harness(plugin, runtime, httpRef[0]!!).also { harness = it }
    }

    private fun startServer(responder: (RecordedRequest) -> MockResponse): MockWebServer {
        val srv = MockWebServer()
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = responder(request)
        }
        srv.start()
        server = srv
        return srv
    }

    private companion object {
        const val PLUGIN_ID = "session-test"
    }
}
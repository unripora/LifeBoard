package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.Bound
import com.lifeboard.plugin.api.ColumnNode
import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.InputNode
import com.lifeboard.plugin.api.ListNode
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.RowNode
import com.lifeboard.plugin.api.StatusCardNode
import com.lifeboard.plugin.api.TextNode
import com.lifeboard.plugin.api.UnsupportedNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JS 插件作为普通 [com.lifeboard.plugin.api.SourcePlugin] 的契约验证。
 *
 * ## 为什么在共享层而不是各端各测一份
 *
 * 被测对象 [JsSourcePlugin] 本身就在 `jvmMain`，它是「JS 脚本」与「插件契约」之间
 * 唯一的翻译层。这一层错了，Android 和桌面端会一起错，两边各写一份等价测试
 * 只是把同一个断言抄两遍。放在 `desktopTest` 里跑，两端的信任是同一份证据。
 *
 * ## 覆盖的分层
 *
 * - **契约翻译**：登录三种结果、采集、健康度、详情页、操作回调各分支能否正确映射
 * - **可选实现**：契约里 `detailUi` / `onAction` 是可选函数，插件不写时必须优雅降级
 * - **异常兜底**：第三方插件未必守规矩，抛异常不能打到宿主上
 * - **ctx 桥接**：凭据、文件、原始记录、历史指标四条通道的真实行为
 *
 * 最后一条是重点：`ctx.files.read('../../../etc/hosts')` 这类目录穿越必须被拦住。
 * 插件是第三方代码，这条防线不测就等于没有。
 */
class JsSourcePluginContractTest {

    private lateinit var tempDir: File
    private lateinit var fakeContext: FakePluginContext

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("js-plugin-contract").toFile()
        fakeContext = FakePluginContext(
            pluginId = PLUGIN_ID,
            dir = File(tempDir, "data").apply { mkdirs() },
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * 构造被测插件：真实 [JsPluginRuntime] + 真实桥接 + 假的 [PluginContext]。
     *
     * 用真实运行时而不是假实现：线程模型是这里最容易出错的点
     * （QuickJS 上下文不能跨线程），只有跑真实实现才能验证到。
     */
    private fun buildPlugin(
        mainJs: String,
        extraModules: Map<String, String> = emptyMap(),
        metrics: List<MetricSpec> = listOf(
            MetricSpec("steps", MetricBucket.STEPS, "步数"),
        ),
        credentialSpec: CredentialSpec = CredentialSpec.NONE,
    ): JsSourcePlugin {
        val runtime = JsPluginRuntime.create(PLUGIN_ID)
        val contextRef = arrayOfNulls<JsPluginContextBridge>(1)
        // 增量桥接要在插件实例之前装好，而它的接收方正是那个实例：
        // 入口脚本顶层就 emit 时实例还不存在，按「无人接收」丢弃
        val streamRef = arrayOfNulls<JsSourcePlugin>(1)

        val startResult = runtime.start(
            modules = extraModules + mapOf("main.js" to mainJs),
            entry = "main.js",
        ) { _ ->
            val bridge = JsPluginContextBridge(
                contextProvider = { fakeContext },
                pluginIdProvider = { PLUGIN_ID },
            )
            contextRef[0] = bridge
            val stream = JsStreamBridge { text -> streamRef[0]?.emitStreamDelta(text) }
            CompositeHostDispatcher(
                listOf(bridge.functionNames() to bridge, stream.functionNames() to stream),
            )
        }

        check(startResult.isSuccess) {
            "插件启动失败：${startResult.exceptionOrNull()?.message}"
        }

        return JsSourcePlugin(
            id = PLUGIN_ID,
            displayName = "测试插件",
            credentialSpec = credentialSpec,
            metrics = metrics,
            runtime = runtime,
            entryModule = "main.js",
            contextBridge = contextRef[0]!!,
            // 真实加载器在这里注入「本次调用的 context」，测试直接构造时
            // 桥接已经持有 fakeContext，无需再绑定
            bindContext = { },
        ).also { streamRef[0] = it }
    }

    // ---- 登录 ----

    @Test
    fun loginReturnsSuccess() {
        val plugin = buildPlugin(
            """
            export async function login(inputs) {
                ctx.credentials.put('token', 'abc')
                return { type: 'success' }
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertTrue("实际结果：$result", result is LoginResult.Success)
        assertEquals("abc", runBlocking { fakeContext.credentials.get("token") })
    }

    @Test
    fun loginReturnsNeedInputWithCaptcha() {
        val plugin = buildPlugin(
            """
            export async function login(inputs) {
                return {
                    type: 'needInput',
                    prompt: '需要输入图片验证码',
                    fields: [{ name: 'captcha', displayName: '验证码', hint: '4 位数字' }],
                    captchaImage: 'data:image/png;base64,AAAA'
                }
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertTrue("实际结果：$result", result is LoginResult.NeedInput)
        val need = result as LoginResult.NeedInput
        assertEquals("需要输入图片验证码", need.prompt)
        assertEquals(1, need.fields.size)
        assertEquals("captcha", need.fields[0].name)
        assertEquals("data:image/png;base64,AAAA", need.captchaImage)
    }

    @Test
    fun loginReturnsFailure() {
        val plugin = buildPlugin(
            """
            export async function login(inputs) {
                return { type: 'failure', message: '账号或密码错误' }
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertTrue(result is LoginResult.Failure)
        assertEquals("账号或密码错误", (result as LoginResult.Failure).message)
    }

    @Test
    fun authStateReportsLoggedIn() {
        val plugin = buildPlugin(
            """
            export function authState() {
                return { state: 'loggedIn', accountName: 'users***' }
            }
            """.trimIndent()
        )

        val state = runBlocking { plugin.authState(fakeContext) }
        assertTrue(state is AuthState.LoggedIn)
        assertEquals("users***", (state as AuthState.LoggedIn).accountName)
    }

    // ---- 采集 ----

    @Test
    fun fetchReturnsMetrics() {
        val plugin = buildPlugin(
            """
            export async function fetch(date) {
                return [
                    { metricKey: 'steps', value: 5000 },
                    { metricKey: 'steps', value: 2500 }
                ]
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.fetch(fakeContext, "2026-11-11") }
        assertTrue("实际结果：$result", result is FetchResult.Success)
        val metrics = (result as FetchResult.Success).metrics
        assertEquals(2, metrics.size)
        assertEquals("steps", metrics[0].metricKey)
        assertEquals(5000.0, metrics[0].value, 0.001)
    }

    @Test
    fun fetchPassesDateArgument() {
        val plugin = buildPlugin(
            """
            export function fetch(date) {
                return [{ metricKey: 'steps', value: date === '2026-11-11' ? 1 : 0 }]
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.fetch(fakeContext, "2026-11-11") }
        assertTrue("实际结果：$result", result is FetchResult.Success)
        assertEquals(1.0, (result as FetchResult.Success).metrics[0].value, 0.001)
    }

    // ---- 健康度 ----

    @Test
    fun healthReportsHealthy() {
        val plugin = buildPlugin(
            "export function health() { return { status: 'healthy' } }"
        )
        val health = runBlocking { plugin.health(fakeContext) }
        assertTrue("实际：$health", health is PluginHealth.Healthy)
    }

    @Test
    fun healthReportsBroken() {
        val plugin = buildPlugin(
            "export function health() { return { status: 'broken', message: '登录已失效' } }"
        )
        val health = runBlocking { plugin.health(fakeContext) }
        assertTrue(health is PluginHealth.Broken)
        assertEquals("登录已失效", (health as PluginHealth.Broken).message)
    }

    // ---- 详情页 ----

    @Test
    fun detailUiReturnsNodeTree() {
        val plugin = buildPlugin(
            """
            export function detailUi() {
                return [
                    { type: 'text', title: '说明', body: '这是正文' },
                    { type: 'statusCard', label: '上次同步', value: '刚刚', tone: 'ok' },
                    { type: 'row', card: false, children: [
                        { type: 'button', label: '立即同步', actionId: 'sync', primary: true }
                    ]}
                ]
            }
            """.trimIndent()
        )

        val root = runBlocking { plugin.detailUi(fakeContext) }
        // 插件返回数组，解析层包一层不套卡片的 column
        val column = root as ColumnNode
        assertEquals(3, column.children.size)
        assertTrue(column.children[0] is TextNode)
        assertTrue(column.children[1] is StatusCardNode)
        assertTrue(column.children[2] is RowNode)
    }

    @Test
    fun onActionReturnsTriggerSync() {
        val plugin = buildPlugin(
            """
            export function onAction(actionId) {
                return actionId === 'sync'
                    ? { type: 'triggerSync' }
                    : { type: 'showMessage', text: '未知操作' }
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.onAction(fakeContext, "sync") }
        assertTrue("实际：$result", result is ActionResult.TriggerSync)
    }

    @Test
    fun onActionReceivesInputValue() {
        val plugin = buildPlugin(
            """
            export function onAction(actionId, value) {
                return { type: 'showMessage', text: actionId + ':' + (value === null ? 'null' : value) }
            }
            """.trimIndent()
        )

        val result = runBlocking { plugin.onAction(fakeContext, "send", "你好") }
        assertEquals("send:你好", (result as ActionResult.ShowMessage).text)

        // 按钮类动作不带文本，必须显式传 null 而不是空串——
        // 插件据此区分「用户发了空消息」与「不是输入类动作」
        val button = runBlocking { plugin.onAction(fakeContext, "sync") }
        assertEquals("sync:null", (button as ActionResult.ShowMessage).text)
    }

    /**
     * 增量片段按推送顺序到达，空串不占位。
     *
     * 订阅必须在调用前完成：热流没有重放，晚一步就丢掉开头几段——
     * 这不是实现细节，而是调用方（ViewModel）遵守的约定，所以在这里钉住。
     */
    @Test
    fun streamEmitsIncrementalText() = runBlocking {
        val plugin = buildPlugin(
            """
            export function onAction(actionId) {
                ctx.stream.emit('你');
                ctx.stream.emit('');
                ctx.stream.emit('好');
                return { type: 'refreshUi', root: { type: 'text', body: '完成' } }
            }
            """.trimIndent()
        )

        val deltas = mutableListOf<String>()
        // UNDISPATCHED：collect 直接跑到挂起点，订阅在调用发起前就已注册
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            plugin.streamDeltas.collect { deltas += it }
        }

        plugin.onAction(fakeContext, "chat")

        // 投递经过一次调度，等片段落到收集端
        withTimeout(2_000) { while (deltas.size < 2) delay(5) }
        job.cancel()

        assertEquals("片段应按推送顺序到达，空串被丢弃", listOf("你", "好"), deltas)
    }

    /**
     * 会话界面由插件用普通原语自己拼，靠 `binding` 接宿主推送。
     *
     * 这是通用绑定的核心约定：没有专用会话节点，插件写
     * `{binding:'session:<key>:<field>'}` 就把那块数据交给宿主托管。
     */
    @Test
    fun detailUiParsesSessionBindings() {
        val plugin = buildPlugin(
            """
            export function detailUi() {
                return {
                    type: 'column',
                    children: [
                        { type: 'list', maxHeightDp: 320, emptyHint: '暂无对话',
                          items: { binding: 'session:main:messages' } },
                        { type: 'input', actionId: 'dsh.send', placeholder: '说点什么',
                          submitTo: 'session:main',
                          enabled: { binding: 'session:main:inputEnabled' } },
                        { type: 'text', body: { binding: 'session:main:status' } }
                    ]
                }
            }
            """.trimIndent()
        )

        val column = runBlocking { plugin.detailUi(fakeContext) } as ColumnNode
        assertEquals(3, column.children.size)

        val list = column.children[0] as ListNode
        assertEquals("session:main:messages", (list.items as Bound.Ref).ref)
        assertEquals(320, list.maxHeightDp)
        assertEquals("暂无对话", list.emptyHint)

        val input = column.children[1] as InputNode
        assertEquals("dsh.send", input.actionId)
        assertEquals("session", input.submitTo?.source)
        assertEquals("main", input.submitTo?.key)
        assertEquals("session:main:inputEnabled", (input.enabled as Bound.Ref).ref)

        val status = column.children[2] as TextNode
        assertEquals("session:main:status", (status.body as Bound.Ref).ref)
    }

    /**
     * 畸形绑定退回静态默认值，不能让整页打不开。
     *
     * 插件传来的 JSON 不可信，一个拼错的三段式不该把详情页变成空白。
     */
    @Test
    fun malformedBindingFallsBackToStatic() {
        val plugin = buildPlugin(
            """
            export function detailUi() {
                return {
                    type: 'column',
                    children: [
                        { type: 'text', body: { binding: 'oops' } },
                        { type: 'list', items: { binding: 'session:only-two' } }
                    ]
                }
            }
            """.trimIndent()
        )

        val column = runBlocking { plugin.detailUi(fakeContext) } as ColumnNode
        val text = column.children[0] as TextNode
        assertEquals("", (text.body as Bound.Static).value)
        val list = column.children[1] as ListNode
        assertTrue((list.items as Bound.Static).value.isEmpty())
    }

    /** 旧区块类型不再被识别，降级为占位而不是静默消失 */
    @Test
    fun legacyBlockTypeDegradesToUnsupported() {
        val plugin = buildPlugin(
            """
            export function detailUi() {
                return [
                    { type: 'conversation', sessionId: 'main' },
                    { type: 'messageList', messages: [] }
                ]
            }
            """.trimIndent()
        )

        val column = runBlocking { plugin.detailUi(fakeContext) } as ColumnNode
        // 两者都必须出现（占位），不能被静默丢弃
        assertEquals(2, column.children.size)
        assertTrue(column.children[0] is UnsupportedNode)
        assertTrue(column.children[1] is UnsupportedNode)
    }

    // ---- 可选实现与异常兜底 ----

    @Test
    fun missingOptionalFunctionsFallBackGracefully() {
        // 只实现 fetch，其余都不实现——契约里 detailUi / onAction 是可选
        val plugin = buildPlugin(
            "export function fetch(date) { return [] }"
        )

        runBlocking {
            // 未实现 detailUi 时返回 null（不是空列表）：「没有自定义内容」
            assertNull(plugin.detailUi(fakeContext))
            assertTrue(plugin.onAction(fakeContext, "x") is ActionResult.NoOp)
            // authState 未实现时必须报错而不是「未登录」：
            // 把「没问出来」当成「插件说它未登录」，界面就会在用户
            // 明明登录着的时候显示未登录。上层据此显示「待确认」
            assertThrows(IllegalStateException::class.java) {
                runBlocking { plugin.authState(fakeContext) }
            }
        }
    }

    @Test
    fun pluginThrowDoesNotCrashHost() {
        val plugin = buildPlugin(
            "export async function fetch(date) { throw new Error('network down') }"
        )

        // 契约要求插件不抛异常；即便抛了，宿主也不该崩，
        // 但必须明确表达「这次没取到」，不能伪装成「今日无数据」
        val result = runBlocking { plugin.fetch(fakeContext, "2026-11-11") }
        assertTrue("实际结果：$result", result is FetchResult.Failure)
    }

    // ---- ctx 桥接 ----

    @Test
    fun credentialsRoundTripThroughCtx() {
        val plugin = buildPlugin(
            """
            export async function login() {
                ctx.credentials.put('a', '1')
                ctx.credentials.put('b', '2')
                const a = ctx.credentials.get('a')
                ctx.credentials.remove('b')
                const b = ctx.credentials.get('b')
                ctx.credentials.put('summary', a + '/' + (b === null ? 'gone' : b))
                return { type: 'success' }
            }
            """.trimIndent()
        )

        runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertEquals("1/gone", runBlocking { fakeContext.credentials.get("summary") })
    }

    @Test
    fun filesRoundTripThroughCtx() {
        val plugin = buildPlugin(
            """
            export async function login() {
                ctx.files.write('note.txt', 'hello-file')
                const content = ctx.files.read('note.txt')
                ctx.credentials.put('read', content)
                return { type: 'success' }
            }
            """.trimIndent()
        )

        runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertEquals("hello-file", runBlocking { fakeContext.credentials.get("read") })
        assertTrue(File(fakeContext.dir, "note.txt").exists())
    }

    @Test
    fun fileTraversalIsBlocked() {
        val plugin = buildPlugin(
            """
            export async function login() {
                const outside = ctx.files.read('../../../etc/hosts')
                ctx.credentials.put('leak', String(outside))
                return { type: 'success' }
            }
            """.trimIndent()
        )

        runBlocking { plugin.login(fakeContext, emptyMap()) }
        // 目录穿越被拦下，读到的是 null。插件是第三方代码，这条防线必须成立
        assertEquals("null", runBlocking { fakeContext.credentials.get("leak") })
    }

    @Test
    fun reportRawRecordsReachesHost() {
        val plugin = buildPlugin(
            """
            export async function fetch(date) {
                ctx.reportRawRecords('steps', [
                    { timestamp: 1000, value: 100, detail: 'morning' },
                    { timestamp: 2000, value: 200 }
                ])
                return [{ metricKey: 'steps', value: 300 }]
            }
            """.trimIndent()
        )

        runBlocking { plugin.fetch(fakeContext, "2026-11-11") }

        val staged = fakeContext.stagedRecords["steps"].orEmpty()
        assertEquals(2, staged.size)
        assertEquals(1000L, staged[0].timestamp)
        assertEquals("morning", staged[0].detail)
        assertEquals("2026-11-11", fakeContext.stagedDates["steps"])
    }

    @Test
    fun recentMetricsReadableFromJs() {
        fakeContext.seedMetrics(
            "steps",
            listOf(
                MetricSample("2026-11-09", 100.0),
                MetricSample("2026-11-10", 200.0),
            ),
        )

        val plugin = buildPlugin(
            """
            export async function login() {
                const samples = ctx.recentMetrics('steps', 7)
                ctx.credentials.put('count', String(samples.length))
                ctx.credentials.put('last', String(samples[samples.length - 1].value))
                return { type: 'success' }
            }
            """.trimIndent()
        )

        runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertEquals("2", runBlocking { fakeContext.credentials.get("count") })
        // 比较数值而非字符串：JS 把 200.0 转成字符串时，Android 给 "200"、JVM 给 "200.0"。
        // 这条断言要验证的是「取到的是最后一条」，不是数字怎么打印
        assertEquals(
            200.0,
            runBlocking { fakeContext.credentials.get("last") }?.toDoubleOrNull() ?: -1.0,
            0.001,
        )
    }

    @Test
    fun multiModulePluginWorks() {
        val plugin = buildPlugin(
            mainJs = """
                import { double } from './lib/math.js'
                export function fetch(date) {
                    return [{ metricKey: 'steps', value: double(250) }]
                }
            """.trimIndent(),
            extraModules = mapOf(
                "lib/math.js" to "export function double(x) { return x * 2 }",
            ),
        )

        val result = runBlocking { plugin.fetch(fakeContext, "2026-11-11") }
        assertTrue("实际结果：$result", result is FetchResult.Success)
        assertEquals(500.0, (result as FetchResult.Success).metrics[0].value, 0.001)
    }

    @Test
    fun credentialsAreIsolatedPerPluginId() {
        // 凭据按 pluginId 分区。这条不是实现细节：插件能读到别人的 token，
        // 等于任装一个插件就拿到全部账号
        val plugin = buildPlugin(
            """
            export async function login() {
                const other = ctx.credentials.get('shared-slot')
                ctx.credentials.put('seen', String(other))
                return { type: 'success' }
            }
            """.trimIndent()
        )

        runBlocking { plugin.login(fakeContext, emptyMap()) }
        assertEquals("null", runBlocking { fakeContext.credentials.get("seen") })
    }

    private companion object {
        const val PLUGIN_ID = "js.test.contract"
    }
}

/**
 * 测试用的 [PluginContext] 假实现。
 *
 * 只覆盖适配器会用到的能力：凭据放内存 Map，原始记录与历史指标放内存表。
 * 文件通道走真实磁盘——目录穿越的拦截逻辑在真实文件系统上才有意义。
 */
internal class FakePluginContext(
    override val pluginId: String,
    val dir: File,
) : PluginContext {

    private val credentialMap = mutableMapOf<String, String>()
    private val sampleMap = mutableMapOf<String, List<MetricSample>>()

    val stagedRecords = mutableMapOf<String, List<RawRecord>>()
    val stagedDates = mutableMapOf<String, String>()

    override val credentials = object : CredentialStore {
        override suspend fun get(slot: String): String? = credentialMap[slot]
        override suspend fun put(slot: String, value: String) {
            credentialMap[slot] = value
        }
        override suspend fun remove(slot: String) {
            credentialMap.remove(slot)
        }
        override suspend fun clear() {
            credentialMap.clear()
        }
    }

    override val filesDirPath: String = dir.absolutePath

    fun seedMetrics(metricKey: String, samples: List<MetricSample>) {
        sampleMap[metricKey] = samples
    }

    override suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample> =
        sampleMap[metricKey].orEmpty()

    override suspend fun reportRawRecords(
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) {
        stagedRecords[metricKey] = records
        stagedDates[metricKey] = date
    }
}

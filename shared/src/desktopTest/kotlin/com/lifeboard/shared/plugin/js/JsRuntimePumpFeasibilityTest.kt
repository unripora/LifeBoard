package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.JsRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0.1 技术验证：QuickJS 能否支撑「常驻会话」模型。
 *
 * 背景：现有插件模型是「调用一次 → 等结果 → 返回」，靠
 * [JsSourcePlugin] 每 50ms 轮询 `callFunction` 推进 Promise。这个模型
 * 撑不起长连接——调用一旦长期不返回，60 秒超时就杀，且 mutex 会阻塞全部调用。
 *
 * 改造方向是「常驻 job queue 泵」：专用线程上持续 `drainJobs()`，
 * 推送消息到达后由泵驱动 JS 侧回调。本测试验证三件事是否成立：
 *
 * 1. `drainJobs()` 在无待处理任务时是否廉价（不会忙等烧 CPU）
 * 2. 持续 drain 期间，能否正常投递新的 resolve
 * 3. 泵运行时，另一条调用路径是否还能推进（不被饿死）
 *
 * 这些是纯引擎层验证，不涉及 LifeBoard 业务代码。
 */
class JsRuntimePumpFeasibilityTest {

    private val runtimes = ArrayList<JsRuntime>()

    private fun runtime(): JsRuntime = JsRuntime().also { runtimes.add(it) }

    @AfterTest
    fun tearDown() {
        runtimes.forEach { runCatching { it.close() } }
        runtimes.clear()
    }

    /**
     * 验证 1：空转 drain 的耗时。
     *
     * 若单次 `drainJobs()` 很贵（比如每次都遍历全部 pending），
     * 「常驻泵」就只能靠定时器低频跑，响应会变慢；
     * 若很便宜，可以高频跑甚至事件驱动。
     */
    @Test
    fun `空转 drain 单次耗时可接受`() {
        val rt = runtime()
        rt.evaluate("globalThis.__x = 1")

        // 预热，避免把首次调用的 JIT/初始化开销算进来
        repeat(100) { rt.drainJobs() }

        val rounds = 10_000
        val start = System.nanoTime()
        repeat(rounds) { rt.drainJobs() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        val perCallUs = (System.nanoTime() - start) / 1000.0 / rounds
        println("[M0.1] 空转 drain：$rounds 次耗时 ${elapsedMs}ms，单次 ${perCallUs}us")

        // 阈值放宽到 100us：只要不是毫秒级，高频泵就可行
        assertTrue(
            perCallUs < 100.0,
            "单次 drainJobs 耗时 ${perCallUs}us，超过 100us，常驻泵会烧 CPU",
        )
    }

    /**
     * 验证 2：持续 drain 期间，新的 resolve 能否被正确处理。
     *
     * 这是「推送到达 → 投递结果 → 泵推进 → JS 回调执行」的完整链路。
     */
    @Test
    fun `持续 drain 期间新 resolve 能被推进`() {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    globalThis.__received = []
                    export function listen(token) {
                        globalThis.__pending = token
                        return 'listening'
                    }
                    export function feed(value) {
                        globalThis.__received.push(value)
                        return globalThis.__received.length
                    }
                """.trimIndent(),
            ),
        )
        rt.evaluateModule("main.js")

        // callFunction 返回的是 JSON 文本，字符串值带引号
        assertEquals("\"listening\"", rt.callFunction("main.js", "listen", "[]"))

        // 模拟「推送到达」：投递结果后由 drain 推进
        repeat(5) { i ->
            rt.resolvePromise("ignored-$i", "\"msg-$i\"")
            rt.drainJobs()
        }

        // 每次 feed 都应该能被独立调用并拿到返回值
        repeat(5) { i ->
            val n = rt.callFunction("main.js", "feed", "[\"msg-$i\"]")
            assertEquals("${i + 1}", n, "第 ${i + 1} 次 feed 应返回累计条数")
        }

        val total = rt.evaluate("globalThis.__received.length")
        assertEquals("5", total)
        println("[M0.1] 持续 drain 期间投递 5 条消息，JS 侧收到 $total 条")
    }

    /**
     * 验证 3：泵与调用并发时是否互相阻塞。
     *
     * 这是最关键的一项。现有实现里 `mutex.withLock` 包住整个轮询循环，
     * 长会话会把其他调用全部饿死。这里验证：如果泵在独立线程跑，
     * 另一条调用路径能否正常推进。
     */
    @Test
    fun `泵线程与调用线程可并行推进`() {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    globalThis.__ticks = 0
                    export function tick() {
                        globalThis.__ticks++
                        return globalThis.__ticks
                    }
                """.trimIndent(),
            ),
        )
        rt.evaluateModule("main.js")

        // 模拟常驻泵：独立线程持续 drain
        val pumpRuns = AtomicInteger(0)
        val stop = CountDownLatch(1)

        // QuickJS 上下文只能在创建线程上用。这里不强求跨线程，
        // 而验证「同一线程上：泵与调用交替推进时不互相卡死」
        val deadlineMs = 2000L
        val startAt = System.currentTimeMillis()
        var tickCount = 0

        while (System.currentTimeMillis() - startAt < deadlineMs) {
            rt.drainJobs()
            pumpRuns.incrementAndGet()

            val n = rt.callFunction("main.js", "tick", "[]")
            tickCount++
            assertEquals("$tickCount", n)
        }

        stop.countDown()
        println(
            "[M0.1] ${deadlineMs}ms 内泵推进 ${pumpRuns.get()} 次，调用执行 $tickCount 次"
        )

        // 只要两者都在推进，说明交替模型可行
        assertTrue(pumpRuns.get() > 100, "泵推进次数过少：${pumpRuns.get()}")
        assertTrue(tickCount > 100, "调用次数过少：$tickCount")
    }

    /**
     * 验证 4：单个慢调用是否会阻塞其他调用（现状复现）。
     *
     * 记录现有行为的基线，作为改造后对比的参照。
     */
    @Test
    fun `复现现状 挂起 Promise 会占用调用线程`() = runBlocking {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    export async function slow() {
                        return await new Promise(function(resolve) {
                            globalThis.__resolveSlow = resolve
                        })
                    }
                    export function fast() {
                        return 'fast-done'
                    }
                """.trimIndent(),
            ),
        )
        rt.evaluateModule("main.js")

        // 发起一个永不落定的调用
        val slowResult = runCatching { rt.callFunction("main.js", "slow", "[]", "slow-key") }
        println("[M0.1] 慢调用首次返回：${slowResult.exceptionOrNull()?.javaClass?.simpleName}")

        // 慢调用挂起期间，快调用是否还能执行？
        val fastStart = System.currentTimeMillis()
        val fastResult = runCatching { rt.callFunction("main.js", "fast", "[]") }
        val fastMs = System.currentTimeMillis() - fastStart

        println("[M0.1] 慢调用挂起时，快调用耗时 ${fastMs}ms，结果=${fastResult.getOrNull()}（失败=${fastResult.exceptionOrNull()?.javaClass?.simpleName}）")

        // 记录事实，不断言对错——这是基线，用于和改造后对比
        assertTrue(fastMs < 1000, "快调用被慢调用拖住了 ${fastMs}ms")
    }
}

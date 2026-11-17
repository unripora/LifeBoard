package com.lifeboard.jsengine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 异步回调链路验证（M3.1 关卡）。
 *
 * 这是整个桥接里最关键的一环：插件 `await ctx.http.get(...)` 时，
 * HTTP 请求在别的线程完成，结果必须能回灌给 JS 并让 await 继续。
 * 这条链路不通，JS 插件就无法做任何真实网络请求。
 *
 * 两条必须遵守的规则（都是从崩溃里学到的）：
 * 1. 宿主函数不能在自己执行期间同步 resolve——那会重入 JS 栈，
 *    表现为 "Maximum call stack size exceeded"。
 * 2. 重新取结果时必须带同一个 callKey，否则会重新执行整个插件函数，重复发请求。
 */
@RunWith(AndroidJUnit4::class)
class JsAsyncBridgeTest {

    /**
     * 完整异步链路：挂起 → 投递结果 → 驱动 → 续跑取回结果。
     *
     * 手动编排每一步，结果确定，不依赖线程时序。
     */
    @Test
    fun pendResolveDrainResumesAwait() {
        JsRuntime().use { rt ->
            val tokens = ArrayDeque<String>()
            rt.registerHostFunctions(
                listOf("ctx.delayed"),
                HostFunctionDispatcher { _, _ ->
                    val token = "t${tokens.size}"
                    tokens.addLast(token)
                    HostFunctionDispatcher.pending(token)
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export async function run(x) {
                            const v = await ctx.delayed(x)
                            return v * 2
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            // 第一次调用：宿主返回 pending，Kotlin 侧抛 PendingResultException
            try {
                rt.callFunction("main.js", "run", "[21]", callKey = "k1")
                fail("首次调用应当挂起")
            } catch (e: PendingResultException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }

            assertTrue("应当登记了一个 token", tokens.isNotEmpty())

            // 投递结果并驱动
            assertTrue(rt.resolvePromise(tokens.first(), "21"))
            rt.drainJobs()

            // 用同一个 callKey 再取：应复用挂起的 Promise，拿到最终结果
            val out = rt.callFunction("main.js", "run", "[21]", callKey = "k1")
            assertEquals("42", out)
        }
    }

    /**
     * resolve 之前反复调用都保持挂起，且不会重复执行插件函数。
     *
     * 通过宿主调用次数验证「没有重新执行」：ctx.count() 只应被调一次。
     */
    @Test
    fun repeatedCallsReuseSameInflightPromise() {
        JsRuntime().use { rt ->
            var hostCallCount = 0
            val tokens = ArrayDeque<String>()

            rt.registerHostFunctions(
                listOf("ctx.slow"),
                HostFunctionDispatcher { _, _ ->
                    hostCallCount++
                    val token = "s$hostCallCount"
                    tokens.addLast(token)
                    HostFunctionDispatcher.pending(token)
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export async function go() {
                            const v = await ctx.slow()
                            return 'got:' + v
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            repeat(3) {
                try {
                    rt.callFunction("main.js", "go", callKey = "same")
                    fail("未 resolve 前应当一直挂起")
                } catch (e: PendingResultException) {
                    // 预期
                }
            }

            assertEquals("插件函数只应执行一次", 1, hostCallCount)

            rt.resolvePromise(tokens.first(), "\"payload\"")
            rt.drainJobs()

            val out = rt.callFunction("main.js", "go", callKey = "same")
            assertTrue("实际输出：$out", out.contains("got:payload"))
        }
    }

    /**
     * reject 之后，JS 侧 catch 能拿到错误信息。
     */
    @Test
    fun rejectSurfacesInJsCatch() {
        JsRuntime().use { rt ->
            val tokens = ArrayDeque<String>()
            rt.registerHostFunctions(
                listOf("ctx.fail"),
                HostFunctionDispatcher { _, _ ->
                    val token = "f${tokens.size}"
                    tokens.addLast(token)
                    HostFunctionDispatcher.pending(token)
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export async function go() {
                            try {
                                await ctx.fail()
                                return 'no-error'
                            } catch (e) {
                                return 'caught:' + e
                            }
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            try {
                rt.callFunction("main.js", "go", callKey = "r1")
                fail("应当挂起")
            } catch (e: PendingResultException) {
                // 预期
            }

            assertTrue(rt.resolvePromise(tokens.first(), error = "boom-from-host"))
            rt.drainJobs()

            val out = rt.callFunction("main.js", "go", callKey = "r1")
            assertTrue("实际输出：$out", out.contains("caught:"))
            assertTrue("实际输出：$out", out.contains("boom-from-host"))
        }
    }

    /**
     * 多个顺序异步调用：第一个 resolve 后链上出现第二个 pending，需要再投递一次。
     */
    @Test
    fun sequentialAsyncCallsNeedMultipleResolves() {
        JsRuntime().use { rt ->
            val tokens = ArrayDeque<String>()
            rt.registerHostFunctions(
                listOf("ctx.step"),
                HostFunctionDispatcher { _, _ ->
                    val token = "q${tokens.size}"
                    tokens.addLast(token)
                    HostFunctionDispatcher.pending(token)
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export async function go() {
                            const a = await ctx.step()
                            const b = await ctx.step()
                            return a + '|' + b
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            try {
                rt.callFunction("main.js", "go", callKey = "seq")
                fail("应当挂起")
            } catch (e: PendingResultException) {
                // 预期
            }

            // 投递第一步结果
            rt.resolvePromise(tokens.first(), "\"first\"")
            rt.drainJobs()

            // 链上出现第二步 pending
            try {
                rt.callFunction("main.js", "go", callKey = "seq")
                fail("第二步应当再次挂起")
            } catch (e: PendingResultException) {
                // 预期
            }

            assertTrue("应当产生第二个 token", tokens.size >= 2)
            rt.resolvePromise(tokens.elementAt(1), "\"second\"")
            rt.drainJobs()

            val out = rt.callFunction("main.js", "go", callKey = "seq")
            assertTrue("实际输出：$out", out.contains("first"))
            assertTrue("实际输出：$out", out.contains("second"))
        }
    }

    @Test
    fun resolveUnknownTokenReturnsFalse() {
        JsRuntime().use { rt ->
            assertFalse(rt.resolvePromise("never-registered", "1"))
        }
    }

    @Test
    fun pendingWithoutTokenThrows() {
        JsRuntime().use { rt ->
            rt.registerHostFunctions(
                listOf("ctx.bad"),
                HostFunctionDispatcher { _, _ -> HostFunctionDispatcher.PENDING }
            )
            rt.setModules(
                mapOf("main.js" to "export function go() { return ctx.bad() }")
            )
            rt.evaluateModule("main.js")

            try {
                rt.callFunction("main.js", "go")
                fail("无 token 的 pending 应当报错")
            } catch (e: JsException) {
                assertTrue("实际信息：${e.message}", e.message.orEmpty().contains("without token"))
            }
        }
    }

    @Test
    fun drainJobsWithoutPendingIsNoop() {
        JsRuntime().use { rt ->
            assertEquals(0, rt.drainJobs())
        }
    }

    /**
     * 不同 callKey 互不影响：两个独立调用各自挂起、各自落定。
     */
    @Test
    fun differentCallKeysAreIndependent() {
        JsRuntime().use { rt ->
            val tokens = ArrayDeque<String>()
            rt.registerHostFunctions(
                listOf("ctx.step"),
                HostFunctionDispatcher { _, _ ->
                    val token = "i${tokens.size}"
                    tokens.addLast(token)
                    HostFunctionDispatcher.pending(token)
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export async function go(tag) {
                            const v = await ctx.step()
                            return tag + ':' + v
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            try {
                rt.callFunction("main.js", "go", "[\"A\"]", callKey = "a")
                fail("A 应当挂起")
            } catch (e: PendingResultException) {
                // 预期
            }
            try {
                rt.callFunction("main.js", "go", "[\"B\"]", callKey = "b")
                fail("B 应当挂起")
            } catch (e: PendingResultException) {
                // 预期
            }

            assertEquals("两个调用各自产生 token", 2, tokens.size)

            rt.resolvePromise(tokens.elementAt(0), "\"va\"")
            rt.drainJobs()
            rt.resolvePromise(tokens.elementAt(1), "\"vb\"")
            rt.drainJobs()

            val outA = rt.callFunction("main.js", "go", "[\"A\"]", callKey = "a")
            val outB = rt.callFunction("main.js", "go", "[\"B\"]", callKey = "b")
            assertTrue("A 实际输出：$outA", outA.contains("A:va"))
            assertTrue("B 实际输出：$outB", outB.contains("B:vb"))
        }
    }
}

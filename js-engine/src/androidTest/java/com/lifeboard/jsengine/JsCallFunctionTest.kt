package com.lifeboard.jsengine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 函数调用与宿主桥接验证（M2 关卡）。
 *
 * 这一组用例决定插件契约能否真正跑起来：
 * 插件导出的 login/fetch 等函数必须能被 Kotlin 带参调用并取回结构化结果。
 */
@RunWith(AndroidJUnit4::class)
class JsCallFunctionTest {

    private fun runtimeWith(source: String): JsRuntime =
        JsRuntime().also { rt ->
            rt.setModules(mapOf("main.js" to source))
            rt.evaluateModule("main.js")
        }

    // ---------- 同步调用 ----------

    @Test
    fun callWithNoArgs() {
        runtimeWith("export function ping() { return 'pong' }").use { rt ->
            assertEquals("\"pong\"", rt.callFunction("main.js", "ping"))
        }
    }

    @Test
    fun callWithOneArg() {
        runtimeWith("export function echo(v) { return v }").use { rt ->
            assertEquals("\"hello\"", rt.callFunction("main.js", "echo", "[\"hello\"]"))
        }
    }

    @Test
    fun callWithMultipleArgs() {
        runtimeWith("export function add(a, b) { return a + b }").use { rt ->
            assertEquals("7", rt.callFunction("main.js", "add", "[3, 4]"))
        }
    }

    @Test
    fun callReturnsObject() {
        runtimeWith(
            "export function metric() { return { metricKey: 'steps', value: 5000 } }"
        ).use { rt ->
            val out = rt.callFunction("main.js", "metric")
            assertTrue(out.contains("\"metricKey\":\"steps\""))
            assertTrue(out.contains("\"value\":5000"))
        }
    }

    @Test
    fun callReturnsArray() {
        runtimeWith(
            "export function list() { return [{k:'a',v:1},{k:'b',v:2}] }"
        ).use { rt ->
            val out = rt.callFunction("main.js", "list")
            assertTrue(out.startsWith("["))
            assertTrue(out.contains("\"k\":\"a\""))
            assertTrue(out.contains("\"k\":\"b\""))
        }
    }

    @Test
    fun callReturnsNull() {
        runtimeWith("export function nothing() { return null }").use { rt ->
            assertEquals("null", rt.callFunction("main.js", "nothing"))
        }
    }

    @Test
    fun callWithObjectArg() {
        runtimeWith(
            "export function read(o) { return o.date + '/' + o.metric }"
        ).use { rt ->
            assertEquals(
                "\"2026-11-11/steps\"",
                rt.callFunction("main.js", "read", "[{\"date\":\"2026-11-11\",\"metric\":\"steps\"}]")
            )
        }
    }

    @Test
    fun callMissingFunctionThrows() {
        runtimeWith("export function ping() { return 1 }").use { rt ->
            try {
                rt.callFunction("main.js", "nope")
                fail("不存在的函数应当抛异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().contains("not a function"))
            }
        }
    }

    @Test
    fun callThatThrowsSurfacesMessage() {
        runtimeWith("export function boom() { throw new Error('kaboom') }").use { rt ->
            try {
                rt.callFunction("main.js", "boom")
                fail("抛错函数应当传播异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().contains("kaboom"))
            }
        }
    }

    @Test
    fun callWithInvalidArgsJsonThrows() {
        runtimeWith("export function echo(v) { return v }").use { rt ->
            try {
                rt.callFunction("main.js", "echo", "{not json}")
                fail("非法参数 JSON 应当抛异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }

    // ---------- 异步调用 ----------

    @Test
    fun callAsyncFunction() {
        runtimeWith(
            "export async function fetchDate(d) { const v = await Promise.resolve(d); return v }"
        ).use { rt ->
            assertEquals("\"2026-11-11\"", rt.callFunction("main.js", "fetchDate", "[\"2026-11-11\"]"))
        }
    }

    @Test
    fun callAsyncReturningObject() {
        runtimeWith(
            "export async function m() { return await Promise.resolve({ metricKey: 'steps', value: 42 }) }"
        ).use { rt ->
            val out = rt.callFunction("main.js", "m")
            assertTrue(out.contains("\"metricKey\":\"steps\""))
            assertTrue(out.contains("\"value\":42"))
        }
    }

    @Test
    fun asyncRejectionSurfacesMessage() {
        runtimeWith(
            "export async function bad() { throw new Error('async-fail') }"
        ).use { rt ->
            try {
                rt.callFunction("main.js", "bad")
                fail("async 抛错应当传播")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().contains("async-fail"))
            }
        }
    }

    // ---------- 宿主桥接 ----------

    @Test
    fun hostFunctionReturnsSyncValue() {
        JsRuntime().use { rt ->
            val calls = mutableListOf<String>()
            rt.registerHostFunctions(
                listOf("ctx.http.get"),
                HostFunctionDispatcher { name, args ->
                    calls += "$name$args"
                    "\"response-body\""
                }
            )
            rt.setModules(
                mapOf("main.js" to "export function go() { return ctx.http.get('https://x') }")
            )
            rt.evaluateModule("main.js")

            assertEquals("\"response-body\"", rt.callFunction("main.js", "go"))
            assertEquals(listOf("ctx.http.get[\"https://x\"]"), calls)
        }
    }

    @Test
    fun hostFunctionReceivesStructuredArgs() {
        JsRuntime().use { rt ->
            var received = ""
            rt.registerHostFunctions(
                listOf("ctx.credentials.put"),
                HostFunctionDispatcher { _, args ->
                    received = args
                    "null"
                }
            )
            rt.setModules(
                mapOf(
                    "main.js" to "export function save() { ctx.credentials.put('token', 'abc123'); return 1 }"
                )
            )
            rt.evaluateModule("main.js")
            rt.callFunction("main.js", "save")

            assertTrue("实际收到：$received", received.contains("\"token\""))
            assertTrue("实际收到：$received", received.contains("\"abc123\""))
        }
    }

    @Test
    fun hostFunctionErrorBecomesJsException() {
        JsRuntime().use { rt ->
            rt.registerHostFunctions(
                listOf("ctx.http.get"),
                HostFunctionDispatcher { _, _ -> HostFunctionDispatcher.error("network down") }
            )
            rt.setModules(
                mapOf(
                    "main.js" to "export function go() { try { ctx.http.get('x') } catch (e) { return 'caught:' + e.message } }"
                )
            )
            rt.evaluateModule("main.js")

            val out = rt.callFunction("main.js", "go")
            assertTrue("实际返回值：$out", out.contains("caught:"))
            assertTrue("实际返回值：$out", out.contains("network down"))
        }
    }

    @Test
    fun hostFunctionsSurviveModuleReset() {
        JsRuntime().use { rt ->
            rt.registerHostFunctions(
                listOf("ctx.ping"),
                HostFunctionDispatcher { _, _ -> "\"pong\"" }
            )
            // setModules 会重建 ctx，宿主函数必须被重新挂载
            rt.setModules(mapOf("main.js" to "export function go() { return ctx.ping() }"))
            rt.evaluateModule("main.js")

            assertEquals("\"pong\"", rt.callFunction("main.js", "go"))
        }
    }

    @Test
    fun nestedNamespaceIsCreated() {
        JsRuntime().use { rt ->
            rt.registerHostFunctions(
                listOf("ctx.log.d", "ctx.log.e"),
                HostFunctionDispatcher { name, _ -> "\"$name\"" }
            )
            rt.setModules(
                mapOf("main.js" to "export function go() { return [ctx.log.d(), ctx.log.e()] }")
            )
            rt.evaluateModule("main.js")

            val out = rt.callFunction("main.js", "go")
            assertTrue(out.contains("ctx.log.d"))
            assertTrue(out.contains("ctx.log.e"))
        }
    }

    @Test
    fun pluginShapedContractWorks() {
        JsRuntime().use { rt ->
            rt.registerHostFunctions(
                listOf("ctx.credentials.get", "ctx.reportRawRecords"),
                HostFunctionDispatcher { name, _ ->
                    when (name) {
                        "ctx.credentials.get" -> "\"token-abc\""
                        "ctx.reportRawRecords" -> "null"
                        else -> HostFunctionDispatcher.error("unknown $name")
                    }
                }
            )
            // ctx 是全局对象（由宿主注入），不作为函数参数传入
            rt.setModules(
                mapOf(
                    "lib/http.js" to """
                        export function authHeader(token) { return 'Bearer ' + token }
                    """.trimIndent(),
                    "main.js" to """
                        import { authHeader } from './lib/http.js'

                        export async function login(inputs) {
                            const token = ctx.credentials.get('token')
                            return { type: 'success', account: inputs.account }
                        }

                        export async function fetch(date) {
                            const token = ctx.credentials.get('token')
                            const header = authHeader(token)
                            ctx.reportRawRecords('steps', [{ t: date, v: 100 }])
                            return [{ metricKey: 'steps', value: 100, header }]
                        }
                    """.trimIndent()
                )
            )
            rt.evaluateModule("main.js")

            val loginOut = rt.callFunction(
                "main.js", "login",
                "[{\"account\":\"user@example.com\",\"password\":\"x\"}]"
            )
            assertTrue(loginOut.contains("\"type\":\"success\""))
            assertTrue(loginOut.contains("user@example.com"))

            val fetchOut = rt.callFunction("main.js", "fetch", "[\"2026-11-11\"]")
            assertTrue(fetchOut.contains("\"metricKey\":\"steps\""))
            assertTrue(fetchOut.contains("\"value\":100"))
            assertTrue(fetchOut.contains("Bearer token-abc"))
        }
    }
}

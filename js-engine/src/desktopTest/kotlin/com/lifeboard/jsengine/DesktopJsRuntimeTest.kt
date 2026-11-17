package com.lifeboard.jsengine

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M0 可行性验证：桌面侧 QuickJS 动态库 + JNA 绑定是否可用。
 *
 * 这些用例与 `androidTest` 里的 JsRuntimeTest 断言的是**同一套引擎语义**，
 * 只是跑在 JVM 上。它们证明的是：
 *   1. lifeboard-js.dll 能被 JNA 按符号名加载
 *   2. C ABI 的字符串所有权（malloc / lb_free_string）配对正确
 *   3. 模块、异步 Promise、中断这些复杂路径在桌面桥接层同样工作
 */
class DesktopJsRuntimeTest {

    private val runtimes = ArrayList<JsRuntime>()

    private fun runtime(): JsRuntime = JsRuntime().also { runtimes.add(it) }

    @AfterTest
    fun tearDown() {
        runtimes.forEach { it.close() }
        runtimes.clear()
    }

    @Test
    fun `引擎可创建并求值表达式`() {
        val rt = runtime()
        assertEquals("3", rt.evaluate("1 + 2"))
        assertEquals("hello", rt.evaluate("'hello'"))
    }

    @Test
    fun `求值异常转为JsException`() {
        val rt = runtime()
        val e = assertFailsWith<JsException> {
            rt.evaluate("throw new Error('boom')")
        }
        assertTrue(e.message!!.contains("boom"), "异常信息应包含原因，实际：${e.message}")
    }

    @Test
    fun `模块可注册并执行`() {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    import { double } from './lib/math.js'
                    export function run(n) { return double(n) }
                """.trimIndent(),
                "lib/math.js" to """
                    export function double(n) { return n * 2 }
                """.trimIndent(),
            ),
        )

        val exports = rt.evaluateModule("main.js")
        assertEquals(listOf("run"), exports)
        assertEquals("42", rt.callFunction("main.js", "run", "[21]"))
    }

    @Test
    fun `宿主函数可被JS同步调用`() {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    export function greet(name) { return ctx.demo.greet(name) }
                """.trimIndent(),
            ),
        )
        rt.registerHostFunctions(listOf("ctx.demo.greet")) { name, argsJson ->
            check(name == "ctx.demo.greet") { "意外的宿主函数名：$name" }
            check(argsJson == "[\"张三\"]") { "意外的参数：$argsJson" }
            "\"你好，张三\""
        }
        rt.evaluateModule("main.js")

        assertEquals("\"你好，张三\"", rt.callFunction("main.js", "greet", "[\"张三\"]"))
    }

    @Test
    fun `异步宿主函数通过Promise落地`() {
        val rt = runtime()
        rt.setModules(
            mapOf(
                "main.js" to """
                    export async function fetchValue() {
                        return await ctx.demo.fetch()
                    }
                """.trimIndent(),
            ),
        )
        rt.registerHostFunctions(listOf("ctx.demo.fetch")) { _, _ ->
            HostFunctionDispatcher.pending("t-1")
        }
        rt.evaluateModule("main.js")

        // 第一次调用：Promise 挂在宿主回调上，抛 PendingResultException
        val pending = assertFailsWith<PendingResultException> {
            rt.callFunction("main.js", "fetchValue", "[]", "key-1")
        }
        assertEquals("key-1", pending.callKey)

        // 宿主投递结果，再用同一个 callKey 取回
        assertTrue(rt.resolvePromise("t-1", "\"落地值\""))
        rt.drainJobs()
        assertEquals("\"落地值\"", rt.callFunction("main.js", "fetchValue", "[]", "key-1"))
    }

    @Test
    fun `中断可打断死循环`() {
        val rt = runtime()
        val runner = Thread {
            try {
                rt.evaluate("for(;;){}")
            } catch (_: JsException) {
                // 预期被打断
            }
        }
        runner.start()
        Thread.sleep(200)
        rt.requestInterrupt()
        runner.join(5000)

        assertTrue(!runner.isAlive, "中断后脚本应已退出，实际仍在执行")
    }
}

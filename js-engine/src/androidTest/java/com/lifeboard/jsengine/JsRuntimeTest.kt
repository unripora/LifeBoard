package com.lifeboard.jsengine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QuickJS 引擎最小验证（M1 关卡）。
 *
 * 这些用例只回答一个问题：QuickJS 能不能在 Android 上正常跑起来。
 * 不涉及插件契约、不涉及桥接，失败即说明引擎选型或集成方式有问题。
 *
 * 注意：方法名不能用反引号+空格——DEX 不支持带空格的 SimpleName。
 */
@RunWith(AndroidJUnit4::class)
class JsRuntimeTest {

    private fun runtime(): JsRuntime = JsRuntime()

    @Test
    fun engineEvaluatesArithmetic() {
        runtime().use { rt ->
            assertEquals("3", rt.evaluate("1 + 2"))
        }
    }

    @Test
    fun engineEvaluatesStrings() {
        runtime().use { rt ->
            assertEquals("hello", rt.evaluate("'hello'"))
        }
    }

    @Test
    fun engineSupportsModernSyntax() {
        runtime().use { rt ->
            // 可选链 + 空值合并 + 箭头函数 + 模板字符串，插件代码会大量用到
            val code = """
                const obj = { a: { b: 42 } };
                const v = obj?.a?.b ?? 0;
                const f = (x) => `${'$'}{x * 2}`;
                f(v);
            """.trimIndent()
            assertEquals("84", rt.evaluate(code))
        }
    }

    @Test
    fun engineSupportsPromise() {
        runtime().use { rt ->
            // QuickJS 的 async 需要 job queue 驱动，这里只验证语法能解析、
            // Promise 能构造，不验证 microtask 执行（那需要 native 侧 JS_ExecutePendingJob）
            val code = """
                const p = Promise.resolve(7);
                typeof p.then;
            """.trimIndent()
            assertEquals("function", rt.evaluate(code))
        }
    }

    @Test
    fun jsonRoundtripWorks() {
        runtime().use { rt ->
            val code = """
                const o = JSON.parse('{"steps":5000,"sleep":7.5}');
                JSON.stringify([o.steps, o.sleep]);
            """.trimIndent()
            assertEquals("[5000,7.5]", rt.evaluate(code))
        }
    }

    @Test
    fun syntaxErrorThrows() {
        runtime().use { rt ->
            try {
                rt.evaluate("const = ;")
                fail("语法错误应当抛异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun runtimeThrowThrows() {
        runtime().use { rt ->
            try {
                rt.evaluate("throw new Error('boom')")
                fail("运行时异常应当抛出")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().contains("boom"))
            }
        }
    }

    @Test
    fun memoryLimitStopsRunawayAllocation() {
        // 运行时设了 64MB 上限，超大分配应当失败而不是拖死宿主
        runtime().use { rt ->
            try {
                rt.evaluate("const a = []; while(true) { a.push(new Array(100000).fill(0)) }")
                fail("超限分配应当失败")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun runtimesAreIsolated() {
        runtime().use { a ->
            runtime().use { b ->
                a.evaluate("globalThis.leak = 'from-a'")
                assertEquals("undefined", b.evaluate("typeof globalThis.leak"))
                assertEquals("from-a", a.evaluate("globalThis.leak"))
            }
        }
    }

    @Test
    fun closeIsIdempotent() {
        val rt = JsRuntime()
        rt.close()
        rt.close()
    }
}

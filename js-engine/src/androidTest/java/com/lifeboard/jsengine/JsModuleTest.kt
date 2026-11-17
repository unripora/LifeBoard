package com.lifeboard.jsengine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ES 模块与异步能力验证（M1.5 / M1.6 / M1.7 关卡）。
 *
 * 这一组用例决定插件能否用多文件结构编写：
 * 若 import/export 不可用，插件必须由构建期打包成单文件（esbuild）。
 */
@RunWith(AndroidJUnit4::class)
class JsModuleTest {

    @Test
    fun moduleExportsAreListed() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export const id = 'demo'
                        export function fetch(date) { return date }
                    """.trimIndent()
                )
            )
            val exports = rt.evaluateModule("main.js")
            assertEquals(listOf("fetch", "id"), exports.sorted())
        }
    }

    @Test
    fun relativeImportResolves() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "lib/math.js" to """
                        export function add(a, b) { return a + b }
                        export const MAGIC = 42
                    """.trimIndent(),
                    "main.js" to """
                        import { add, MAGIC } from './lib/math.js'
                        export const answer = add(MAGIC, 1)
                    """.trimIndent()
                )
            )
            val exports = rt.evaluateModule("main.js")
            assertEquals(listOf("answer"), exports)
        }
    }

    @Test
    fun nestedImportsResolve() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "lib/base.js" to "export const BASE = 10",
                    "lib/mid.js" to """
                        import { BASE } from './base.js'
                        export const MID = BASE * 2
                    """.trimIndent(),
                    "main.js" to """
                        import { MID } from './lib/mid.js'
                        export const value = MID + 1
                    """.trimIndent()
                )
            )
            assertEquals(listOf("value"), rt.evaluateModule("main.js"))
        }
    }

    @Test
    fun defaultExportIsSupported() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export default function () { return 'ok' }
                    """.trimIndent()
                )
            )
            assertEquals(listOf("default"), rt.evaluateModule("main.js"))
        }
    }

    @Test
    fun importMetaUrlIsInjected() {
        JsRuntime().use { rt ->
            // 若不注入 import.meta，这行会抛 ReferenceError
            rt.setModules(
                mapOf(
                    "main.js" to """
                        export const url = import.meta.url
                        export const ok = typeof url === 'string' && url.length > 0
                    """.trimIndent()
                )
            )
            assertEquals(listOf("ok", "url"), rt.evaluateModule("main.js").sorted())
        }
    }

    @Test
    fun missingModuleThrows() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "main.js" to "import { x } from './nope.js'\n export const y = x"
                )
            )
            try {
                rt.evaluateModule("main.js")
                fail("缺失模块应当抛异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
    }

    @Test
    fun unregisteredEntryThrows() {
        JsRuntime().use { rt ->
            try {
                rt.evaluateModule("nothing.js")
                fail("未注册模块应当抛异常")
            } catch (e: JsException) {
                assertTrue(e.message.orEmpty().contains("not registered"))
            }
        }
    }

    @Test
    fun promiseMicrotasksAreDrained() {
        JsRuntime().use { rt ->
            // 关键验证：顶层 await 需要 JS_ExecutePendingJob 才能完成。
            // 不做 drainJobs 的话，模块永远不会执行完。
            rt.setModules(
                mapOf(
                    "main.js" to """
                        const value = await Promise.resolve(123)
                        export const resolved = value
                    """.trimIndent()
                )
            )
            assertEquals(listOf("resolved"), rt.evaluateModule("main.js"))
        }
    }

    @Test
    fun asyncFunctionAwaitWorks() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "main.js" to """
                        async function double(x) {
                            const v = await Promise.resolve(x)
                            return v * 2
                        }
                        export const out = await double(21)
                    """.trimIndent()
                )
            )
            assertEquals(listOf("out"), rt.evaluateModule("main.js"))
        }
    }

    @Test
    fun chainedPromisesComplete() {
        JsRuntime().use { rt ->
            rt.setModules(
                mapOf(
                    "main.js" to """
                        const result = await Promise.resolve(1)
                            .then(v => v + 1)
                            .then(v => v + 1)
                            .then(v => v * 10)
                        export const value = result
                        export const ok = result === 30
                    """.trimIndent()
                )
            )
            assertEquals(listOf("ok", "value"), rt.evaluateModule("main.js").sorted())
        }
    }

    @Test
    fun exportedValuesAreReadableAcrossPaths() {
        JsRuntime().use { rt ->
            // 验证真实插件结构：多文件、工具库、常量分离，且同时导出
            // const / function / async function 三种形态。
            rt.setModules(
                mapOf(
                    "lib/const.js" to "export const HOST = 'hlth.io.mi.com'\nexport const VERSION = 3",
                    "lib/util.js" to "import { HOST, VERSION } from './const.js'\nexport function endpoint(path) { return HOST + '/' + VERSION + '/' + path }",
                    "main.js" to "import { endpoint } from './lib/util.js'\nexport const url = endpoint('data')\nexport function login() { return url }\nexport async function fetch(d) { return d }",
                )
            )
            val exports = rt.evaluateModule("main.js")
            assertEquals(listOf("fetch", "login", "url"), exports.sorted())
        }
    }
}

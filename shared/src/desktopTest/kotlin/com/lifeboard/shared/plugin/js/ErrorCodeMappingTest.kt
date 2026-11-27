package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.PluginErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 错误码往返与旧语义推断（对应计划 3.5 / 8.6）。
 *
 * 要证明三件事：
 * 1. 插件显式给了 `error` 对象 → 按它分类
 * 2. 旧插件只给 `status` → 按契约推断（不能变成"没有错误"）
 * 3. 未知错误码 → 回退 `INTERNAL_ERROR`，绝不静默成功
 */
class ErrorCodeMappingTest {

    @Test
    fun explicitErrorObjectWins() {
        val json = """
            {"status":"failure","message":"接口变了",
             "error":{"code":"UPSTREAM_CHANGED","message":"steps 字段缺失","operation":"fetch"}}
        """.trimIndent()
        val result = JsTypeConverter.toFetchResult(json)
        assertTrue(result is FetchResult.Failure)

        val error = JsTypeConverter.errorOf(result)
        assertNotNull(error)
        assertEquals(PluginErrorCode.UPSTREAM_CHANGED, error.code)
        assertEquals("fetch", error.operation)
        assertTrue(!error.retryable, "结构变化重试无用")
    }

    @Test
    fun legacyStatusIsInferred() {
        // 旧插件只给 status：必须按契约推断，而不能当成"没有错误"
        val temporary = JsTypeConverter.toFetchResult("""{"status":"temporary","message":"超时"}""")
        assertEquals(PluginErrorCode.UPSTREAM_ERROR, JsTypeConverter.errorOf(temporary)?.code)

        val auth = JsTypeConverter.toFetchResult("""{"status":"authRequired","message":"过期"}""")
        assertEquals(PluginErrorCode.AUTH_REQUIRED, JsTypeConverter.errorOf(auth)?.code)

        val failure = JsTypeConverter.toFetchResult("""{"status":"failure","message":"坏了"}""")
        assertEquals(PluginErrorCode.INTERNAL_ERROR, JsTypeConverter.errorOf(failure)?.code)
    }

    @Test
    fun unknownCodeFallsBackToInternalError() {
        val json = """
            {"status":"failure","message":"x","error":{"code":"TOTALLY_UNKNOWN","message":"x"}}
        """.trimIndent()
        val error = JsTypeConverter.errorOf(JsTypeConverter.toFetchResult(json))
        assertEquals(PluginErrorCode.INTERNAL_ERROR, error?.code, "未知码不得变成成功或空数据")
    }

    @Test
    fun successHasNoError() {
        val success = JsTypeConverter.toFetchResult("""{"status":"success","metrics":[]}""")
        assertNull(JsTypeConverter.errorOf(success), "成功结果不应带错误")

        val empty = JsTypeConverter.toFetchResult("""{"status":"empty","reason":"无记录"}""")
        assertNull(JsTypeConverter.errorOf(empty), "「确实没有数据」不是错误")
    }

    @Test
    fun retryPolicyMatchesDesign() {
        // 与 05-errors 表一致：只有这几类是"宿主可自动重试"的
        assertTrue(PluginErrorCode.UPSTREAM_ERROR.let { true })
        val retryable = listOf(
            PluginErrorCode.AUTH_EXPIRED,
            PluginErrorCode.UPSTREAM_ERROR,
            PluginErrorCode.RATE_LIMITED,
            PluginErrorCode.UNAVAILABLE,
        )
        val notRetryable = listOf(
            PluginErrorCode.AUTH_REQUIRED,
            PluginErrorCode.AUTH_INVALID,
            PluginErrorCode.UPSTREAM_CHANGED,
            PluginErrorCode.UNSUPPORTED,
            PluginErrorCode.INVALID_ARGUMENT,
            PluginErrorCode.STORAGE_FULL,
            PluginErrorCode.RESOURCE_LIMIT,
            PluginErrorCode.INTERRUPTED,
            PluginErrorCode.INTERNAL_ERROR,
        )
        retryable.forEach {
            assertTrue(
                com.lifeboard.plugin.api.PluginError(it, "x").retryable,
                "$it 应可重试",
            )
        }
        notRetryable.forEach {
            assertTrue(
                !com.lifeboard.plugin.api.PluginError(it, "x").retryable,
                "$it 不应可重试",
            )
        }
    }

    @Test
    fun rawArrayIsStillSuccess() {
        // 裸数组是历史写法，不能因为引入错误码就失效
        val result = JsTypeConverter.toFetchResult("""[{"metricKey":"steps","value":1}]""")
        assertTrue(result is FetchResult.Success)
    }
}

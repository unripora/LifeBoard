package com.lifeboard.server.plugin.js

import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.shared.plugin.js.JsTypeConverter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fetch 返回值的解析。
 *
 * 这组用例守的是本次契约改造的核心承诺：
 * **「没数据」和「没取到」必须在类型层面就分得开**。
 * 早期两者都是空列表，用户看到「今日无数据」时无法判断是没运动还是断网了。
 */
class FetchResultParseTest {

    /** 裸数组等同 success：老写法不强制迁移，否则所有现存脚本都要改 */
    @Test
    fun bareArrayIsSuccess() {
        val json = """[{"metricKey":"steps","value":5000}]"""

        val result = JsTypeConverter.toFetchResult(json)

        assertTrue(result is FetchResult.Success)
        assertEquals(1, (result as FetchResult.Success).metrics.size)
    }

    /** 新写法的 success 分支 */
    @Test
    fun statusSuccessCarriesMetrics() {
        val json = """{"status":"success","metrics":[{"metricKey":"steps","value":42}]}"""

        val result = JsTypeConverter.toFetchResult(json) as FetchResult.Success

        assertEquals(42.0, result.metrics.single().value, 0.001)
    }

    /** success 但空列表 —— 这是「确实没记录」，与失败无关 */
    @Test
    fun statusSuccessWithEmptyMetricsIsNotFailure() {
        val json = """{"status":"success","metrics":[]}"""

        val result = JsTypeConverter.toFetchResult(json)

        assertTrue(result is FetchResult.Success)
        assertTrue((result as FetchResult.Success).metrics.isEmpty())
    }

    /** empty 必须带上原因，否则用户还是不知道为什么没数据 */
    @Test
    fun statusEmptyKeepsReason() {
        val json = """{"status":"empty","reason":"当天没有运动记录"}"""

        val result = JsTypeConverter.toFetchResult(json) as FetchResult.Empty

        assertEquals("当天没有运动记录", result.reason)
    }

    /** 缺 reason 时也要有一句可读的兜底文案 */
    @Test
    fun statusEmptyWithoutReasonFallsBack() {
        val result = JsTypeConverter.toFetchResult("""{"status":"empty"}""") as FetchResult.Empty

        assertTrue(result.reason.isNotBlank())
    }

    /** 临时失败带上服务端建议的等待时间 */
    @Test
    fun statusTemporaryKeepsRetryAfter() {
        val json = """{"status":"temporary","message":"请求超时","retryAfterSeconds":60}"""

        val result = JsTypeConverter.toFetchResult(json) as FetchResult.TemporaryFailure

        assertEquals("请求超时", result.message)
        assertEquals(60L, result.retryAfterSeconds)
    }

    /** 没给 retryAfterSeconds 时是 null，由宿主决定退避，而不是当成「等 0 秒」 */
    @Test
    fun statusTemporaryWithoutRetryAfterIsNull() {
        val result = JsTypeConverter.toFetchResult("""{"status":"temporary","message":"断网"}""")
            as FetchResult.TemporaryFailure

        assertNull(result.retryAfterSeconds)
    }

    /** 凭据失效必须单独成类：这是唯一用户自己能动手术修好的失败 */
    @Test
    fun statusAuthRequiredIsItsOwnBranch() {
        val json = """{"status":"authRequired","message":"登录已过期"}"""

        val result = JsTypeConverter.toFetchResult(json) as FetchResult.AuthenticationRequired

        assertEquals("登录已过期", result.message)
    }

    /** 明确失败 */
    @Test
    fun statusFailureKeepsMessage() {
        val result = JsTypeConverter.toFetchResult("""{"status":"failure","message":"接口变了"}""")
            as FetchResult.Failure

        assertEquals("接口变了", result.message)
    }

    /**
     * 不认识的 status 按失败处理，不能当成功。
     *
     * 当成功会让「插件字段名写错」表现成「今天没数据」——正是要消灭的歧义。
     */
    @Test
    fun unknownStatusIsFailure() {
        val result = JsTypeConverter.toFetchResult("""{"status":"sucsess","metrics":[]}""")

        assertTrue(result is FetchResult.Failure)
    }

    /** 完全没有 status 的对象也不能当成功 */
    @Test
    fun objectWithoutStatusIsFailure() {
        assertTrue(JsTypeConverter.toFetchResult("""{"metrics":[]}""") is FetchResult.Failure)
    }

    /** 畸形 JSON 不能抛异常，只能是失败 */
    @Test
    fun malformedJsonIsFailure() {
        assertTrue(JsTypeConverter.toFetchResult("not json") is FetchResult.Failure)
        assertTrue(JsTypeConverter.toFetchResult("") is FetchResult.Failure)
    }

    /** 前导空白不应影响类型判断 */
    @Test
    fun leadingWhitespaceIsTolerated() {
        val json = "\n  " + JSONArray().put(org.json.JSONObject().put("metricKey", "s").put("value", 1)).toString()

        assertTrue(JsTypeConverter.toFetchResult(json) is FetchResult.Success)
    }
}

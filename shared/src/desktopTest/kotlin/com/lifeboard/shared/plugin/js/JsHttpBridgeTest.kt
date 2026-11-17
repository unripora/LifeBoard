package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.json.PluginJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 守住 [JsHttpBridge.buildHttpRequest] 的请求构造。
 *
 * 这组断言来自一次真机故障：小米插件在短信二次验证那步发空表单 POST，
 * 结果整个登录报 `method POST must have a request body`。
 *
 * 根因是空字符串被当成「没传 body」。空串与 null 在 HTTP 语义上是两回事：
 * 前者要一个 `Content-Length: 0` 的合法空 body，后者对 POST 来说是非法请求。
 *
 * 放在 `shared` 的测试源集：`buildHttpRequest` 是 `internal`，
 * 它的可见性决定了测试只能与它在同一个模块里。
 */
class JsHttpBridgeTest {

    /** 只测请求构造，不发起真实调用，也不依赖插件运行时 */
    private fun build(method: String, body: String?, options: PluginJsonObject? = null) =
        JsHttpBridge.buildHttpRequest(method, URL, body, options)

    private val URL = "https://account.xiaomi.com/pass/serviceLoginAuth2"

    /** 插件写 `body: ''` 时，必须发出一个空 body 的 POST，而不是无 body 的 POST */
    @Test
    fun postWithEmptyBody_stillHasRequestBody() {
        val request = build("POST", "")

        assertNotNull(request.body, "空串 body 也必须产出 RequestBody")
        assertEquals(0L, request.body!!.contentLength())
        assertEquals("POST", request.method)
    }

    /** 没传 body 的 POST 也要补出空 body，否则 okhttp 会直接抛异常 */
    @Test
    fun postWithoutBody_getsEmptyRequestBody() {
        val request = build("POST", null)

        assertNotNull(request.body)
        assertEquals(0L, request.body!!.contentLength())
    }

    /** PUT / PATCH 同理 */
    @Test
    fun putAndPatchWithoutBody_areAllowed() {
        listOf("PUT", "PATCH").forEach { method ->
            val request = build(method, null)
            assertNotNull(request.body, "$method 必须有 body")
            assertEquals(method, request.method)
        }
    }

    /** GET 不该凭空长出一个 body，那会让服务端困惑 */
    @Test
    fun getHasNoRequestBody() {
        val request = build("GET", null)

        assertNull(request.body)
        assertEquals("GET", request.method)
    }

    /** 正常表单提交：body 原样进入请求，Content-Type 跟着 options 走 */
    @Test
    fun formBodyAndContentTypeArePropagated() {
        val options = PluginJsonObject().apply {
            put("contentType", "application/x-www-form-urlencoded")
        }
        val request = build("POST", "user=a&hash=B", options)

        assertEquals(
            "application/x-www-form-urlencoded; charset=utf-8",
            request.body!!.contentType().toString(),
        )
        assertEquals("user=a&hash=B".length.toLong(), request.body!!.contentLength())
    }

    /** 没声明 contentType 时兜底为 JSON，不能发一个没有类型的 body */
    @Test
    fun missingContentTypeFallsBackToJson() {
        val request = build("POST", "{}")

        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
    }

    /** 单次请求的 headers 要落到 Request 上 */
    @Test
    fun perRequestHeadersAreApplied() {
        val options = PluginJsonObject("""{"headers":{"Referer":"https://a.example/"}}""")

        val request = build("GET", null, options)

        assertEquals("https://a.example/", request.header("Referer"))
    }

    /** 非 http/https 的 url 必须显式拒绝，不能悄悄发出去 */
    @Test
    fun nonHttpUrlIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            JsHttpBridge.buildHttpRequest("GET", "file:///etc/passwd", null, null)
        }
    }
}

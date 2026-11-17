package com.lifeboard.server.plugin.js

import com.lifeboard.shared.plugin.js.JsHttpSession
import com.lifeboard.shared.plugin.js.SessionCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住会话级 cookie 的匹配与隔离。
 *
 * 这组断言来自一次真实故障：插件手写 `Cookie` 头，把请求交给自己跟随重定向，
 * 结果 OkHttp 在跨域跳转时剔除 `Cookie` 头，会话断掉，服务端只回一句含糊的
 * 「非法请求」而 HTTP 状态是 200，极难定位。
 *
 * 根因是把「HTTP 会话状态」当成了「插件业务状态」。现在改由 CookieJar 托管，
 * 这里钉死它的匹配规则与插件间隔离。
 */
class JsHttpSessionTest {

    private fun url(s: String) = s.toHttpUrl()

    /** 种进去的 cookie 要能被同域请求取到 */
    @Test
    fun savedCookieIsSentToSameDomain() {
        val jar = SessionCookieJar()
        jar.put("deviceId", "ABC123", "xiaomi.com")

        val sent = jar.load(url("https://account.xiaomi.com/pass/serviceLogin"))

        assertEquals(1, sent.size)
        assertEquals("ABC123", sent[0].value)
    }

    /** 子域要能拿到父域种的 cookie（这是跨子域会话的基础） */
    @Test
    fun subDomainReceivesParentDomainCookie() {
        val jar = SessionCookieJar()
        jar.put("sdkVersion", "accountsdk-18.8.15", ".xiaomi.com")

        val sent = jar.load(url("https://identity.xiaomi.com/list"))

        assertEquals(1, sent.size)
        assertEquals("accountsdk-18.8.15", sent[0].value)
    }

    /** 不能把 cookie 发给不相干的域，那是泄露 */
    @Test
    fun cookieIsNotSentToOtherDomain() {
        val jar = SessionCookieJar()
        jar.put("passToken", "SECRET", "xiaomi.com")

        val sent = jar.load(url("https://example.com/anything"))

        assertTrue("跨域不应带 cookie", sent.isEmpty())
    }

    /** 过期的 cookie 等于删除：服务端用 Set-Cookie + 过去时间做登出 */
    @Test
    fun expiredCookieIsDropped() {
        val jar = SessionCookieJar()
        val expired = Cookie.Builder()
            .name("serviceToken")
            .value("OLD")
            .domain("xiaomi.com")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 1000)
            .build()
        jar.save(expired)

        assertNull(jar.value("serviceToken"))
        assertTrue(jar.load(url("https://account.xiaomi.com/")).isEmpty())
    }

    /** 同名 cookie 以最新的为准 */
    @Test
    fun latestValueWins() {
        val jar = SessionCookieJar()
        jar.put("ick", "OLD", "xiaomi.com")
        jar.put("ick", "NEW", "xiaomi.com")

        assertEquals("NEW", jar.value("ick"))
    }

    /** 同名 cookie 必须能按域读取，不能跨域覆盖 */
    @Test
    fun sameNameCookiesAreReadByDomain() {
        val jar = SessionCookieJar()
        jar.put("serviceToken", "miothealth", "hlth.io.mi.com")
        jar.put("serviceToken", "REAL_TOKEN", "sts-hlth.io.mi.com")

        assertEquals("REAL_TOKEN", jar.value("serviceToken", "sts-hlth.io.mi.com"))
        assertEquals("miothealth", jar.value("serviceToken", ".hlth.io.mi.com"))
        assertNull(jar.value("serviceToken", "account.xiaomi.com"))
    }

    /** 两个会话之间互不可见：A 站的登录态不能被带到 B 站 */
    @Test
    fun twoSessionsDoNotShareCookies() {
        val first = JsHttpSession("first")
        val second = JsHttpSession("second")
        first.jar.put("passToken", "TOKEN_A", "xiaomi.com")

        assertEquals("TOKEN_A", first.jar.value("passToken"))
        assertNull("另一会话不该看到这个 cookie", second.jar.value("passToken"))
    }

    /** 会话默认头要能被单独覆盖，且不影响其他会话 */
    @Test
    fun defaultHeaderIsPerSession() {
        val first = JsHttpSession("first")
        val second = JsHttpSession("second")
        first.setDefaultHeader("Referer", "https://a.example/")

        assertEquals("https://a.example/", first.defaultHeadersSnapshot()["Referer"])
        assertNull(second.defaultHeadersSnapshot()["Referer"])
    }

    /** 默认头可以被删掉 */
    @Test
    fun defaultHeaderCanBeRemoved() {
        val session = JsHttpSession("s")
        session.setDefaultHeader("X-Test", "1")
        session.setDefaultHeader("X-Test", null)

        assertNull(session.defaultHeadersSnapshot()["X-Test"])
    }

    /** 快照只给键值，用于插件排查 */
    @Test
    fun snapshotExposesNameValuePairs() {
        val session = JsHttpSession("s")
        session.jar.put("a", "1", "xiaomi.com")
        session.jar.put("b", "2", "xiaomi.com")

        val snap = session.jar.snapshot()

        assertEquals("1", snap.optString("a"))
        assertEquals("2", snap.optString("b"))
    }

    /** remove 要能删掉指定名字 */
    @Test
    fun removeDeletesByName() {
        val session = JsHttpSession("s")
        session.jar.put("token", "V", "xiaomi.com")
        session.jar.remove("token")

        assertNull(session.jar.value("token"))
    }

    /** 非法 domain / 空名字不应该造出可用 cookie */
    @Test
    fun invalidCookieIsRejected() {
        val jar = SessionCookieJar()
        jar.put("name", "value", "")
        jar.put("", "value", "xiaomi.com")

        assertTrue(jar.load(url("https://xiaomi.com/")).isEmpty())
    }

    /** value 里带特殊字符也要能原样取回 */
    @Test
    fun valueWithSpecialCharactersSurvives() {
        val jar = SessionCookieJar()
        val tricky = "a+b/c=d"
        jar.put("token", tricky, "xiaomi.com")

        assertEquals(tricky, jar.value("token"))
        assertNotNull(jar.load(url("https://xiaomi.com/")))
    }
}

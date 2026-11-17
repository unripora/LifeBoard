package com.lifeboard.server.plugin.js

import com.lifeboard.shared.plugin.js.JsHttpSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉死「一次请求该落到哪个会话」的解析规则。
 *
 * 对应一次真实故障：插件 `ctx.http.session('default')` 建了会话并往里种了
 * 伪装 cookie，但请求端把所有不带会话名的调用都派给了硬编码的
 * `__default__` 会话 —— 两边名字对不上，种进去的 cookie **一次都没生效**。
 *
 * 症状极其隐蔽：请求参数全对、HTTP 200，但服务端不认这个客户端，
 * 全程不下发 Set-Cookie，登录走到底卡在「缺少 ssecurity」。
 */
class SessionNameResolutionTest {

    /** 显式指定的会话名优先 */
    @Test
    fun explicitNameWins() {
        assertEquals(
            "acc1",
            JsHttpSession.resolveName("acc1", "default"),
        )
    }

    /** 没指定就落到「当前默认会话」，而不是内置默认 */
    @Test
    fun fallsBackToCurrentDefault() {
        val resolved = JsHttpSession.resolveName(null, "default")

        assertEquals("default", resolved)
        // 这一条正是修复的核心：以前这里会返回 "__default__"
        assertNotEquals(JsHttpSession.BUILT_IN_DEFAULT, resolved)
    }

    /** 两者都没有才用内置默认 */
    @Test
    fun finallyFallsBackToBuiltIn() {
        assertEquals(
            JsHttpSession.BUILT_IN_DEFAULT,
            JsHttpSession.resolveName(null, null),
        )
    }

    /** 空串与空白等同「没指定」，不能当成会话名 */
    @Test
    fun blankIsTreatedAsMissing() {
        assertEquals("default", JsHttpSession.resolveName("", "default"))
        assertEquals("default", JsHttpSession.resolveName("   ", "default"))
    }

    /** 起名时前后空白要去掉，避免 'default' 与 ' default' 变成两个会话 */
    @Test
    fun nameIsTrimmed() {
        assertEquals("acc1", JsHttpSession.resolveName("  acc1  ", null))
    }

    /** 解析出来的名字必须与插件建会话时用的名字一致，否则又会对不上 */
    @Test
    fun createAndRequestResolveToSameName() {
        // 插件建会话：ctx.http.session('default')
        val created = JsHttpSession.resolveName("default", null)
        // 建完后默认会话变为它；后续请求不带名字
        val used = JsHttpSession.resolveName(null, created)

        assertEquals(created, used)
    }

    /** 会话隔离：两个会话的 cookie 互不可见 */
    @Test
    fun sessionsAreIsolated() {
        val a = JsHttpSession("a")
        val b = JsHttpSession("b")
        a.jar.put("deviceId", "AAA", "xiaomi.com")

        assertEquals("AAA", a.jar.value("deviceId"))
        assertNull(b.jar.value("deviceId"))
    }

    /** 同一会话里同名 cookie 以最新为准 */
    @Test
    fun latestCookieWins() {
        val session = JsHttpSession("s")
        session.jar.put("deviceId", "OLD", "xiaomi.com")
        session.jar.put("deviceId", "NEW", "xiaomi.com")

        assertEquals("NEW", session.jar.value("deviceId"))
    }
}

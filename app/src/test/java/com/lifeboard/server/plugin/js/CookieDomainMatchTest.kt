package com.lifeboard.server.plugin.js

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉死 cookie 的域匹配规则。
 *
 * 背景：2FA 全流程走完后，会话里只剩我们种进去的伪装 cookie，
 * 服务端下发的 passToken / cUserId 一个都没有；同时 `verifyPhone` 一路
 * 能通（说明部分请求带上了 cookie），但认证跳转的 Set-Cookie 收不到。
 *
 * 需要排除的一种可能：`cookieSet(name, value, 'xiaomi.com')` 种下的 cookie
 * 在 `account.xiaomi.com` 的请求里到底带不带。OkHttp 的 `domain()` 是
 * **精确匹配**，与浏览器「父域自动包含子域」的直觉不同。写错了会表现为
 * 「伪装 cookie 时灵时不灵」，极难定位。
 */
class CookieDomainMatchTest {

    private fun cookieOf(name: String, value: String, domain: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(domain).path("/").build()

    /**
     * `domain("xiaomi.com")` **会**匹配 `account.xiaomi.com`。
     *
     * 这条最初写反了（以为 OkHttp 的 domain() 是精确匹配、不含子域），
     * 实测证明它与浏览器直觉一致：父域 cookie 对子域生效。
     * 留在这里是为了防止有人凭直觉去改插件的 cookieSet 域参数。
     */
    @Test
    fun bareDomainMatchesSubdomain() {
        val cookie = cookieOf("deviceId", "ABC", "xiaomi.com")

        val matches = cookie.matches("https://account.xiaomi.com/pass/serviceLogin".toHttpUrl())

        assertEquals(true, matches)
    }

    /** 前导点在 OkHttp 里不被接受，说明「靠点区分父子域」这套在 OkHttp 行不通 */
    @Test(expected = IllegalArgumentException::class)
    fun leadingDotDomainIsRejected() {
        cookieOf("deviceId", "ABC", ".xiaomi.com")
    }

    /** 精确主机名只匹配它自己 */
    @Test
    fun exactHostOnlyMatchesThatHost() {
        val cookie = cookieOf("deviceId", "ABC", "account.xiaomi.com")

        assertEquals(true, cookie.matches("https://account.xiaomi.com/x".toHttpUrl()))
        assertEquals(false, cookie.matches("https://identity.xiaomi.com/x".toHttpUrl()))
    }

    /** 服务端 Set-Cookie 的 Domain 属性由 OkHttp 解析，这里验证解析结果能被正确匹配 */
    @Test
    fun parsedSetCookieMatchesItsDomain() {
        val url = "https://account.xiaomi.com/pass/serviceLogin".toHttpUrl()
        val parsed = Cookie.parse(url, "passToken=ABC; Domain=xiaomi.com; Path=/")

        assertEquals(true, parsed != null)
        // Domain=xiaomi.com 由服务端显式给出，OkHttp 会规范化成 .xiaomi.com，
        // 因此它应该能匹配回 account.xiaomi.com
        println("解析出的 domain=${parsed!!.domain}，匹配子域=${parsed.matches(url)}")
        assertEquals(true, parsed.matches(url))
    }
}

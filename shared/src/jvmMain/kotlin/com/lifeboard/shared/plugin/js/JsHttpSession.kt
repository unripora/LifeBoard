package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个 HTTP 会话：持有 cookie 与重定向策略。
 *
 * ## 为什么需要它
 *
 * 早期版本的 `ctx.http` 只提供「发一个请求」的能力，cookie 与重定向全由插件
 * 自己用 header 拼。这在登录流程上出了两个必然踩到的坑：
 *
 * 1. **重定向时 cookie 会丢**。OkHttp 跟随重定向时会重新构造请求，`Cookie`
 *    属于逐跳语义的头，会被剔除后重新计算；没有 CookieJar 就没有人把它补回来。
 *    表现是「A 站登录成功，跳到 B 站时变成未登录」，服务端只回一句含糊的
 *    「非法请求」，而 HTTP 状态仍是 200，极难定位。
 * 2. **Set-Cookie 要手动解析**。同名多值、Path、Domain、过期时间全靠插件处理，
 *    每个插件都会重新实现一遍，且很难写对。
 *
 * 这里把这两件事收回宿主：每个会话对应一个 [SessionCookieJar]，挂到派生的
 * `OkHttpClient` 上，之后的重定向、Set-Cookie 全由 OkHttp 处理。
 *
 * ## 隔离
 *
 * cookie 按**插件**隔离，不同插件的会话互不可见。共用一份会让 A 站的登录态
 * 被带到 B 站去，这属于凭据泄露，不是「方便」。
 *
 * ## 线程
 *
 * 会话对象本身只存配置，可被任意线程读；真正的请求仍然通过
 * [JsHttpBridge] 投递到插件专用线程再到 OkHttp。cookie 容器内部用并发结构。
 */
class JsHttpSession(
    /** 会话标识，由插件声明，作用域限于本插件 */
    val name: String,
) {
    companion object {
        /** 插件完全没建会话时用的名字 */
        const val BUILT_IN_DEFAULT = "__default__"

        /**
         * 解析一次请求该落到哪个会话。
         *
         * 优先用请求自己指定的会话名，其次用当前默认会话，都没有才用内置默认。
         *
         * 这层解析单独抽出来是因为踩过一次：请求端写死用内置默认会话，
         * 而插件 `ctx.http.session('default')` 建的是另一个会话，两边名字对不上，
         * 插件种进去的伪装 cookie 一次都没生效。抽成纯函数后可以直接测。
         */
        fun resolveName(requested: String?, currentDefault: String?): String {
            val explicit = requested?.trim().orEmpty()
            if (explicit.isNotEmpty()) return explicit
            val fallback = currentDefault?.trim().orEmpty()
            return fallback.ifEmpty { BUILT_IN_DEFAULT }
        }
    }

    /** cookie 容器，线程安全 */
    val jar: SessionCookieJar = SessionCookieJar()

    /** 是否跟随重定向。插件可以按需改成 false 以逐跳读取 Set-Cookie。 */
    @Volatile
    var followRedirects: Boolean = true

    /** 默认 User-Agent；为 null 表示用 OkHttp 默认 */
    @Volatile
    var userAgent: String? = null

    /** 默认请求头，每次请求都会带上，单次请求里的 headers 优先级更高 */
    private val defaultHeaders = ConcurrentHashMap<String, String>()

    fun setDefaultHeader(name: String, value: String?) {
        if (value == null) defaultHeaders.remove(name) else defaultHeaders[name] = value
    }

    fun defaultHeadersSnapshot(): Map<String, String> = HashMap(defaultHeaders)
}

/**
 * 会话级 cookie 容器，实现 OkHttp 的 `CookieJar` 语义。
 *
 * 每个 [okhttp3.Cookie] 自带 domain / path / 过期时间，查找时按请求 URL 匹配。
 * 这里不做持久化：会话随插件运行时一起销毁，登录凭据本身由插件通过
 * `ctx.credentials` 显式落盘——那是业务数据，与 HTTP 会话状态是两件事。
 */
class SessionCookieJar {
    private val cookies = ConcurrentHashMap<String, okhttp3.Cookie>()

    private fun keyOf(c: okhttp3.Cookie): String = "${c.domain}|${c.path}|${c.name}"

    fun save(c: okhttp3.Cookie) {
        // 已过期的 cookie 等于删除：服务端用 Set-Cookie + 过去时间做登出
        if (c.expiresAt < System.currentTimeMillis()) {
            cookies.remove(keyOf(c))
        } else {
            cookies[keyOf(c)] = c
        }
    }

    fun saveAll(list: List<okhttp3.Cookie>) {
        list.forEach { save(it) }
    }

    /** 按 URL 匹配可发送的 cookie */
    fun load(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val now = System.currentTimeMillis()
        return cookies.values.filter { c -> c.expiresAt >= now && c.matches(url) }
    }

    fun remove(name: String) {
        cookies.entries.removeIf { it.value.name == name }
    }

    fun clear() {
        cookies.clear()
    }

    /** 给插件读单个 cookie 的值；同名 cookie 可按 domain/path 精确区分。 */
    fun value(name: String, domain: String? = null, path: String? = null): String? {
        val host = domain?.trim()?.trimStart('.')
        val now = System.currentTimeMillis()
        return cookies.values.firstOrNull { c ->
            c.name == name && c.expiresAt >= now &&
                (host == null || c.domain == host) &&
                (path == null || c.path == path)
        }?.value
    }

    /** 给插件导出全部 cookie（键 → 值），同名取第一个 */
    fun snapshot(): PluginJsonObject {
        val obj = PluginJsonObject()
        cookies.values.forEach { c ->
            if (!obj.has(c.name)) obj.put(c.name, c.value)
        }
        return obj
    }

    /**
     * 插件手动种一个 cookie。
     *
     * 伪造身份的场景（比如把自己声明成某个 App 的设备）需要这一步：
     * 这类 cookie 不是服务端下发的，但没有它服务端就不认这个客户端。
     */
    fun put(name: String, value: String, domain: String, path: String = "/") {
        // OkHttp 的 Cookie.Builder 会自动处理前导点（表示包含子域），
        // 所以这里只要把点去掉，行为与浏览器一致
        val host = domain.trim().trimStart('.')
        if (host.isEmpty() || name.isEmpty()) return
        runCatching {
            save(
                okhttp3.Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path.ifBlank { "/" })
                    .domain(host)
                    .build(),
            )
        }
    }

    /** 供桥接层拼调试日志：只给键名，不给值 */
    fun names(): PluginJsonArray = PluginJsonArray().apply {
        cookies.values.map { it.name }.distinct().forEach { put(it) }
    }
}

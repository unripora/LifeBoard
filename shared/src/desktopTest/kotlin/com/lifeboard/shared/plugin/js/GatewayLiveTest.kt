package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialSpec
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `gateway.ts` 对真实 dsh-mobile 网关的连通性验证。
 *
 * ## 为什么要有这个测试
 *
 * 之前所有验证都停在「编译通过 + 单元测试绿」，而 CA 钉扎这件事
 * **只有真的和自签网关握一次手才算数**。Windows 自带的 curl 用 schannel
 * 做 TLS，根本不读 `--cacert`（报 `did not add any certificates from CA file`），
 * 所以 curl 失败完全不能说明代码有问题——必须用 Java 的 `javax.net.ssl`
 * 走一遍，即 `PluginTls` 的真实路径。
 *
 * ## 前提条件
 *
 * 需要本机正在运行 dsh-mobile 网关。找不到时用 `assumeTrue` 跳过而非失败：
 * 在 CI 或没装插件的机器上，这条用例没有意义，让它红只会训练人忽略红灯。
 *
 * 网关地址与 CA 从 dsh-mobile 的 state 文件读，读不到再用环境变量覆盖。
 */
class GatewayLiveTest {

    /** dsh-mobile 的状态目录（`setup.json` 里记录了监听端口与 CA 路径） */
    private fun mobileAccessDir(): File =
        File(System.getProperty("user.home"), ".dsh/mobile-access")

    private fun readSetup(): Map<String, Any?>? {
        val file = File(mobileAccessDir(), "setup.json")
        if (!file.exists()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            com.lifeboard.shared.plugin.json.PluginJsonObject(file.readText()).let { o ->
                mapOf(
                    "networkInterface" to o.optString("networkInterface"),
                    "listenPort" to o.optInt("listenPort", 0),
                    "caCertFile" to o.optJSONObject("tls")?.optString("caCertFile"),
                )
            }
        }.getOrNull()
    }

    /**
     * 找到网关实际监听的地址。
     *
     * ## 为什么不能按网卡名找
     *
     * `setup.json` 里 `networkInterface` 是 `"WLAN"`——那是 **Windows 的别名**，
     * Java 的 `NetworkInterface.getName()` 拿到的是 `wireless_32768`，
     * `getByName("WLAN")` 返回 `null`。之前退回「取第一个非回环网卡」，
     * 结果拿到 Hyper-V 的 `172.24.192.1`，连到一个没人监听的地址，
     * 报的是 `ConnectException` 而不是业务错误。
     *
     * ## 按监听端口反查
     *
     * 先枚举本站所有 IPv4，再逐个探 TCP 端口：能连上的那个就是网关。
     * 这比猜网卡名可靠——它验的是「这个地址上真的有东西在听」。
     */
    private fun findListeningGateway(): String? {
        val port = (readSetup()?.get("listenPort") as? Int)?.takeIf { it > 0 } ?: return null
        val candidates = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { ip ->
            runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(ip, port), 300)
                    true
                }
            }.getOrDefault(false)
        }
    }

    private data class Target(val gateway: String, val caFile: File)

    private fun locate(): Target? {
        val setup = readSetup() ?: return null
        val ip = findListeningGateway() ?: return null
        val port = setup["listenPort"] as? Int ?: return null
        // setup.json 里记的是 PEM；同目录下还有网关实际下发的 DER，优先用它
        val der = File(mobileAccessDir(), "tls/dsh-mobile-ca.cer")
        val ca = if (der.exists()) der else {
            val pem = setup["caCertFile"] as? String ?: return null
            File(pem).takeIf { it.exists() } ?: return null
        }
        return Target("https://$ip:$port", ca)
    }

    /**
     * 宿主的 `PluginTls` 能凭 manifest 指纹信任网关并完成握手。
     *
     * 这是 M8.1 的真实验收项：单张 CA 的 KeyStore 构造、指纹比对、
     * 以及不带系统根证书的握手。
     */
    @Test
    fun `PluginTls 能连上真实网关并取回 CA`(): Unit = runBlocking {
        val target = locate()
        assumeTrue("本机没有运行中的 dsh-mobile 网关，跳过", target != null)
        val t = target!!

        // PEM 是文本，DER 是二进制；两种都要能算指纹
        val caBytes = t.caFile.readBytes()
        val der = if (caBytes.isNotEmpty() && caBytes[0] == '<'.code.toByte()) {
            val b64 = String(caBytes, Charsets.UTF_8).split("-----")[2].replace(Regex("\\s"), "")
            java.util.Base64.getDecoder().decode(b64)
        } else {
            caBytes
        }

        // 指纹不再与包内常量比对：manifest 是 `runtime` 模式，期望值来自
        // 用户粘贴的配对链接 `instance=`。这里只验宿主钉扎链路本身——
        // 「指纹怎么算、能不能凭它握手」——来源是否正确由配对用例覆盖
        // （它断言网关自报的 instanceId 等于 CA 指纹）。
        val fingerprint = PluginTls.fingerprintOf(der)

        val client = PluginTls.pin(JsHttpBridge.defaultClient(), der)
        val request = okhttp3.Request.Builder()
            .url("${t.gateway}/mobile-access/ca.cer")
            .header("Origin", t.gateway)
            .build()
        client.newCall(request).execute().use { response ->
            org.junit.Assert.assertEquals("取 CA 失败：HTTP ${response.code}", 200, response.code)
            val body = response.body?.bytes() ?: ByteArray(0)
            assertTrue(body.isNotEmpty(), "CA 响应体为空")
            // 服务端返回的就是同一张证书，指纹必须一致
            org.junit.Assert.assertEquals(
                "服务端返回的 CA 与本地文件不是同一张",
                fingerprint,
                PluginTls.fingerprintOf(body),
            )
        }
    }

    /**
     * 未声明指纹时**不能**连上自签网关。
     *
     * 这条比上面更重要：钉扎的价值全在拒绝一侧。如果默认 client 也能连上，
     * 说明系统信任库里混进了这张 CA，钉扎就成了摆设。
     */
    @Test
    fun `未钉扎的 client 连不上自签网关`(): Unit = runBlocking {
        val target = locate()
        assumeTrue("本机没有运行中的 dsh-mobile 网关，跳过", target != null)
        val t = target!!

        val request = okhttp3.Request.Builder()
            .url("${t.gateway}/mobile-access/ca.cer")
            .header("Origin", t.gateway)
            .build()
        val failed = runCatching {
            JsHttpBridge.defaultClient().newCall(request).execute().use { it.code }
        }
        assertTrue(
            failed.isFailure,
            "默认 client 竟然连上了自签网关，说明系统信任库里有这张 CA，钉扎失效",
        )
    }

    /**
     * 未配对的请求必须被拒（401/403），而不是 200。
     *
     * 反过来说明：只要后续能拿到 200，就确实是 Cookie 在起作用，
     * 不是网关漏了校验。
     */
    @Test
    fun `无 Cookie 的业务请求被网关拒绝`(): Unit = runBlocking {
        val target = locate()
        assumeTrue("本机没有运行中的 dsh-mobile 网关，跳过", target != null)
        val t = target!!
        val client = PluginTls.pin(JsHttpBridge.defaultClient(), readCaDer(t.caFile))
        val request = okhttp3.Request.Builder()
            .url("${t.gateway}/api/remote.mux")
            .header("Origin", t.gateway)
            .build()
        client.newCall(request).execute().use { response ->
            assertTrue(
                response.code == 401 || response.code == 403,
                "未带会话 Cookie 的请求返回了 ${response.code}，网关没有拦住",
            )
        }
    }

    /**
     * 完整配对链路。
     *
     * 配对码默认只有 **120 秒**有效期（`config.ts:488`，上限 10 分钟）且**一次性**
     * （`access.ts:341` 成功后立刻清窗），所以这条用例不能常驻跑：
     * 用系统属性 `dsh.pairingCode` 传入新鲜配对码，没传就跳过。
     *
     * ```bash
     * ./gradlew :shared:desktopTest --tests "...GatewayLiveTest" -Ddsh.pairingCode=<43位码>
     * ```
     *
     * 走的是 `JsHttpBridge` 的真实路径（含 CookieJar），不是手搓 socket——
     * 这里要验的正是「宿主桥接层能不能完成这条链路」。
     */
    @Test
    fun `配对全链路`(): Unit = runBlocking {
        val code = System.getProperty("dsh.pairingCode").orEmpty().trim()
        assumeTrue("未提供 -Ddsh.pairingCode，跳过配对用例", code.length == 43)
        val target = locate()
        assumeTrue("本机没有运行中的 dsh-mobile 网关，跳过", target != null)
        val t = target!!

        val der = readCaDer(t.caFile)
        val client = PluginTls.pin(JsHttpBridge.defaultClient(), der)

        // 1. 配对
        val pairRequest = okhttp3.Request.Builder()
            .url("${t.gateway}/mobile-access/auth/native-pair")
            .header("Origin", t.gateway)
            .post(
                """{"token":"$code","label":"LifeBoard-Test"}"""
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        val pair = client.newCall(pairRequest).execute().use { parseJson(it) }
        val sessionToken = pair["sessionToken"] as? String
        val csrfToken = pair["csrfToken"] as? String
        val deviceToken = pair["deviceToken"] as? String
        assertTrue(sessionToken != null && sessionToken.isNotBlank(), "配对未返回 sessionToken：$pair")
        assertTrue(csrfToken != null && csrfToken.isNotBlank(), "配对未返回 csrfToken")
        assertTrue(deviceToken != null && deviceToken.isNotBlank(), "配对未返回 deviceToken")

        // instanceId 就是 CA 指纹。这一条把「用户看到的配对指纹」与
        // 「manifest 钉扎的指纹」绑定起来——两者不一致时用户没法核对
        org.junit.Assert.assertEquals(
            "网关的 instanceId 应等于 CA 指纹",
            PluginTls.fingerprintOf(der),
            pair["instanceId"],
        )

        // 2. 带会话 Cookie 的请求应通过鉴权（/api/remote.mux 只接受 WS 升级，所以是 404 而非 401）
        val host = t.gateway.removePrefix("https://")
        val cookieClient = PluginTls.pin(
            JsHttpBridge.defaultClient().newBuilder()
                .cookieJar(object : okhttp3.CookieJar {
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) = Unit
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                        listOf(
                            okhttp3.Cookie.Builder()
                                .domain(host.substringBefore(':'))
                                .name("__Host-dsh_ma_session")
                                .value(sessionToken!!)
                                .path("/")
                                .secure()
                                .build(),
                        )
                })
                .build(),
            der,
        )
        cookieClient.newCall(
            okhttp3.Request.Builder()
                .url("${t.gateway}/api/remote.mux")
                .header("Origin", t.gateway)
                .build(),
        ).execute().use { response ->
            assertTrue(
                response.code != 401,
                "带会话 Cookie 仍被判未鉴权（HTTP ${response.code}），Cookie 名或路径可能不对",
            )
        }

        // 3. 续期：session 与 csrf 轮换，deviceToken 不变
        val renewRequest = okhttp3.Request.Builder()
            .url("${t.gateway}/mobile-access/auth/native-renew")
            .header("Origin", t.gateway)
            .post(
                """{"deviceToken":"$deviceToken"}"""
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        val renewed = client.newCall(renewRequest).execute().use { parseJson(it) }
        assertTrue(
            (renewed["sessionToken"] as? String) != sessionToken,
            "续期后 sessionToken 未轮换",
        )
        assertTrue(renewed["deviceToken"] == null, "续期不应返回新的 deviceToken")

        // 4. 写请求缺 CSRF 必须被拒
        val noCsrf = client.newCall(
            okhttp3.Request.Builder()
                .url("${t.gateway}/api/remote.mux")
                .header("Origin", t.gateway)
                .header("Cookie", "__Host-dsh_ma_session=$sessionToken")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { it.code }
        assertTrue(noCsrf == 403, "写请求缺 CSRF 头未被拒（HTTP $noCsrf）")

        // 5. 错误 Origin 必须被拒
        val badOrigin = client.newCall(
            okhttp3.Request.Builder()
                .url("${t.gateway}/api/remote.mux")
                .header("Origin", "https://evil.example")
                .build(),
        ).execute().use { it.code }
        assertTrue(badOrigin == 403, "错误 Origin 未被拒（HTTP $badOrigin）")
    }

    /** 读 CA 文件并统一成 DER：PEM 是文本（以 `-----` 开头），DER 是二进制 */
    private fun readCaDer(file: File): ByteArray {
        val bytes = file.readBytes()
        if (bytes.isEmpty() || bytes[0] != '-'.code.toByte()) return bytes
        val b64 = String(bytes, Charsets.UTF_8).split("-----")[2].replace(Regex("\\s"), "")
        return java.util.Base64.getDecoder().decode(b64)
    }

    private fun parseJson(response: okhttp3.Response): Map<String, Any?> {
        val text = response.body?.string().orEmpty()
        val obj = runCatching { com.lifeboard.shared.plugin.json.PluginJsonObject(text) }.getOrNull()
            ?: error("响应不是 JSON 对象（HTTP ${response.code}）：$text")
        return obj.keys().associateWith { obj.opt(it) }
    }
}

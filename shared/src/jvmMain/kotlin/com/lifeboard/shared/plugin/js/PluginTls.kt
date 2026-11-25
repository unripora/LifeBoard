package com.lifeboard.shared.plugin.js

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 插件自签证书的钉扎信任。
 *
 * ## 为什么需要
 *
 * 局域网里的服务（例如电脑上的 DSH 移动网关）用的是自建 CA 签发的证书，
 * 系统信任库不认识它。默认 OkHttp 会直接抛 SSL 握手失败，插件连不上。
 *
 * 把系统信任库整个关掉是一种做法，但那样插件就能连任何伪造的服务；
 * 这里改成**只信任插件自己声明的那张证书**，其余一律拒绝。
 *
 * ## 信任怎么建立
 *
 * 指纹来自 manifest 的声明，而不是运行时由插件自己给——插件若能随口
 * 说「信任这张 CA」，那这个机制就形同虚设：一个被篡改的插件可以先替换
 * 证书再声明它。指纹固定写在包里，内容哈希变化会导致重新信任，
 * 用户能看到「这个插件要信任一张自签证书」并核对指纹。
 *
 * ## 跨平台
 *
 * 只用 `javax.net.ssl`，Android 与桌面 JVM 都能用，因此放在 jvmMain。
 */
object PluginTls {

    /**
     * 一张被声明的 CA。
     *
     * @param sha256 证书 DER 的 SHA-256（小写十六进制，不含冒号）
     */
    data class PinnedCa(val sha256: String)

    /** 指纹格式：64 位十六进制，允许 `AA:BB:...` 或带空格的写法，比较前统一规范化 */
    fun normalizeFingerprint(raw: String): String? {
        val cleaned = raw.filter { !it.isWhitespace() && it != ':' }.lowercase()
        if (cleaned.length != 64 || !cleaned.all { it in "0123456789abcdef" }) return null
        return cleaned
    }

    /** 计算一张证书 DER 的指纹，用于和 manifest 声明比对 */
    fun fingerprintOf(caDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(caDer)
            .joinToString("") { "%02x".format(it) }

    /**
     * 校验 CA 字节是否匹配声明的指纹。
     *
     * @return true 表示指纹一致；不一致时必须拒绝使用，不能「先连上再说」
     */
    fun verify(caDer: ByteArray, pinned: PinnedCa): Boolean =
        fingerprintOf(caDer) == pinned.sha256

    /**
     * 用单张 CA 构造 socket 工厂。
     *
     * 不复用系统默认信任锚：那样等于「既信任自己的 CA，也信任所有公共 CA」，
     * 与钉扎的目的相反。
     */
    fun socketFactory(caDer: ByteArray): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, trustManagers(caDer), null) }
            .socketFactory

    /** 与 [socketFactory] 配套的 TrustManager；OkHttp 要求两者一起设置 */
    fun trustManager(caDer: ByteArray): X509TrustManager =
        trustManagers(caDer).filterIsInstance<X509TrustManager>().first()

    private fun trustManagers(caDer: ByteArray): Array<javax.net.ssl.TrustManager> {
        val ca = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(caDer)) as X509Certificate
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("plugin-pinned-ca", ca)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
    }

    /**
     * 返回一个只信任 [caDer] 的 client。
     *
     * 保留原 client 的其余配置（超时、连接池、cookie jar），只替换 TLS 层。
     */
    fun pin(client: OkHttpClient, caDer: ByteArray): OkHttpClient =
        client.newBuilder()
            .sslSocketFactory(socketFactory(caDer), trustManager(caDer))
            .build()
}

/**
 * 不校验服务端证书的 TLS。
 *
 * ## 只有一个合法用途
 *
 * 取回自签 CA 的**证书本身**：那一刻 CA 还没被信任，用正常校验必然失败。
 * 证书是公开信息（不含私钥），取错一张不泄露任何东西——
 * 安全边界在随后的指纹比对，而不在这一次请求。
 *
 * ## 不要用于业务请求
 *
 * 名字里的 `Insecure` 是故意的：任何在业务请求上用它的人都应该看到这个词
 * 并停下来想一次。加这个类时已经限定到单条路径，且要求 https。
 */
internal object InsecureTls {

    fun trustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager()), null) }
            .socketFactory
}

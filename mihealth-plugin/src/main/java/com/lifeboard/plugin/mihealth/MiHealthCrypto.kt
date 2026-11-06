package com.lifeboard.plugin.mihealth

import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * 小米运动健康云端接口的加解密与签名。
 *
 * 协议来自对 `com.xiaomi.fitness.app.CloudInterceptor` 的逆向
 * （参考 `external/mihealth-api/mihealth_client.py`），要点：
 *
 * ```
 * nonce      = b64( random(8B) | int32BE(minutes_since_epoch + timeDiff) )
 * sessionKey = b64( SHA256( b64dec(ssecurity) | b64dec(nonce) ) )   // 32 字节
 *
 * // 加密请求（本 API 默认 encryptResponse=true）
 * rc4_hash__ = b64( SHA1( METHOD & path & k=v... & sessionKey ) )   // 明文参数算
 * 每个值      = b64( RC4_drop1024(sessionKey, value) )
 * signature  = b64( SHA1( METHOD & path & k=rc4(v)... & sessionKey ) )
 * ```
 *
 * 纯算法、无 Android 依赖，可单独跑单测。
 */
object MiHealthCrypto {

    /**
     * RC4 密钥流的丢弃字节数。
     *
     * 必须丢弃，且必须是 1024：小米的实现（`o0l` 类）在加密前先吃掉
     * 1024 字节密钥流。用标准 RC4（不丢）解出来是乱码，且不会报错，
     * 只会得到无法解析的 JSON，排查时极易误判成「接口变了」。
     */
    private const val RC4_DROP = 1024

    /** 会话密钥固定 32 字节；长度不符说明 ssecurity/nonce 有问题，直接失败更快 */
    private const val SESSION_KEY_SIZE = 32

    private fun b64e(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)

    /**
     * 生成 nonce：8 字节随机 + 4 字节大端「分钟级时间戳」。
     *
     * 时间戳用分钟而不是秒，是协议本身的约定；[timeDiff] 用于校正
     * 客户端与服务端的时钟偏差（服务端会校验时间窗）。
     */
    fun genNonce(timeDiffMillis: Long = 0L): String {
        val random = ByteArray(8)
        Random.nextBytes(random)
        val minutes = (System.currentTimeMillis() + timeDiffMillis) / 60_000L
        val timeBytes = byteArrayOf(
            (minutes ushr 24).toByte(),
            (minutes ushr 16).toByte(),
            (minutes ushr 8).toByte(),
            minutes.toByte(),
        )
        return b64e(random + timeBytes)
    }

    /** sessionKey = b64( SHA256( b64dec(ssecurity) + b64dec(nonce) ) ) */
    fun sessionKey(ssecurity: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(b64d(ssecurity))
        digest.update(b64d(nonce))
        return b64e(digest.digest())
    }

    /**
     * RC4 加解密（同一次调用既加密也解密）。
     *
     * @param key 32 字节会话密钥
     */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == SESSION_KEY_SIZE) { "rc4 key must be $SESSION_KEY_SIZE bytes" }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        // 先吃掉 drop 字节的密钥流：只推进状态，不真的生成 1024 字节输出
        repeat(RC4_DROP) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun sha1B64(items: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return b64e(digest.digest(items.joinToString("&").toByteArray(Charsets.UTF_8)))
    }

    /** 取 URL 的 path 部分参与签名；已是纯 path 时原样返回 */
    private fun pathOf(path: String): String {
        val idx = path.indexOf("://")
        if (idx < 0) return path.substringBefore('?')
        val afterScheme = path.indexOf('/', idx + 3)
        if (afterScheme < 0) return "/"
        return path.substring(afterScheme).substringBefore('?')
    }

    /**
     * 构造加密请求的参数表。
     *
     * 注意顺序：`rc4_hash__` 是在**加密循环之前**插入明文表的，
     * 所以它自己也会被 RC4 加密（而不是单独保留明文）。
     *
     * @param params 原始参数（值为空/null 的会被剔除）
     * @return 可直接作为 query（GET）或 form body（POST）发送的参数表
     */
    fun signedEncrypted(
        method: String,
        path: String,
        params: Map<String, String>,
        nonce: String,
        ssecurity: String,
    ): Map<String, String> {
        val skey = sessionKey(ssecurity, nonce)
        val p = pathOf(path)
        val upper = method.uppercase()

        val plain = params.filterValues { it.isNotBlank() }.toMutableMap()
        val rc4Hash = sha1B64(
            buildList {
                add(upper)
                add(p)
                plain.entries.sortedBy { it.key }.forEach { add("${it.key}=${it.value}") }
                add(skey)
            },
        )
        plain["rc4_hash__"] = rc4Hash

        // 同一个 RC4 实例按顺序加密所有值（流式密码，顺序敏感）
        val cipher = Rc4State(b64d(skey))
        val enc = LinkedHashMap<String, String>()
        plain.entries.sortedBy { it.key }.forEach { (k, v) ->
            enc[k] = b64e(cipher.crypt(v.toByteArray(Charsets.UTF_8)))
        }

        val signature = sha1B64(
            buildList {
                add(upper)
                add(p)
                enc.forEach { add("${it.key}=${it.value}") }
                add(skey)
            },
        )
        enc["signature"] = signature
        enc["_nonce"] = nonce
        return enc
    }

    /** 解密响应体：RC4( sessionKey, b64dec(body) ) → UTF-8 文本 */
    fun decryptResponse(bodyB64: String, nonce: String, ssecurity: String): String {
        val key = b64d(sessionKey(ssecurity, nonce))
        return String(rc4(key, b64d(bodyB64)), Charsets.UTF_8)
    }

    /**
     * 保持状态的 RC4，用于「多个参数依次加密」的场景。
     *
     * 协议要求所有参数值在**同一个密钥流**上连续加密（不是每个值重新初始化），
     * 所以不能对每个值调用一次 [rc4]。
     */
    private class Rc4State(key: ByteArray) {
        private val s = IntArray(256) { it }
        private var i = 0
        private var j = 0

        init {
            require(key.size == SESSION_KEY_SIZE)
            var k = 0
            for (idx in 0 until 256) {
                k = (k + s[idx] + (key[idx % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[idx]; s[idx] = s[k]; s[k] = t
            }
            repeat(RC4_DROP) { advance() }
        }

        private fun advance() {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }

        fun crypt(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (n in data.indices) {
                advance()
                out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }
    }
}

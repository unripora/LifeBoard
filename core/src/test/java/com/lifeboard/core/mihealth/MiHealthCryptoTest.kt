package com.lifeboard.core.mihealth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * [MiHealthCrypto] 与参考实现的一致性校验。
 *
 * 基准向量由 `external/mihealth-api/mihealth_client.py` 生成：
 * 固定 ssecurity / nonce / 参数，把 Python 参考实现的输出抄在这里做比对。
 *
 * 为什么必须逐字节比对：这套协议里有几处「错了也不报错」的坑——
 * RC4 忘了 drop-1024、参数加密时复用同一个密钥流 vs 每次重新初始化、
 * rc4_hash__ 是加密前算还是加密后算。任何一处错了，服务端只会返回
 * 无法解密的乱码，排查成本极高。用参考实现钉死是最省事的办法。
 */
class MiHealthCryptoTest {

    /** 固定的假 ssecurity（base64，解码后 37 字节，仅测试用） */
    private val ssecurity = "dGhpcy1pcy1hLWZha2Utc3NlY3VyaXR5LXZhbHVlLTMyYnl0ZXMh"

    /** 固定 12 字节 nonce：0x00..0x0B，避免随机导致不可复现 */
    private val nonce: String =
        Base64.getEncoder().encodeToString(ByteArray(12) { it.toByte() })

    @Test
    fun `sessionKey 与参考实现一致`() {
        assertEquals(
            "fvu4AiEjCFOkAxG0xvP8M/jUtVbG5tmQXUkoqYk3GXM=",
            MiHealthCrypto.sessionKey(ssecurity, nonce),
        )
    }

    @Test
    fun `rc4 drop1024 加密结果与参考实现一致`() {
        val key = Base64.getDecoder().decode(MiHealthCrypto.sessionKey(ssecurity, nonce))
        val enc = MiHealthCrypto.rc4(key, "hello-mi-health".toByteArray())
        assertEquals("3vdtMTJqtCYZ64IbBHNN", Base64.getEncoder().encodeToString(enc))
    }

    @Test
    fun `rc4 加解密可逆`() {
        val key = Base64.getDecoder().decode(MiHealthCrypto.sessionKey(ssecurity, nonce))
        val plain = "步数 8432 steps".toByteArray()
        val round = MiHealthCrypto.rc4(key, MiHealthCrypto.rc4(key, plain))
        assertEquals(String(plain), String(round))
    }

    @Test
    fun `signedEncrypted 的参数密文与签名与参考实现一致`() {
        val dataJson =
            """{"key":"steps","startTime":1730000000000,"endTime":1730086400000,"reverse":true,"next_key":null}"""

        val out = MiHealthCrypto.signedEncrypted(
            method = "GET",
            path = "/app/v1/data/get_fitness_data_by_time",
            params = mapOf("data" to dataJson),
            nonce = nonce,
            ssecurity = ssecurity,
        )

        assertEquals(
            "zbBqOCRl421H94IKGyUJBp6lYKc7FN3ugJET+Cqnf8neb2q0c2MgJOpNbNXIdox67QZNYgeH9SSpKJpv060jXlPkNNEcmpbVBFfr5xABfA0sR2d68wXrHFx5T05Ukaaw",
            out["data"],
        )
        assertEquals("d51GGOeHdRqGdfeCEDBz3v55zx7wFHCArwE6KA==", out["rc4_hash__"])
        assertEquals("2xXsitbjl/Msw6k3Z/qDC/45Jrs=", out["signature"])
        assertEquals(nonce, out["_nonce"])
    }

    @Test
    fun `nonce 长度固定且末 4 字节是分钟时间戳`() {
        val raw = Base64.getDecoder().decode(MiHealthCrypto.genNonce())
        assertEquals(12, raw.size)

        // 末 4 字节大端还原后应与「当前分钟数」接近（允许 1 分钟误差）
        val encoded = ((raw[8].toInt() and 0xFF) shl 24) or
            ((raw[9].toInt() and 0xFF) shl 16) or
            ((raw[10].toInt() and 0xFF) shl 8) or
            (raw[11].toInt() and 0xFF)
        val nowMinutes = System.currentTimeMillis() / 60_000L
        val diff = kotlin.math.abs(encoded - nowMinutes)
        org.junit.Assert.assertTrue("时间戳偏差过大: $diff 分钟", diff <= 1)
    }
}

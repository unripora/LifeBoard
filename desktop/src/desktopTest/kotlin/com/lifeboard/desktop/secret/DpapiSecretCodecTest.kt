package com.lifeboard.desktop.secret

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * DPAPI 加解密的回归测试（M4-4.3）。
 *
 * ## 为什么这组测试必须跑在真机上
 *
 * DPAPI 的密钥由**当前 Windows 登录用户**派生，没有任何 mock 能模拟
 * 「换账户后解不开」。这里能测的是同一用户下的往返、随机性、抗篡改；
 * 「旧密文失效时当作没有凭据」这条由 `CredentialVault.read` 的
 * 返回 null 约定兜住，属于 Android/桌面共用的编排逻辑。
 *
 * 若在非 Windows 上跑，`Native.load("Crypt32")` 会失败——
 * 这组测试直接跳过而不是失败，因为「不是 Windows」不是代码缺陷。
 */
class DpapiSecretCodecTest {

    private val codec = DpapiSecretCodec()

    private fun assumeWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun roundTripPreservesUnicodeAndLongText() {
        if (!assumeWindows()) return
        val samples = listOf(
            "",
            "a",
            "token-abc123",
            // 中文、希腊字母、emoji：走的是 UTF-8 编解码，不能按字节截断
            "刷新令牌：αβγ-✓-🔑",
            // 超过单个 DPAPI 块长度的内容也要能过（插件凭据偶尔存整段 JWT）
            "x".repeat(4096),
        )
        for (plain in samples) {
            val cipher = codec.encrypt(plain)
            assertEquals(plain, codec.decrypt(cipher), "往返失败：$plain")
        }
    }

    @Test
    fun encryptionIsRandomized() {
        if (!assumeWindows()) return
        // 同一明文两次加密必须得到不同密文。若相同，密文本身就成了
        // 「两段明文是否一样」的指纹，库被读走就能直接比对
        val a = codec.encrypt("same-plaintext")
        val b = codec.encrypt("same-plaintext")
        assertNotEquals(a, b)
        assertEquals("same-plaintext", codec.decrypt(a))
        assertEquals("same-plaintext", codec.decrypt(b))
    }

    @Test
    fun cipherTextIsNotPlainText() {
        if (!assumeWindows()) return
        val plain = "secret-token-should-not-leak"
        val cipher = codec.encrypt(plain)
        assertFalse(cipher.contains(plain))
    }

    @Test
    fun tamperedCipherTextDecryptsToNull() {
        if (!assumeWindows()) return
        val cipher = codec.encrypt("original")
        // 翻掉中间一个字符（避开 Base64 尾部填充位）
        val mid = cipher.length / 2
        val flipped = cipher.substring(0, mid) +
            (if (cipher[mid] == 'A') 'B' else 'A') +
            cipher.substring(mid + 1)
        // 契约要求返回 null 而不是抛异常：宿主不该因为一条坏数据整页打不开
        assertNull(codec.decrypt(flipped))
    }

    @Test
    fun garbageInputDecryptsToNull() {
        if (!assumeWindows()) return
        // 不是合法 Base64
        assertNull(codec.decrypt("not-base64-!!!@@@"))
        // 是合法 Base64 但不是 DPAPI 输出
        assertNull(codec.decrypt("YWJjZGVmZ2g="))
        assertNull(codec.decrypt(""))
    }
}

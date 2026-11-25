package com.lifeboard.shared.plugin.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PluginTls] 与 manifest 指纹解析的测试。
 *
 * ## 这里测的是「拒绝」而不是「连得上」
 *
 * 钉扎的价值全在拒绝一侧：指纹不符时必须连不上，否则机制形同虚设。
 * 「连得上」需要真起一个自签 HTTPS 服务，属于集成测试的范畴，
 * 而拒绝逻辑是纯函数，可以用公开的测试向量稳定覆盖。
 *
 * ## 证书从哪来
 *
 * 用 JDK 自带的 `cacerts` 里取一张真实证书，而不是构造假的 X.509 字节：
 * 后面的 `socketFactory` / `trustManager` 要真的解析它，假字节会在这步炸掉。
 */
class PluginTlsTest {

    /** 取一张 JDK 自带的可信根证书，用作「正常 CA」的样本 */
    private fun sampleCa(): ByteArray {
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        java.io.File(System.getProperty("java.home"), "lib/security/cacerts").inputStream().use {
            store.load(it, "changeit".toCharArray())
        }
        val alias = store.aliases().toList().first { store.isCertificateEntry(it) }
        return store.getCertificate(alias).encoded
    }

    /** 另一张不同的证书，用来验证「不匹配」 */
    private fun otherCa(): ByteArray {
        val store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
        java.io.File(System.getProperty("java.home"), "lib/security/cacerts").inputStream().use {
            store.load(it, "changeit".toCharArray())
        }
        val alias = store.aliases().toList().filter { store.isCertificateEntry(it) }[1]
        return store.getCertificate(alias).encoded
    }

    // ---------- 指纹规范化 ----------

    @Test
    fun `normalizeFingerprint 接受带冒号与空白的写法`() {
        val plain = "a".repeat(64)
        val coloned = plain.chunked(2).joinToString(":")
        assertEquals(plain, PluginTls.normalizeFingerprint(coloned))
        assertEquals(plain, PluginTls.normalizeFingerprint(plain.uppercase()))
        assertEquals(plain, PluginTls.normalizeFingerprint("  $plain  "))
        // 冒号 + 空格混写也是常见形态（openssl 的输出就是这种）
        assertEquals(plain, PluginTls.normalizeFingerprint(plain.chunked(2).joinToString(": ")))
    }

    @Test
    fun `normalizeFingerprint 拒绝长度不对或含非十六进制字符的输入`() {
        assertNull(PluginTls.normalizeFingerprint(""), "空串必须被拒绝")
        assertNull(PluginTls.normalizeFingerprint("a".repeat(63)), "63 位必须被拒绝")
        assertNull(PluginTls.normalizeFingerprint("a".repeat(65)), "65 位必须被拒绝")
        assertNull(PluginTls.normalizeFingerprint("g".repeat(64)), "非十六进制必须被拒绝")
        // 大写会被规范化成小写，因此不能算非法
        assertNotNull(PluginTls.normalizeFingerprint("A".repeat(64)))
    }

    // ---------- 指纹计算与比对 ----------

    @Test
    fun `fingerprintOf 对同一份字节稳定且与规范化结果同形`() {
        val ca = sampleCa()
        val first = PluginTls.fingerprintOf(ca)
        val second = PluginTls.fingerprintOf(ca)
        assertEquals(first, second, "同一份字节两次计算结果不同")
        assertEquals(first, PluginTls.normalizeFingerprint(first), "输出不是规范化的十六进制")
        assertEquals(64, first.length)
    }

    @Test
    fun `fingerprintOf 对不同证书给出不同指纹`() {
        val a = PluginTls.fingerprintOf(sampleCa())
        val b = PluginTls.fingerprintOf(otherCa())
        assertTrue(a != b, "两张不同证书算出了同一指纹")
    }

    @Test
    fun `verify 只认声明的那张证书`() {
        val ca = sampleCa()
        val pinned = PluginTls.PinnedCa(PluginTls.fingerprintOf(ca))
        assertTrue(PluginTls.verify(ca, pinned), "同一张证书应通过")

        val other = PluginTls.PinnedCa(PluginTls.fingerprintOf(otherCa()))
        assertFalse(PluginTls.verify(ca, other), "证书与声明不符时必须拒绝")
    }

    // ---------- 信任锚只含这一张 ----------

    @Test
    fun `trustManager 的信任锚只有声明的这一张`() {
        val ca = sampleCa()
        val accepted = PluginTls.trustManager(ca).acceptedIssuers
        assertEquals(1, accepted.size, "信任锚数量不为 1，可能混进了系统根证书")
        assertTrue(accepted[0].encoded.contentEquals(ca), "信任锚不是传入的那张证书")
    }

    @Test
    fun `socketFactory 能构造出来`() {
        // 只验证不抛异常：真正的握手要有对端才能进行
        assertNotNull(PluginTls.socketFactory(sampleCa()))
    }

    // ---------- manifest 侧 ----------

    @Test
    fun `manifest 解析接受规范指纹并规范化`() {
        val fingerprint = PluginTls.fingerprintOf(sampleCa())
        val coloned = fingerprint.chunked(2).joinToString(":")
        val manifest = JsPluginManifest.parse(
            """{"id":"demo","name":"Demo","pinnedCaSha256":"$coloned"}"""
        )
        assertNotNull(manifest)
        assertEquals(fingerprint, manifest.pinnedCaSha256)
    }

    @Test
    fun `manifest 未声明指纹时为 null`() {
        val manifest = JsPluginManifest.parse("""{"id":"demo","name":"Demo"}""")
        assertNotNull(manifest)
        assertNull(manifest.pinnedCaSha256)
        // 空串按未声明处理，不能当成「声明了一个空的指纹」
        val blank = JsPluginManifest.parse("""{"id":"demo","name":"Demo","pinnedCaSha256":"  "}""")
        assertNotNull(blank)
        assertNull(blank.pinnedCaSha256)
    }

    @Test
    fun `manifest 的 runtime 哨兵被识别且大小写不敏感`() {
        // 分发给多用户时包内不能写死指纹：每台电脑的 dsh-mobile 各有自己的 CA，
        // 只能由运行时（配对链接的 instance=）提供。哨兵必须原样落到 manifest 上，
        // 桥接层靠它区分「写死」与「运行时给」
        listOf("runtime", "Runtime", "RUNTIME").forEach { raw ->
            val manifest = JsPluginManifest.parse(
                """{"id":"demo","name":"Demo","pinnedCaSha256":"$raw"}"""
            )
            assertNotNull(manifest, "哨兵 $raw 本应被接受")
            assertEquals(JsPluginManifest.PINNED_CA_RUNTIME, manifest!!.pinnedCaSha256)
        }
    }

    @Test
    fun `manifest 指纹格式错误时整包拒绝加载`() {
        // 退化成「只用系统证书」是最坏的失败方式：插件以为连上了自签服务，
        // 实际每次握手都在别处失败，排查会绕很远
        listOf("too-short", "z".repeat(64), "not a fingerprint").forEach { bad ->
            assertNull(
                JsPluginManifest.parse("""{"id":"demo","name":"Demo","pinnedCaSha256":"$bad"}"""),
                "非法指纹 $bad 本应让整包加载失败",
            )
        }
    }
}

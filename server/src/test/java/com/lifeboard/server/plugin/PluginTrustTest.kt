package com.lifeboard.server.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信任判定规则。
 *
 * 这组断言直接对应「谁能被加载」，写错的后果是要么放进可疑包，要么把正常更新判成替换，
 * 所以逐条钉死，而不是靠真机点界面验证。
 */
class PluginTrustTest {

    private val pkg = "dev.foo.mihealth"
    private val sigA = "AA11"
    private val sigB = "BB22"

    private fun info(
        versionCode: Long = 1,
        signatures: List<String> = listOf(sigA),
        packageName: String = pkg,
    ) = PluginArchiveInfo(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0",
        signatureHashes = signatures,
    )

    private fun trust(
        trusted: Set<String> = emptySet(),
        versions: Map<String, InstalledVersion> = emptyMap(),
    ) = TrustState(trusted, versions)

    @Test
    fun unsigned_isRejected() {
        assertEquals(TrustVerdict.Unsigned, PluginTrust.evaluate(info().copy(signatureHashes = emptyList()), trust()))
    }

    @Test
    fun signedButNeverTrusted_isUntrusted() {
        val verdict = PluginTrust.evaluate(info(), trust())
        assertTrue(verdict is TrustVerdict.Untrusted)
    }

    @Test
    fun trustedIdentity_isTrusted() {
        val state = trust(trusted = setOf(info().identity.key))
        assertEquals(TrustVerdict.Trusted, PluginTrust.evaluate(info(), state))
    }

    @Test
    fun newVersion_sameSignature_staysTrusted() {
        // 身份不含版本号，因此同一签名发新版不需要用户重新信任
        val state = trust(
            trusted = setOf(info().identity.key),
            versions = mapOf(pkg to InstalledVersion(1, setOf(sigA))),
        )
        assertEquals(TrustVerdict.Trusted, PluginTrust.evaluate(info(versionCode = 2), state))
    }

    @Test
    fun changedSignature_isRejected() {
        val state = trust(
            trusted = setOf(info().identity.key),
            versions = mapOf(pkg to InstalledVersion(1, setOf(sigA))),
        )
        val verdict = PluginTrust.evaluate(info(signatures = listOf(sigB)), state)

        assertTrue(verdict is TrustVerdict.Rejected)
        assertTrue((verdict as TrustVerdict.Rejected).reason.contains("签名"))
    }

    @Test
    fun downgrade_isRejected() {
        val state = trust(
            trusted = setOf(info().identity.key),
            versions = mapOf(pkg to InstalledVersion(5, setOf(sigA))),
        )
        val verdict = PluginTrust.evaluate(info(versionCode = 4), state)

        assertTrue(verdict is TrustVerdict.Rejected)
        assertTrue((verdict as TrustVerdict.Rejected).reason.contains("降级"))
    }

    @Test
    fun sameVersion_reinstall_isAllowed() {
        val state = trust(
            trusted = setOf(info().identity.key),
            versions = mapOf(pkg to InstalledVersion(2, setOf(sigA))),
        )
        assertEquals(TrustVerdict.Trusted, PluginTrust.evaluate(info(versionCode = 2), state))
    }

    @Test
    fun identity_ignoresSignatureOrder() {
        // 同一套签名在 APK 内部的顺序不同，不能被当成两个身份
        val one = info(signatures = listOf(sigA, sigB))
        val two = info(signatures = listOf(sigB, sigA))

        assertEquals(one.identity.key, two.identity.key)
    }

    @Test
    fun identity_differsByPackageName() {
        val a = info(packageName = "dev.foo.one")
        val b = info(packageName = "dev.foo.two")

        assertTrue(a.identity.key != b.identity.key)
    }

    @Test
    fun updateSignature_supersetIsAllowed() {
        // 证书轮换期间新版同时带新旧签名，属于正常情况
        assertNull(PluginTrust.checkUpdateSignature(setOf(sigA), setOf(sigA, sigB)))
    }

    @Test
    fun updateSignature_keepingOldOnly_isAllowed() {
        assertNull(PluginTrust.checkUpdateSignature(setOf(sigA), setOf(sigA)))
    }

    @Test
    fun updateSignature_droppingOld_isRejected() {
        // 只要求有交集的话，攻击者能混进自己的签名，所以必须是包含关系
        assertTrue(PluginTrust.checkUpdateSignature(setOf(sigA), setOf(sigB)) != null)
        assertTrue(PluginTrust.checkUpdateSignature(setOf(sigA, sigB), setOf(sigA)) != null)
    }
}

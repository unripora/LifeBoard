package com.lifeboard.server.plugin

import com.lifeboard.shared.plugin.InstalledVersion
import com.lifeboard.shared.plugin.PluginPackageInfo
import com.lifeboard.shared.plugin.PluginTrust
import com.lifeboard.shared.plugin.TrustState
import com.lifeboard.shared.plugin.TrustVerdict
import org.junit.Assert.assertEquals
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
    ) = FakePackage(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0",
        signatureHashes = signatures,
    )

    /** 测试替身：宿主只依赖 [PluginPackageInfo] 接口，这里给一个最小实现 */
    private data class FakePackage(
        override val packageName: String,
        override val versionCode: Long,
        override val versionName: String,
        override val signatureHashes: List<String>,
    ) : PluginPackageInfo

    private fun trust(
        trusted: Set<String> = emptySet(),
        versions: Map<String, InstalledVersion> = emptyMap(),
    ) = TrustState(trusted, versions)

    /**
     * 把「指纹来自内容哈希」的语义单独拿出来测。
     *
     * 只覆盖 [PluginPackageInfo.isContentAddressed] 这一个差异，
     * 其余字段全部透传——这样两条用例的差别只在指纹语义本身。
     */
    private fun FakePackage.asContentAddressed(): PluginPackageInfo = object : PluginPackageInfo {
        override val packageName get() = this@asContentAddressed.packageName
        override val versionCode get() = this@asContentAddressed.versionCode
        override val versionName get() = this@asContentAddressed.versionName
        override val signatureHashes get() = this@asContentAddressed.signatureHashes
        override val isContentAddressed get() = true
    }

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

    /**
     * 内容寻址的包（JS 插件）改了内容只能要求重新确认，不能判死。
     *
     * 判成 [TrustVerdict.Rejected] 会让用户陷入死路：装不上新版，也删不掉旧包，
     * 只能卸载整个应用。哈希变化对 JS 包来说是日常更新，不是「被替换」。
     */
    @Test
    fun changedContentHash_javascriptPackage_needsReconfirm() {
        val state = TrustState(
            trustedKeys = setOf(info(signatures = listOf(sigA)).identity.key),
            installedVersions = mapOf(pkg to InstalledVersion(1, setOf(sigA))),
        )
        val jsPkg = info(signatures = listOf(sigB)).asContentAddressed()
        val verdict = PluginTrust.evaluate(jsPkg, state)

        assertTrue(verdict is TrustVerdict.Untrusted)
        assertEquals(jsPkg.identity, (verdict as TrustVerdict.Untrusted).identity)
    }

    /** 同一个包，如果指纹来自证书（APK），换签名仍然必须拒绝 */
    @Test
    fun changedCertificate_apkPackage_stillRejected() {
        val state = TrustState(
            trustedKeys = setOf(info(signatures = listOf(sigA)).identity.key),
            installedVersions = mapOf(pkg to InstalledVersion(1, setOf(sigA))),
        )
        val verdict = PluginTrust.evaluate(info(signatures = listOf(sigB)), state)

        assertTrue(verdict is TrustVerdict.Rejected)
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
}

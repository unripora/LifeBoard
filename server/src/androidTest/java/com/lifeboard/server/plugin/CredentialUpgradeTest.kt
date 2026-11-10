package com.lifeboard.server.plugin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「升级不丢登录」的真机验证。
 *
 * 这组断言覆盖用户实际抱怨的场景：更新应用或更新插件后，账号还得重新登一次。
 * 相关行为依赖 Keystore 真实加解密与 SharedPreferences 落盘，只能在仪器测试里验证。
 *
 * 结论边界（不要写成「永不过期」）：
 * - 同签名覆盖升级：凭据可读，不需要重新登录
 * - 换签名/降级：在安装阶段就被拒，旧凭据保持原样
 * - 卸载重装 App、跨设备恢复、Keystore 密钥失效：无法保证解密，需要重新登录
 */
@RunWith(AndroidJUnit4::class)
class CredentialUpgradeTest {

    private lateinit var context: Context

    private val packageName = "com.example.plugin.upgrade"
    private val pluginId = "com.example.plugin.upgrade"
    private val sigA = "AAAA1111"
    private val sigB = "BBBB2222"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        runBlocking { CredentialVault.clearPlugin(context, pluginId) }
        PluginTrustStore.forgetInstalled(context, packageName)
        PluginTrustStore.revokePackage(context, packageName)
    }

    @After
    fun tearDown() {
        runBlocking { CredentialVault.clearPlugin(context, pluginId) }
        PluginTrustStore.forgetInstalled(context, packageName)
        PluginTrustStore.revokePackage(context, packageName)
    }

    private fun archive(versionCode: Long, signatures: List<String>) = PluginArchiveInfo(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        signatureHashes = signatures,
    )

    @Test
    fun sameSignatureUpgrade_keepsCredentialsReadable() = runBlocking {
        // 旧版本登录后落库
        CredentialVault.write(context, pluginId, "service_token", "token-v1")
        PluginTrustStore.recordInstalled(context, archive(versionCode = 1, signatures = listOf(sigA)))

        // 同签名的更高版本：版本裁决应放行
        val verdict = PluginVersionPolicy.evaluate(
            PluginTrustStore.load(context).installedVersions[packageName],
            archive(versionCode = 2, signatures = listOf(sigA)),
        )
        assertTrue(verdict is VersionVerdict.Upgrade)

        // 升级完成后重新写入安装记录，凭据仍在——这就是「更新插件不用重新登录」
        PluginTrustStore.recordInstalled(context, archive(versionCode = 2, signatures = listOf(sigA)))
        assertEquals("token-v1", CredentialVault.read(context, pluginId, "service_token"))
    }

    @Test
    fun changedSignature_isRejectedBeforeOverwrite() = runBlocking {
        CredentialVault.write(context, pluginId, "service_token", "token-v1")
        PluginTrustStore.recordInstalled(context, archive(versionCode = 1, signatures = listOf(sigA)))

        val verdict = PluginVersionPolicy.evaluate(
            PluginTrustStore.load(context).installedVersions[packageName],
            archive(versionCode = 2, signatures = listOf(sigB)),
        )

        assertTrue("换签名必须在覆盖前被拒绝", verdict is VersionVerdict.Rejected)
        // 被拒绝时旧凭据不受影响
        assertEquals("token-v1", CredentialVault.read(context, pluginId, "service_token"))
    }

    @Test
    fun downgrade_isRejectedBeforeOverwrite() = runBlocking {
        CredentialVault.write(context, pluginId, "service_token", "token-v2")
        PluginTrustStore.recordInstalled(context, archive(versionCode = 5, signatures = listOf(sigA)))

        val verdict = PluginVersionPolicy.evaluate(
            PluginTrustStore.load(context).installedVersions[packageName],
            archive(versionCode = 4, signatures = listOf(sigA)),
        )

        assertTrue(verdict is VersionVerdict.Rejected)
        assertEquals("token-v2", CredentialVault.read(context, pluginId, "service_token"))
    }

    @Test
    fun uninstallLikeOperation_doesNotClearCredentials() = runBlocking {
        CredentialVault.write(context, pluginId, "service_token", "token-v1")
        PluginTrustStore.recordInstalled(context, archive(versionCode = 1, signatures = listOf(sigA)))

        // 卸载清的是信任与安装记录，凭据按产品口径保留
        PluginTrustStore.revokePackage(context, packageName)
        PluginTrustStore.forgetInstalled(context, packageName)

        assertNull(PluginTrustStore.load(context).installedVersions[packageName])
        assertEquals("token-v1", CredentialVault.read(context, pluginId, "service_token"))
    }
}

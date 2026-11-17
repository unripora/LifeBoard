package com.lifeboard.server.plugin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.platform.SecretCodec
import com.lifeboard.shared.repository.CredentialVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 端的凭据保管（M4-4.3 拆分后只保留 Keystore 这一半）。
 *
 * ## 编排去哪了
 *
 * 「读库 → 解密」「写入 → 加密 → 落库」「清空」「列槽位」这些动作两端一模一样，
 * 已经搬到 `shared` 的 [CredentialVault]。
 * 这里只剩真正平台相关的一件事：**密钥怎么保管**。
 *
 * ## 加密细节
 *
 * Android Keystore 里一把 AES-256-GCM 密钥，密钥由 TEE 保管不可导出。
 *
 * - `setUserAuthenticationRequired(false)`：后台定时同步不能弹指纹，
 *   与锁屏/指纹无关，用户全程无感知。防的是数据库文件被拷走，不防已解锁设备被操作
 * - GCM 自带完整性校验，密文被篡改会解密失败而不是返回垃圾数据
 * - 每次加密用新的随机 IV（`Cipher.init` 自动生成），IV 拼在密文头部一起入库
 */
class AndroidKeystoreCodec(context: Context) : SecretCodec {

    private val appContext = context.applicationContext

    private val vault: CredentialVault by lazy {
        CredentialVault(DatabaseProviders.pluginStore(appContext), this)
    }

    /** 供调用方使用的编排入口（与原 `CredentialVault` 同名方法一一对应） */
    val credentials: CredentialVault get() = vault

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // IV 拼在密文前面：解密时不需要调用方额外保存它
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String? {
        val blob = runCatching { Base64.decode(cipherText, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (blob.size <= IV_SIZE) return null
        val iv = blob.copyOfRange(0, IV_SIZE)
        val body = blob.copyOfRange(IV_SIZE, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 后台定时同步不能弹指纹验证；加密防的是库文件被拷走
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lifeboard_plugin_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_SIZE = 12
    }
}

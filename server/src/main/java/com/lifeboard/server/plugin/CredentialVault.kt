package com.lifeboard.server.plugin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.PluginCredentialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 插件凭据的宿主托管库。
 *
 * 边界（见 I 阶段 §4.1）：**插件可见自己名下凭据，不可见他人凭据；
 * 存储、加密、清除由宿主统一负责**。
 * 之所以不能做成「插件完全看不到凭据」：续期必须读旧 token、解析新 token，
 * 明文必经插件，拦不住也没必要拦。
 *
 * 加密：Android Keystore 里一把 AES-256-GCM 密钥，密钥由 TEE 保管不可导出。
 * - `setUserAuthenticationRequired(false)`：后台定时同步不能弹指纹，
 *   与锁屏/指纹无关，用户全程无感知。防的是数据库文件被拷走，不防已解锁设备被操作。
 * - GCM 自带完整性校验，密文被篡改会解密失败而不是返回垃圾数据。
 *
 * 日志：**任何路径都不打印凭据内容**，只打 slot 名与成败。
 */
object CredentialVault {

    private const val TAG = "LifeBoardCredential"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lifeboard_plugin_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12

    /**
     * 读取某插件某槽位的明文。
     *
     * 解密失败一律按「没有凭据」返回 null：密钥失效（如用户清了凭据、系统重置）
     * 后旧密文永远解不开，继续当作有效凭据只会让插件拿着垃圾数据去请求。
     */
    suspend fun read(context: Context, pluginId: String, slot: String): String? =
        withContext(Dispatchers.IO) {
            val cipherText = dao(context).get(pluginId, slot) ?: return@withContext null
            runCatching { decrypt(cipherText) }.getOrElse { e ->
                Log.w(TAG, "decrypt failed for $pluginId/$slot, treating as absent", e)
                null
            }
        }

    /** 写入（覆盖）某插件某槽位，落库为密文 */
    suspend fun write(context: Context, pluginId: String, slot: String, value: String) {
        withContext(Dispatchers.IO) {
            val row = PluginCredentialEntity(
                pluginId = pluginId,
                slot = slot,
                cipherText = encrypt(value),
            )
            dao(context).upsert(row)
        }
    }

    /** 清除某插件名下全部凭据，返回删掉的槽位数量 */
    suspend fun clearPlugin(context: Context, pluginId: String): Int = withContext(Dispatchers.IO) {
        val count = dao(context).slots(pluginId).size
        dao(context).deleteByPlugin(pluginId)
        Log.i(TAG, "cleared $count credentials for $pluginId")
        count
    }

    /** 删除单个槽位 */
    suspend fun clearSlot(context: Context, pluginId: String, slot: String) {
        withContext(Dispatchers.IO) { dao(context).deleteSlot(pluginId, slot) }
    }

    /** 某插件名下现有的槽位名，供 UI 告知「清除会影响哪些项」 */
    suspend fun slots(context: Context, pluginId: String): List<String> = withContext(Dispatchers.IO) {
        dao(context).slots(pluginId)
    }

    /** 是否已存过该槽位（只判断存在性，不解密） */
    suspend fun has(context: Context, pluginId: String, slot: String): Boolean =
        withContext(Dispatchers.IO) { dao(context).get(pluginId, slot) != null }

    private fun dao(context: Context) =
        LifeBoardDatabase.get(context.applicationContext).pluginCredentialDao()

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        if (blob.size <= IV_SIZE) return null
        val iv = blob.copyOfRange(0, IV_SIZE)
        val cipherText = blob.copyOfRange(IV_SIZE, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
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
}

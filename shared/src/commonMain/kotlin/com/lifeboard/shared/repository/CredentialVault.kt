package com.lifeboard.shared.repository

import com.lifeboard.shared.platform.SecretCodec
import com.lifeboard.shared.repository.model.PluginCredentialRecord

/**
 * 插件凭据的宿主托管库（M4-4.3 从 Android 的 `CredentialVault` 抽出编排部分）。
 *
 * 边界（见 I 阶段 §4.1）：**插件可见自己名下凭据，不可见他人凭据；
 * 存储、加密、清除由宿主统一负责**。
 * 之所以不能做成「插件完全看不到凭据」：续期必须读旧 token、解析新 token，
 * 明文必经插件，拦不住也没必要拦。
 *
 * ## 与 Android 原 `CredentialVault` 的关系
 *
 * 那个类把「编排」与「Keystore 加解密」写在一起，导致桌面端想复用编排
 * 就得连 `android.security.keystore` 一起搬。拆开以后：
 *
 * - 编排（本类）：读库 → 解密、写入 → 加密 → 落库、清空、列槽位。**两端同一份**
 * - 加解密（[SecretCodec]）：Android 用 Keystore，Windows 用 DPAPI
 *
 * ## 日志纪律
 *
 * **任何路径都不打印凭据内容**，只打槽位名与成败。这条在两端都成立，
 * 所以它写在这里而不是各自的实现里。
 *
 * @param store 凭据的落库入口（密文）
 * @param codec 加解密实现；解密失败按「没有凭据」处理
 */
class CredentialVault(
    private val store: PluginStore,
    private val codec: SecretCodec,
) {

    /**
     * 读取某插件某槽位的明文。
     *
     * 解密失败一律按「没有凭据」返回 null：密钥失效（如用户清了凭据、系统重置、
     * 换了 Windows 登录账户）后旧密文永远解不开，继续当作有效凭据只会让插件
     * 拿着垃圾数据去请求。
     */
    suspend fun read(pluginId: String, slot: String): String? {
        val cipherText = store.getCredential(pluginId, slot) ?: return null
        return codec.decrypt(cipherText)
    }

    /** 写入（覆盖）某插件某槽位，落库为密文 */
    suspend fun write(pluginId: String, slot: String, value: String) {
        store.saveCredential(
            PluginCredentialRecord(
                pluginId = pluginId,
                slot = slot,
                cipherText = codec.encrypt(value),
            ),
        )
    }

    /**
     * 清除某插件名下全部凭据，返回删掉的槽位数量。
     *
     * 直接取 DELETE 的影响行数，省掉先查一次 slots 只为数个数。
     */
    suspend fun clearPlugin(pluginId: String): Int = store.deleteCredentialsByPlugin(pluginId)

    /** 删除单个槽位 */
    suspend fun clearSlot(pluginId: String, slot: String) {
        store.deleteCredentialSlot(pluginId, slot)
    }

    /** 某插件名下现有的槽位名，供 UI 告知「清除会影响哪些项」 */
    suspend fun slots(pluginId: String): List<String> = store.getCredentialSlots(pluginId)

    /**
     * 是否已存过该槽位。
     *
     * **只判断存在性，不解密**：解密失败的旧密文仍算「有」，
     * 因为「有但解不开」与「从来没有」在界面上要给出不同的提示
     * （前者应引导用户重新登录，后者只是还没配）。
     */
    suspend fun has(pluginId: String, slot: String): Boolean =
        store.getCredential(pluginId, slot) != null
}

package com.lifeboard.shared.platform

/**
 * 明文 ↔ 密文的加解密端口（M4-4.3）。
 *
 * ## 为什么只抽「加解密」这一步
 *
 * 凭据的**编排**（读库 → 解密 → 给调用方；写入 → 加密 → 落库）两端完全一样：
 * 都在 `PluginStore` 上做，都以 `pluginId` + `slot` 定位，解密失败都按「没有凭据」处理。
 * 真正平台相关的只有一件事——**用什么保护密钥**：
 *
 * - Android：Keystore 里一把 AES-256-GCM 密钥，由 TEE 保管不可导出
 * - Windows：DPAPI 的 `CryptProtectData`，密钥由操作系统按登录用户派生
 *
 * 所以这里只留 [encrypt] / [decrypt] 两个方法。把这个边界画窄是有意的：
 * 若把「凭据增删查」也放进契约，两端就得各写一遍 slot 语义，
 * 而那份语义（清空要不要返回行数、解密失败算不算存在）恰恰是最容易分叉的部分。
 *
 * ## 契约要求
 *
 * - 每次 [encrypt] 必须使用**新的随机 IV/nonce**。同一明文两次加密必须得到不同密文，
 *   否则密文本身成了明文是否相同的指纹
 * - 密文是**自包含**的：解密所需的 IV 必须编在 [encrypt] 的返回值里，
 *   调用方不负责保存 IV
 * - [decrypt] 遇到「密钥不可用」「密文被篡改」一律返回 null，**不抛异常**。
 *   宿主不该因为某条凭据解不开就整个页面打不开——那条凭据就当它不存在
 */
interface SecretCodec {

    /** 加密一段明文，返回自包含 IV 的密文字符串（Base64 等可入库的形式） */
    fun encrypt(plain: String): String

    /** 解密；密钥不可用、密文损坏或被篡改时返回 null */
    fun decrypt(cipherText: String): String?

    companion object {
        /**
         * 不加密的直通实现，**仅供测试与不出凭据的场景使用**。
         *
         * 名字里带 `Insecure` 是故意的：在装配处 grep 到它就说明有人在用明文落库。
         * 生产装配不该出现这个值。
         */
        val Insecure: SecretCodec = object : SecretCodec {
            override fun encrypt(plain: String): String = plain
            override fun decrypt(cipherText: String): String = cipherText
        }
    }
}

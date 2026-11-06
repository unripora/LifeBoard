package com.lifeboard.plugin.mihealth

import java.security.MessageDigest

/**
 * 小米健康云端接口的登录凭据。
 *
 * 三件套缺一不可：
 * - [ssecurity]：每次登录会话都会变，与 serviceToken 配套，用于派生 sessionKey
 * - [serviceToken]：`sts-hlth.io.mi.com` 域下的长 token
 * - [cUserId]：用户 ID，作为 cookie 发送
 *
 * [passToken] 用于免密续期：有效期约 1~2 个月，过期后必须重新走账号密码登录。
 *
 * 注意：请求时 **不能** 带 `userId` cookie，带了服务端返回 401
 * （参考实现里明确踩过这个坑）。
 */
data class MiHealthCredentials(
    val ssecurity: String,
    val serviceToken: String,
    val cUserId: String,
    val passToken: String = "",
    /**
     * 登录用的账号（手机号/邮箱/小米 ID）。
     *
     * 只用于界面显示「当前登录的是谁」——多账号用户需要一眼确认自己
     * 登的是哪个号。它不参与任何请求签名，缺失也不影响取数。
     */
    val account: String = "",
) {
    /**
     * 是否可用于取数。
     *
     * [cUserId] 只作为可选 cookie 发送，缺了不影响接口调用（服务端靠
     * serviceToken + ssecurity 认人）。早期版本把它也算成必需项，
     * 结果 cookie 名大小写对不上时就被误判成「未登录」，
     * 用户明明登录成功、界面却一直显示要去登录。
     */
    fun isValid(): Boolean = ssecurity.isNotBlank() && serviceToken.isNotBlank()

    /**
     * 落盘用；不含任何加密，调用方需保证存储安全。
     *
     * 用 `\n` 分隔而不是 `|`：token 可能含任意可打印字符，
     * 用 `|` 就得做转义，转义做错会让凭据悄悄变样、表现为登录失效。
     */
    fun serialize(): String =
        listOf(ssecurity, serviceToken, cUserId, passToken, account).joinToString("\n")

    companion object {
        /**
         * 解析落盘内容。
         *
         * 兼容早期的 `|` 分隔格式：老版本已经用 `|` 写过凭据，
         * 直接换分隔符会让所有已登录用户的凭据失效、被迫重新登录。
         */
        fun parse(raw: String): MiHealthCredentials? {
            val parts = if (raw.contains('\n')) raw.split("\n") else raw.split("|")
            if (parts.size < 2) return null
            return MiHealthCredentials(
                ssecurity = parts[0],
                serviceToken = parts[1],
                cUserId = parts.getOrElse(2) { "" },
                passToken = parts.getOrElse(3) { "" },
                // 第 5 段是后加的账号名，老凭据没有这一段，取空即可
                account = parts.getOrElse(4) { "" },
            )
        }

        /** 密码哈希：**单次** MD5 并转大写。双重 MD5 会稳定报 70016 */
        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(password.toByteArray())
            return digest.joinToString("") { "%02X".format(it) }
        }
    }
}

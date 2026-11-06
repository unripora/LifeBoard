package com.lifeboard.plugin.api

/**
 * 插件当前登录态，供插件 Tab 与面板展示。
 *
 * 之所以要有 [Expired] 这个中间态：小米健康的 passToken 过期但可自动续期，
 * 此时「已登录」和「未登录」都不准确——用布尔字段表达不出来，
 * 用户会看到「已登录」却拉不到数，或看到「未登录」又被自动恢复，两种情况都难排障。
 */
sealed class AuthState {

    /** 未登录，或凭据已被清除 */
    data object LoggedOut : AuthState()

    /**
     * 已登录且凭据可用。
     *
     * @property accountName 账号展示名（昵称/手机号尾号等），拿不到就留空
     */
    data class LoggedIn(val accountName: String? = null) : AuthState()

    /**
     * 凭据存在但已过期，插件会在下次拉数时尝试自动续期。
     *
     * @property message 过期原因，展示给用户，不得含凭据内容
     */
    data class Expired(val message: String? = null) : AuthState()
}

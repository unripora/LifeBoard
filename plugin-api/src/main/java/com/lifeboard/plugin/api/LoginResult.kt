package com.lifeboard.plugin.api

/**
 * 登录结果。
 *
 * 只区分「登录成功」和「登录失败并给出原因」——登录过程由插件自己走（可能要跳 WebView、
 * 等验证码、处理二次验证），宿主不参与流程，只知道最终结果。
 * 需要用户在 UI 上配合的中间态由插件自己的界面表达，不塞进契约。
 */
sealed class LoginResult {

    /** 登录成功，凭据已写入 [PluginContext.credentials] */
    data object Success : LoginResult()

    /**
     * 登录失败。
     *
     * [message] 会**直接展示给用户**，用于排障（如「账号或密码错误」「服务端返回 502」），
     * 因此插件不得把凭据内容拼进这里。
     */
    data class Failure(val message: String) : LoginResult()
}

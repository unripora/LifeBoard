package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PluginSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 组装详情页顶部的通用信息。
 *
 * 健康度读宿主存的健康表而不是现调插件 `health()`：后者会真的发网络请求，
 * 用户点进来只是想看细节，不是要触发一次采集。
 */
internal suspend fun buildDetailInfo(
    deps: PlatformDependencies,
    summary: PluginSummary,
): PluginDetailInfo {
    // 未信任时不去问健康度：问了也是走一遍未受信代码
    val health = if (summary.trusted) {
        runCatching {
            withContext(Dispatchers.IO) { deps.pluginInvoker.health(summary.pluginId) }
        }.onFailure {
            AppLogger.warn("读取插件 ${summary.pluginId} 健康度失败：${it.message}")
        }.getOrNull()
    } else {
        null
    }

    val healthy = when (health) {
        // 读不到就不下结论：null 在界面上是「还没采集过」，不是「异常」
        null -> null
        is PluginHealth.Healthy -> true
        is PluginHealth.Degraded, is PluginHealth.Broken -> false
    }
    val healthMessage = when (health) {
        is PluginHealth.Degraded -> health.message
        is PluginHealth.Broken -> health.message
        else -> ""
    }

    // 未信任时不去问登录态：问了也是 LoggedOut，会在界面上留一个点了没用的提示。
    // asked 单独记一下：只有「问了但没问出来」才该显示待确认，
    // 「没去问」（未信任/不需要登录）是另一回事
    val asked = summary.needsLogin && summary.trusted
    val auth = if (asked) {
        runCatching { deps.pluginInvoker.authState(summary.pluginId) }
            .onFailure {
                AppLogger.warn("读取插件 ${summary.pluginId} 登录态失败：${it.message}")
            }
            .getOrNull()
    } else {
        null
    }

    return PluginDetailInfo(
        pluginId = summary.pluginId,
        displayName = summary.displayName,
        packageName = summary.packageName,
        versionName = summary.versionName,
        metricCount = summary.metricCount,
        trusted = summary.trusted,
        healthy = healthy,
        healthMessage = healthMessage,
        signatureHashes = summary.signatureHashes.sorted(),
        needsLogin = summary.needsLogin,
        loginText = authText(auth),
        // 「登录」只在插件确实说未登录/过期时给；问不出来时不给
        needsLoginAction = summary.needsLogin && (
            auth is AuthState.LoggedOut || auth is AuthState.Expired
            ),
        authStateUnknown = asked && auth == null,
    )
}

private fun authText(auth: AuthState?): String = when (auth) {
    null -> ""
    is AuthState.LoggedIn -> auth.accountName?.let { "已登录：$it" } ?: "已登录"
    is AuthState.Expired -> auth.message ?: "登录已过期"
    AuthState.LoggedOut -> "未登录"
}

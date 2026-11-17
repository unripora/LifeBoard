package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.server.platform.AndroidSettingsStore

/**
 * 信任存储的 Android 入口。
 *
 * 读写逻辑已下沉到 [:shared 的 PluginTrustStore][com.lifeboard.shared.plugin.PluginTrustStore]，
 * 这里只负责把 `Context` 换成平台无关的 `SettingsStore`，
 * 让调用方（大量既有的 `PluginTrustStore.xxx(context, ...)` 调用点）无需改动。
 *
 * 桌面端使用的是同一个共享实现，只是注入了文件版 `SettingsStore`。
 */
object PluginTrustStore {

    private val stores = java.util.concurrent.ConcurrentHashMap<Context, com.lifeboard.shared.plugin.PluginTrustStore>()

    private fun delegate(context: Context): com.lifeboard.shared.plugin.PluginTrustStore {
        val app = context.applicationContext
        return stores.getOrPut(app) {
            com.lifeboard.shared.plugin.PluginTrustStore(AndroidSettingsStore(app, "plugin_trust"))
        }
    }

    fun load(context: Context): TrustState = delegate(context).load()

    fun trust(context: Context, identity: PluginIdentity) = delegate(context).trust(identity)

    fun revoke(context: Context, identity: PluginIdentity) = delegate(context).revoke(identity)

    fun revokePackage(context: Context, packageName: String) =
        delegate(context).revokePackage(packageName)

    fun recordInstalled(context: Context, info: PluginPackageInfo) =
        delegate(context).recordInstalled(info)

    fun forgetInstalled(context: Context, packageName: String) =
        delegate(context).forgetInstalled(packageName)

    fun revokeAll(context: Context) = delegate(context).revokeAll()
}

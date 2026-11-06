package com.lifeboard.server.plugin

import android.content.Context
import android.util.Log
import com.lifeboard.server.source.SourceProviderRegistry

/**
 * 插件系统的对外入口：扫描、注册、信任与撤销。
 *
 * UI 只跟这个类打交道，不直接碰 [PluginLoader] 和 [PluginInstaller]，
 * 以免「加载」和「注册进调度」两件事被分开调用而不同步——
 * 只加载不注册，插件在列表里看得见却永远不采数。
 *
 * ## 刷新时机
 *
 * [refresh] 由三处触发，都收敛在这里，不依赖 UI：
 * - 应用启动
 * - 用户安装新 `.ext`
 * - 用户撤销信任
 *
 * 这样 M3 结束时加载链路已经自洽，M4 只是给它加界面。
 */
object PluginManager {

    private const val TAG = "LifeBoardPluginManager"

    /**
     * 重新扫描并同步注册表。
     *
     * 注册表替换是**整体一次性赋值**（见 `SourceProviderRegistry.setPluginProviders`），
     * 因此正在采集的旧实例能跑完，不会读到半套插件。
     */
    fun refresh(context: Context): List<LoadedPlugin> {
        val appContext = context.applicationContext
        val plugins = PluginLoader.scan(appContext)
        register(appContext, plugins)
        return plugins
    }

    /**
     * 信任一个待信任插件，并立即生效。
     *
     * 用户的意图是「现在就能用」，所以这里连带重新扫描，而不是等下次启动。
     */
    fun trust(context: Context, identity: PluginIdentity) {
        val appContext = context.applicationContext
        PluginTrustStore.trust(appContext, identity)
        refresh(appContext)
    }

    /**
     * 撤销信任。
     *
     * 只标记该插件不可调度，**不重建整个列表**：重建会连带换掉其他正在采集的插件实例。
     * 撤销的目的就是「别再用了」，让正在跑的那次自然结束即可。
     */
    fun revokeTrust(context: Context, pluginId: String) {
        val appContext = context.applicationContext
        val plugin = PluginLoader.find(pluginId) ?: return
        PluginTrustStore.revokePackage(appContext, plugin.archive.packageName)
        PluginLoader.markUntrusted(pluginId)
        // 注册表里该插件的 Provider 仍在，但 isSchedulable 会返回 false，
        // PluginDataSource 也会因 trusted=false 返回空数据与「已撤销」状态
    }

    /** 当前已加载的插件 */
    fun loaded(): List<LoadedPlugin> = PluginLoader.loadedPlugins()

    /** 加载失败的插件及原因，供 UI 展示 */
    fun broken(): List<PluginLoader.BrokenPlugin> = PluginLoader.brokenPlugins()

    /**
     * 把插件指标注册成一个主动拉取源。
     *
     * 一个指标一个 Provider：插件内各量纲独立成败，
     * 与内置源里小米健康的步数与睡眠各自一条健康记录一致。
     *
     * 空指标的插件不注册：它可能只提供登录管理，注册了也采不到数。
     */
    private fun register(context: Context, plugins: List<LoadedPlugin>) {
        val providers = plugins.flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                SourceProviderRegistry.Provider(
                    source = com.lifeboard.plugin.api.SourceRef.Plugin(
                        pluginId = plugin.pluginId,
                        metricKey = spec.metricKey,
                        displayName = "${plugin.plugin.displayName}·${spec.displayName}",
                        bucket = spec.bucket,
                    ),
                    // 工厂：每次采集新建，避免插件实例状态把不同日期的数据串起来
                    factory = { ctx -> PluginDataSource(plugin, spec, ctx) },
                )
            }
        }
        SourceProviderRegistry.setPluginProviders(providers)
        Log.i(TAG, "已注册插件源 ${providers.size} 个，来自 ${plugins.size} 个插件")
    }
}

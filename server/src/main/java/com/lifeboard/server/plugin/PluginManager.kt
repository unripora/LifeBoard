package com.lifeboard.server.plugin

import android.content.Context

/**
 * 插件系统的对外入口：扫描、信任与撤销。
 *
 * UI 只跟这个类打交道，不直接碰 [PluginLoader] 和 [PluginInstaller]。
 *
 * ## 为什么这里几乎全是转发
 *
 * 「加载插件」和「把插件注册进调度」必须同时发生，否则插件在列表里看得见却永远不采数。
 * 这个约束的落实放在 [PluginLoader.scan] 内部（在同一个临界区里完成二者），
 * 因此本类不再承担编排职责，只负责：
 * - 把 `Context` 收敛成 `applicationContext`，避免调用方传入 Activity 造成泄漏
 * - 把「信任后立即生效」「撤销后停止调度」这两个用户动作翻译成存储 + 扫描
 *
 * 如果将来出现新的跨步骤编排需求（如安装 → 校验 → 注册），仍应加在这里。
 */
object PluginManager {

    /**
     * 重新扫描并同步注册表。
     *
     * 触发点：应用启动、用户安装新 `.ext`、用户撤销信任。
     */
    fun refresh(context: Context): List<LoadedPlugin> =
        PluginLoader.scan(context.applicationContext)

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
}

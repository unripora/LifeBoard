package com.lifeboard.server.plugin.js

import android.content.Context
import com.lifeboard.server.plugin.LoadedPlugin
import com.lifeboard.server.plugin.PluginTrustStore
import com.lifeboard.server.platform.AndroidSettingsStore
import com.lifeboard.shared.plugin.fs.AndroidPluginFileSystem
import com.lifeboard.shared.plugin.js.BrokenJsPlugin
import com.lifeboard.shared.plugin.js.JsPluginLoader as SharedJsPluginLoader
import com.lifeboard.shared.plugin.js.PendingJsPlugin
import java.io.File

/**
 * `.jsplugin` 加载器的 Android 入口。
 *
 * ## 为什么这里只剩一层转发
 *
 * 插件的扫描、验签、信任判定、QuickJS 运行时装配、桥接注册全部下沉到了
 * `:shared`，两端共用同一份实现。原因是这套逻辑与平台无关：它只依赖
 * 「插件根目录」「键值设置」两个抽象，这两个已分别由
 * [AndroidPluginFileSystem] 与 `SettingsStore` 提供。
 *
 * 留一层薄封装而不是让调用方直接用共享类，理由有两个：
 * 1. 既有调用点都写成 `JsPluginLoader.scan(context)`，改成共享类要动几十处
 * 2. 共享层交出的是 [com.lifeboard.shared.plugin.js.JsLoadedPlugin]，
 *    Android 侧的 `LoadedPlugin` 还带「是否仍信任」这类宿主簿记，
 *    需要在这一层组装
 */
internal object JsPluginLoader {

    /** 包文件后缀 */
    const val SUFFIX = ".jsplugin"

    /**
     * 本机能否运行 JS 插件。
     *
     * 纯查询：只看 native 库能否加载，不解压、不扫描、不建运行时。
     * 供界面在渲染插件页之前问一句，缺库时整体降级，
     * 而不是等到扫描时才在后台线程炸出 `UnsatisfiedLinkError`。
     */
    fun engineAvailable(): Boolean = com.lifeboard.jsengine.jsEngineAvailable()

    private val loaders = java.util.concurrent.ConcurrentHashMap<Context, SharedJsPluginLoader>()

    /**
     * 是否已经完整扫描过一次。
     *
     * 后台聚合可能在应用初始化扫描完成之前就被 WorkManager 拉起，
     * 此时注册表为空，采集会静默跑成「一个插件都没有」。
     * 采集前用 [awaitFirstScan] 等一次完整扫描，堵住这个窗口。
     *
     * 这个时序问题与插件格式无关，所以它留在这里而不是上移到调用方。
     */
    @Volatile
    private var scanned = false

    /**
     * 确保至少完成过一次完整扫描。
     *
     * 已经扫过就直接返回，不重复解包：周期任务每次执行都会调用它，
     * 每次都全量扫描既慢又会让正在使用的插件实例被替换。
     *
     * 用 `synchronized` 而不是协程锁：这里的临界区是纯内存判断 + 一次
     * 同步扫描，没有挂起点，锁持有时间就是扫描本身的时间。
     */
    fun awaitFirstScan(context: Context) {
        if (scanned) return
        synchronized(this) {
            // 双检：等锁期间可能已被另一个线程扫完
            if (scanned) return
            runCatching { scan(context.applicationContext) }
                .onFailure { com.lifeboard.shared.log.AppLogger.error("首次插件扫描失败", it) }
            scanned = true
        }
    }

    private fun loader(context: Context): SharedJsPluginLoader {
        val app = context.applicationContext
        return loaders.getOrPut(app) {
            SharedJsPluginLoader(
                fileSystem = AndroidPluginFileSystem(app.filesDir),
                trustStore = com.lifeboard.shared.plugin.PluginTrustStore(
                    AndroidSettingsStore(app, "plugin_trust"),
                ),
            )
        }
    }

    /** 插件包与解压目录的根位置 */
    fun pluginsDir(context: Context): File = File(context.filesDir, "js-plugins").apply { mkdirs() }

    fun loadedPlugins(context: Context): List<LoadedPlugin> =
        loader(context).loadedPlugins().map { it.toLoadedPlugin() }

    fun brokenPlugins(context: Context): List<BrokenJsPlugin> =
        loader(context).brokenPlugins()

    fun pendingPlugins(context: Context): List<PendingJsPlugin> =
        loader(context).pendingPlugins()

    fun find(context: Context, pluginId: String): LoadedPlugin? =
        loader(context).find(pluginId)?.toLoadedPlugin()

    fun remove(context: Context, archiveName: String): Boolean =
        loader(context).remove(archiveName)

    fun scan(context: Context): List<LoadedPlugin> =
        loader(context).scan().map { it.toLoadedPlugin() }

    fun closeAll(context: Context) {
        loaders[context.applicationContext]?.closeAll()
    }

    /**
     * 把某个插件标记为不再可信。
     *
     * 只在内存里翻转标记，不重建运行时：撤销信任发生在 UI 线程，
     * 重建列表会把所有插件实例一起换掉，正在采集的会被打断。
     * 调度前检查 `LoadedPlugin.trusted` 就够了。
     */
    fun markUntrusted(context: Context, pluginId: String) {
        markUntrustedFlags(pluginId)
    }

    /**
     * 撤销信任后的内存标记表。
     *
     * 共享层不认识 `LoadedPlugin.trusted`（那是宿主簿记），所以由这一层记住
     * 哪些插件已被撤销，下次 [scan] 重建 `LoadedPlugin` 时据此恢复标记。
     */
    private val untrustedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun markUntrustedFlags(pluginId: String) {
        untrustedIds += pluginId
    }

    private fun com.lifeboard.shared.plugin.js.JsLoadedPlugin.toLoadedPlugin(): LoadedPlugin =
        LoadedPlugin(
            archive = packageInfo,
            archiveName = archiveName,
            plugin = plugin,
        ).apply {
            // 撤销过信任的插件在下次扫描重建后仍要显示为不可信，
            // 否则用户撤销后刷一下列表它就「恢复」了
            if (plugin.id in untrustedIds) trusted = false
        }

    /** 重新信任时清掉撤销标记，避免下次扫描又把它置回不可信 */
    fun markTrusted(pluginId: String) {
        untrustedIds -= pluginId
    }
}

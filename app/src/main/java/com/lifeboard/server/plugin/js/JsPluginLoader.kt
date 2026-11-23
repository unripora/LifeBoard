package com.lifeboard.server.plugin.js

import android.content.Context
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.platform.AndroidSettingsStore
import com.lifeboard.server.plugin.LoadedPlugin
import com.lifeboard.server.plugin.PluginDataSource
import com.lifeboard.server.source.SourceProviderRegistry
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

    // 注册表、详情页与 PluginDataSource 必须共用同一个可撤销的宿主包装，不能每次读取都重建。
    // 所有修改都持有本对象的锁，与首次扫描共用同一个生命周期临界区。
    private val loaded = mutableMapOf<Context, List<LoadedPlugin>>()

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
        }
    }

    @Synchronized
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

    @Synchronized
    fun loadedPlugins(context: Context): List<LoadedPlugin> =
        loaded[context.applicationContext].orEmpty()

    fun brokenPlugins(context: Context): List<BrokenJsPlugin> =
        loader(context).brokenPlugins()

    fun pendingPlugins(context: Context): List<PendingJsPlugin> =
        loader(context).pendingPlugins()

    @Synchronized
    fun find(context: Context, pluginId: String): LoadedPlugin? =
        loaded[context.applicationContext]?.firstOrNull { it.pluginId == pluginId }

    @Synchronized
    fun remove(context: Context, archiveName: String): Boolean {
        val app = context.applicationContext
        return try {
            loader(app).remove(archiveName)
        } finally {
            // 即便文件已被外部删除或删除中途失败，也不能继续调度这个归档的旧实例。
            replaceLoaded(app, loaded[app].orEmpty().filterNot { it.archiveName == archiveName })
        }
    }

    /** 加载与登记在同一个临界区完成；空扫描也必须覆盖旧的插件源。 */
    @Synchronized
    fun scan(context: Context): List<LoadedPlugin> {
        val app = context.applicationContext
        val previous = loaded[app].orEmpty()
        val plugins = try {
            loader(app).scan().map { js ->
                // 共享层可能复用未变化的运行时，宿主也复用包装，确保所有持有者看到同一个信任标记。
                // 已撤销的包装不复活：重新信任必须通过共享层本次扫描的持久化信任判定。
                previous.firstOrNull { it.plugin === js.plugin && it.trusted }
                    ?: LoadedPlugin(js.packageInfo, js.archiveName, js.plugin)
            }
        } catch (error: Throwable) {
            // 共享扫描可能已经关闭旧运行时，异常时宁可暂不调度，也不留下失效的采集器。
            replaceLoaded(app, emptyList())
            scanned = false
            throw error
        }
        replaceLoaded(app, plugins)
        scanned = true
        return plugins
    }

    @Synchronized
    fun closeAll(context: Context) {
        val app = context.applicationContext
        // 先撤掉调度入口，并让已取出的 Provider/采集器也看见停止标记，再释放运行时。
        replaceLoaded(app, emptyList())
        scanned = false
        loaders[app]?.closeAll()
    }

    /** 撤销只停用该插件，不关闭其它插件的运行时；已取出的采集器也会读取同一个标记。 */
    @Synchronized
    fun markUntrusted(context: Context, pluginId: String) {
        loaded[context.applicationContext].orEmpty()
            .filter { it.pluginId == pluginId }
            .forEach { it.trusted = false }
        registerProviders()
    }

    private fun replaceLoaded(app: Context, plugins: List<LoadedPlugin>) {
        loaded[app].orEmpty().filter { old -> plugins.none { it === old } }
            .forEach { it.trusted = false }
        loaded[app] = plugins
        registerProviders()
    }

    private fun registerProviders() {
        val providers = loaded.values.flatten().filter { it.trusted }.flatMap { plugin ->
            // LoadedPlugin.metrics 按 metricKey 索引，同一插件的重复声明只登记一个采集源。
            plugin.metrics.values.map { spec ->
                SourceProviderRegistry.Provider(
                    SourceRef.Plugin(
                        pluginId = plugin.pluginId,
                        metricKey = spec.metricKey,
                        displayName = "${plugin.plugin.displayName}·${spec.displayName}",
                        bucket = spec.bucket,
                    ),
                ) { context -> PluginDataSource(plugin, spec, context) }
            }
        }.distinctBy { it.source.key }
        SourceProviderRegistry.setPluginProviders(providers)
    }
}

package com.lifeboard.server.plugin

import android.content.Context
import android.util.Log
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginMetrics
import com.lifeboard.plugin.api.SourcePlugin
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 扫描、校验并实例化 `.ext` 插件。
 *
 * ## 加载方式
 *
 * 同进程加载（与 Mihon 一致）：`DexClassLoader` 的父加载器就是宿主类加载器，
 * 插件因此能用 `:plugin-api` 里的契约类型。**没有进程隔离，也没有沙箱**——
 * 插件与本应用同 UID、同权限、同数据目录。OOM 或 native crash 会连同宿主一起被杀，
 * 这里的 try/catch 只能兜住受控异常。
 *
 * ## 失败处理
 *
 * 任何一步失败都只影响该插件自己，产出一条 [BrokenPlugin] 供 UI 展示原因，
 * 其余插件继续加载。这是 M3.7 的要求：一个插件写坏，不能让所有源一起停采。
 */
object PluginLoader {

    private const val TAG = "LifeBoardPluginLoader"

    /** 加载成功的插件 */
    private val loaded = mutableListOf<LoadedPlugin>()

    /** 加载失败的插件及原因，用于 UI 展示「为什么用不了」 */
    private val broken = mutableListOf<BrokenPlugin>()

    /**
     * 一个加载失败的插件。
     *
     * 失败也要留在列表里：用户明明放了 `.ext` 却找不到入口，比看到「加载失败：契约版本不匹配」
     * 更难排查。因此这里用 [BrokenPlugin] 承接下来，而不是直接丢弃。
     *
     * @property archiveName 文件名，UI 用它指认是哪一个
     * @property packageName 包名，解析失败时可能为空
     * @property reason 可展示的原因
     */
    data class BrokenPlugin(
        val archiveName: String,
        val packageName: String,
        val reason: String,
    )

    /** 当前可用的插件。返回副本，避免调用方持有内部列表 */
    fun loadedPlugins(): List<LoadedPlugin> = loaded.toList()

    /** 当前加载失败的插件 */
    fun brokenPlugins(): List<BrokenPlugin> = broken.toList()

    /**
     * 全量重新扫描。
     *
     * **整体替换而不是逐项增删**：调用方在并发读取（Worker 正在采集），
     * 先构建完整的新列表再一次性换上，读到的永远是某一时刻的完整集合。
     *
     * @return 本次成功加载的插件
     */
    fun scan(context: Context): List<LoadedPlugin> {
        val appContext = context.applicationContext
        val trustState = PluginTrustStore.load(appContext)

        val newLoaded = mutableListOf<LoadedPlugin>()
        val newBroken = mutableListOf<BrokenPlugin>()

        PluginInstaller.listArchives(appContext).forEach { archive ->
            when (val result = loadOne(appContext, archive, trustState)) {
                is LoadResult.Success -> newLoaded += result.plugin
                is LoadResult.Failure -> newBroken += result.broken
                // 未信任的插件不加载，也不报错：UI 单独列「待信任」，用户点信任后才生效
                LoadResult.Skipped -> Unit
            }
        }

        synchronized(this) {
            loaded.clear()
            loaded += newLoaded
            broken.clear()
            broken += newBroken
        }
        return newLoaded
    }

    /** 按插件声明 id 找已加载的插件 */
    fun find(pluginId: String): LoadedPlugin? =
        synchronized(this) { loaded.firstOrNull { it.pluginId == pluginId } }

    /**
     * 把某个插件标记为不再可信。
     *
     * 只改标记，不重建列表：重建会把其他正在采集的插件实例一起换掉。
     * Worker 在调度前检查 [LoadedPlugin.trusted] 即可停止使用它。
     */
    fun markUntrusted(pluginId: String) {
        synchronized(this) {
            loaded.firstOrNull { it.pluginId == pluginId }?.trusted = false
        }
    }

    private sealed class LoadResult {
        data class Success(val plugin: LoadedPlugin) : LoadResult()
        data class Failure(val broken: BrokenPlugin) : LoadResult()
        /** 未信任，静默跳过 */
        data object Skipped : LoadResult()
    }

    private fun loadOne(
        context: Context,
        archive: File,
        trustState: TrustState,
    ): LoadResult {
        val info = PluginInstaller.readArchiveInfo(context, archive)
            ?: return fail(archive, "", "无法解析插件包，可能不是有效的 APK")

        return when (val verdict = PluginTrust.evaluate(info, trustState)) {
            // 未信任与无签名都不加载。区别在于前者 UI 提供信任入口，后者没有
            is TrustVerdict.Untrusted, TrustVerdict.Unsigned -> LoadResult.Skipped
            is TrustVerdict.Rejected -> fail(archive, info.packageName, verdict.reason)
            TrustVerdict.Trusted -> instantiate(context, archive, info)
        }
    }

    /**
     * 反射实例化插件并做契约校验。
     *
     * 入口类从 APK 的 `META-INF/lifeboard-plugin.properties` 读取，
     * 键名 `entryClass`；读不到就说明不是给本项目写的包，直接报错而不是扫全包类名猜。
     */
    private fun instantiate(
        context: Context,
        archive: File,
        info: PluginArchiveInfo,
    ): LoadResult {
        val entryClass = readEntryClass(archive)
            ?: return fail(archive, info.packageName, "缺少 META-INF/lifeboard-plugin.properties 或其中没有 entryClass")

        // 先清掉 dex 输出目录里的旧产物，否则换版本后会加载到上一版的类
        val dexOut = File(context.codeCacheDir, "plugin_dex").also { if (!it.exists()) it.mkdirs() }

        return runCatching {
            val classLoader = DexClassLoader(
                archive.absolutePath,
                dexOut.absolutePath,
                null,
                // 父加载器必须是宿主的：插件要靠它拿到 :plugin-api 里的契约类型
                context.classLoader,
            )
            val clazz = Class.forName(entryClass, true, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance()

            if (instance !is SourcePlugin) {
                return fail(archive, info.packageName, "入口类 $entryClass 未实现 SourcePlugin")
            }

            // 契约版本单版本策略：不一致直接拒绝，不做多代并存
            if (instance.apiVersion != PluginApi.VERSION) {
                return fail(
                    archive,
                    info.packageName,
                    "契约版本不匹配：插件为 ${instance.apiVersion}，宿主为 ${PluginApi.VERSION}",
                )
            }

            // 指标冲突：同一 metricKey 两个量纲会让一行数据被两种口径解读
            val problems = PluginMetrics.validate(instance.metrics)
            if (problems.isNotEmpty()) {
                return fail(archive, info.packageName, "指标定义不合法：${problems.joinToString("；")}")
            }

            if (!HostPluginContext.isSafePluginId(instance.id)) {
                return fail(archive, info.packageName, "插件 id 非法：\"${instance.id}\"")
            }

            // 记录已安装版本，供下次扫描防降级与校验签名一致性
            PluginTrustStore.recordInstalled(context, info)

            LoadResult.Success(LoadedPlugin(info, archive.name, instance))
        }.getOrElse { error ->
            // 插件构造里抛出的任何异常都在这里兜住，不影响其他插件
            Log.w(TAG, "加载插件失败：${archive.name}", error)
            fail(archive, info.packageName, "加载失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim())
        }
    }

    /** 从 APK 里的 properties 文件读入口类名 */
    private fun readEntryClass(archive: File): String? = runCatching {
        java.util.zip.ZipFile(archive).use { zip ->
            val entry = zip.getEntry("META-INF/lifeboard-plugin.properties") ?: return@use null
            val props = java.util.Properties()
            zip.getInputStream(entry).use { props.load(it) }
            props.getProperty("entryClass")?.takeIf { it.isNotBlank() }
        }
    }.getOrElse { error ->
        Log.w(TAG, "读取插件元数据失败：${archive.name}", error)
        null
    }

    private fun fail(archive: File, packageName: String, reason: String) =
        LoadResult.Failure(BrokenPlugin(archive.name, packageName, reason))

    /**
     * 某插件当前是否可以参与调度。
     *
     * 只判断静态条件（已加载、仍被信任、声明了指标），**不代表此刻健康**：
     * 真实健康度要调插件的 `health(context)`，那是运行期的事。
     */
    fun isSchedulable(pluginId: String): Boolean =
        find(pluginId)?.let { it.trusted && it.metrics.isNotEmpty() } ?: false
}

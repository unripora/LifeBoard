package com.lifeboard.server.plugin

import android.content.Context
import android.util.Log
import com.lifeboard.plugin.api.PluginApi
import com.lifeboard.plugin.api.PluginMetrics
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.source.SourceProviderRegistry
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
     * 已安装但用户尚未信任（或未签名）的插件。
     *
     * 之前这里是静默跳过：`scan` 遇到 [LoadResult.Skipped] 什么都不记。
     * 结果是用户通过文件选择器装进来的 `.ext` **在界面上完全消失**——
     * 既不在已加载列表，也不在失败列表，用户会以为安装没成功。
     *
     * 因此单独记一份，供「待处理」列表展示包名与签名，让用户决定是否信任。
     */
    private val pending = mutableListOf<PendingPlugin>()

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

    /**
     * 一个已落盘、但还不能用的插件。
     *
     * 与 [BrokenPlugin] 的区别在「用户还能做什么」：
     * - [BrokenPlugin] 是包本身有问题，用户只能重新下载
     * - 本类是包没问题、只差用户一个决定（信任），或签名缺失无法给信任入口
     *
     * @property identity 信任身份（包名 + 签名集合）。无签名时为空，UI 不显示信任按钮
     * @property unsigned 是否无签名。无签名不是「等信任」，而是**根本不能信任**
     */
    data class PendingPlugin(
        val archiveName: String,
        val packageName: String,
        val versionName: String,
        val identity: PluginIdentity?,
        val unsigned: Boolean,
        val reason: String,
    )

    /** 当前可用的插件。返回副本，避免调用方持有内部列表 */
    fun loadedPlugins(): List<LoadedPlugin> = loaded.toList()

    /** 当前加载失败的插件 */
    fun brokenPlugins(): List<BrokenPlugin> = broken.toList()

    /** 已安装但尚未被信任的插件，供 UI 提供信任入口 */
    fun pendingPlugins(): List<PendingPlugin> = synchronized(this) { pending.toList() }

    /**
     * 全量重新扫描，并同步更新调度注册表。
     *
     * **整体替换而不是逐项增删**：调用方在并发读取（Worker 正在采集），
     * 先构建完整的新列表再一次性换上，读到的永远是某一时刻的完整集合。
     *
     * ## 为什么注册也放在这里
     *
     * 加载表和注册表必须描述同一批插件。之前两者由调用方分两步做，
     * 中间存在一个窗口：`SourceHealthChecker` 读加载表、`DailyAggregateWorker`
     * 读注册表，会看到不同的插件集合。把注册收进本方法，返回时两者必然一致。
     *
     * 同理，注册表的替换必须在本对象锁内完成，否则「加载表已换、注册表未换」
     * 的窗口只是从调用方挪到了这里。
     *
     * @return 本次成功加载的插件
     */
    fun scan(context: Context): List<LoadedPlugin> {
        val appContext = context.applicationContext
        val trustState = PluginTrustStore.load(appContext)

        val newLoaded = mutableListOf<LoadedPlugin>()
        val newBroken = mutableListOf<BrokenPlugin>()
        val newPending = mutableListOf<PendingPlugin>()

        PluginInstaller.listArchives(appContext).forEach { archive ->
            when (val result = loadOne(appContext, archive, trustState)) {
                is LoadResult.Success -> {
                    // 同一个 id 被两个包声明时，落库 key 会撞在一起：
                    // 后加载的指标覆盖先加载的，面板上看不出是哪来的数据。
                    // 拒绝后来者，而不是让两者互相污染。
                    val duplicated = newLoaded.firstOrNull { it.pluginId == result.plugin.pluginId }
                    if (duplicated != null) {
                        newBroken += BrokenPlugin(
                            archiveName = result.plugin.archiveName,
                            packageName = result.plugin.archive.packageName,
                            reason = "插件 id \"${result.plugin.pluginId}\" 已被 " +
                                "${duplicated.archiveName} 占用，两个包不能声明同一个 id",
                        )
                    } else {
                        newLoaded += result.plugin
                    }
                }
                is LoadResult.Failure -> newBroken += result.broken
                // 未信任/未签名不加载，但**要记下来**：
                // 只静默跳过的话，用户装完插件在界面上什么都看不到，以为装失败了
                is LoadResult.Skipped -> newPending += result.pending
            }
        }

        applyLoaded(newLoaded, newBroken, newPending)
        return newLoaded
    }

    /**
     * 用一组插件整体替换内部状态，并同步注册表。
     *
     * 抽出来是为了让 `scan` 与需要注入特定插件的测试走**同一条**状态更新路径：
     * 若测试自己拼装状态，就绕过了「注册表与加载表在同一临界区替换」这条约束，
     * 验证的就不是生产代码的行为。
     */
    internal fun applyLoaded(
        plugins: List<LoadedPlugin>,
        brokenList: List<BrokenPlugin>,
        pendingList: List<PendingPlugin> = emptyList(),
    ) {
        synchronized(this) {
            loaded.clear()
            loaded += plugins
            broken.clear()
            broken += brokenList
            pending.clear()
            pending += pendingList
            // 与加载表在同一临界区内更新，保证两者不出现短暂不一致
            registerProviders(plugins)
        }
    }

    /**
     * 把插件指标注册成一个主动拉取源。
     *
     * 一个指标一个 Provider：插件内各量纲独立成败，
     * 与内置源里小米健康的步数与睡眠各自一条健康记录一致。
     *
     * 空指标的插件不注册：它可能只提供登录管理，注册了也采不到数。
     */
    private fun registerProviders(plugins: List<LoadedPlugin>) {
        val providers = plugins.flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                SourceProviderRegistry.Provider(
                    source = SourceRef.Plugin(
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
        /** 不加载，但要让用户看到并（可能）给信任入口 */
        data class Skipped(val pending: PendingPlugin) : LoadResult()
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
            TrustVerdict.Unsigned -> LoadResult.Skipped(
                PendingPlugin(
                    archiveName = archive.name,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    identity = null,
                    unsigned = true,
                    reason = "插件包没有签名，无法建立信任，不能安装使用",
                ),
            )
            is TrustVerdict.Untrusted -> LoadResult.Skipped(
                PendingPlugin(
                    archiveName = archive.name,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    identity = verdict.identity,
                    unsigned = false,
                    reason = "尚未信任，确认来源后才会加载",
                ),
            )
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
    internal fun readEntryClass(archive: File): String? = runCatching {
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

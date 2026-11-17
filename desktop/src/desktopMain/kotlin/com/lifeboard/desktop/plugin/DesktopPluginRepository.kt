package com.lifeboard.desktop.plugin

import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PendingPlugin
import com.lifeboard.shared.platform.PluginList
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.plugin.PluginIdentity
import com.lifeboard.shared.plugin.PluginPackageInfo
import com.lifeboard.shared.plugin.PluginTrust
import com.lifeboard.shared.plugin.PluginTrustStore
import com.lifeboard.shared.plugin.TrustState
import com.lifeboard.shared.plugin.TrustVerdict
import com.lifeboard.shared.plugin.fs.DesktopPluginFileSystem
import com.lifeboard.shared.plugin.fs.PluginFileSystem
import com.lifeboard.shared.plugin.js.BrokenJsPlugin
import com.lifeboard.shared.plugin.js.JsLoadedPlugin
import com.lifeboard.shared.plugin.js.JsPluginInstallService
import com.lifeboard.shared.plugin.js.JsPluginLoader
import com.lifeboard.shared.plugin.js.PendingJsPlugin
import com.lifeboard.shared.plugin.js.PluginDirWatcher
import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.repository.CredentialVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 桌面端插件仓库：装配、缓存与信任簿记。
 *
 * ## 为什么需要这一层
 *
 * 共享层的 [JsPluginLoader] 是无状态的服务——每次 `scan()` 都重建全部运行时。
 * 但界面会频繁地问「装了哪些插件」「这个插件信不信」，
 * 每次都全量扫描等于每次都重跑一遍 QuickJS，界面会卡住。
 * 所以这里缓存一份结果，只在安装、信任、卸载、手动刷新时重建。
 *
 * ## 为什么不直接复用 Android 的 `PluginManager`
 *
 * 那个类里掺了 `PackageManager`、通知与 `WorkManager` 的调度注册，
 * 以及 Android 的文件与上下文 API——桌面端一个都用不了。
 * 桌面端因此独立一份，但**加载与信任判定的逻辑全部来自共享层**，
 * 不另写一套。
 *
 * ## `trusted` 为什么放在这里而不是共享层
 *
 * 共享层只回答「按规则它该不该被信任」，`PluginTrust.evaluate` 是纯函数。
 * 「用户刚点了撤销，但列表还没重建」这类运行期状态是宿主的簿记，
 * 各端自己记。Android 侧记在 `JsPluginLoader.untrustedIds`，桌面端记在这里。
 */
class DesktopPluginRepository(
    private val settings: SettingsStore,
    private val credentials: CredentialVault,
) {

    private val fileSystem = DesktopPluginFileSystem()

    private val trustStore = PluginTrustStore(settings)

    private val loader = JsPluginLoader(fileSystem, trustStore)

    private val installer = JsPluginInstallService(
        fileSystem = fileSystem,
        stagingDirPath = File(baseDir(), "staging").apply { mkdirs() }.absolutePath,
    )

    /**
     * 列表变化的推送通道。
     *
     * `replay = 1` 而不是用 `MutableSharedFlow` 的默认值：订阅者通常比首次扫描晚到
     * （界面在窗口起来之后才组装），没有重放就得再扫一次才能拿到已有结果。
     *
     * 初值 `ready = false`：订阅者立刻拿到一个「加载中」，界面据此显示进度而不是
     * 「一个插件都没有」——后者会诱导用户去重装一个没问题的插件。
     */
    private val state = MutableStateFlow(PluginList())

    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 插件目录监听。
     *
     * 用户把包拷进 `~/.lifeboard/js-plugins`、覆盖成新版本、删掉包，
     * 都在这里被看见并触发重扫。没有它，这些动作要等用户手动点刷新才生效。
     */
    private val watcher = PluginDirWatcher(
        fileSystem = fileSystem,
        suffix = PluginFileSystem.JS_SUFFIX,
        scope = watcherScope,
        onChange = { refresh() },
    )

    /** 是否已至少完成过一次扫描。由 [rebuild] 置位 */
    @Volatile
    private var initialized = false

    /**
     * 撤销信任后的内存标记。
     *
     * 共享层不认识「运行期是否仍信任」，扫描重建 `JsLoadedPlugin` 时不会带上这个状态。
     * 记在这里，[plugins] 每次读取时据此降级——否则用户撤销后刷一下列表它就「恢复」了。
     */
    private val revokedIds = mutableSetOf<String>()

    @Volatile
    private var loaded: List<LoadedEntry> = emptyList()

    @Volatile
    private var pending: List<PendingJsPlugin> = emptyList()

    @Volatile
    private var broken: List<BrokenJsPlugin> = emptyList()

    /** 上一次安装因冲突挂起时留下的暂存文件与目标名，供 `confirmOverwrite` 使用 */
    @Volatile
    private var stagedConflict: StagedConflict? = null

    private fun baseDir(): File =
        File(System.getProperty("user.home") ?: ".", ".lifeboard")

    /**
     * 全量重扫。
     *
     * 会释放并重建所有插件运行时，因此不允许并发调用：两个线程同时 scan
     * 会让同一个包被创建两次运行时，其中一个的 `close()` 关掉另一个正在用的。
     */
    @Synchronized
    fun refresh(): List<LoadedEntry> = rebuild(loader.scan())

    /** 已加载插件。首次访问时自动扫描一次 */
    fun plugins(): List<LoadedEntry> {
        if (loaded.isEmpty() && !initialized) refresh()
        return loaded
    }

    fun pendingPlugins(): List<PendingJsPlugin> {
        if (!initialized) refresh()
        return pending
    }

    fun brokenPlugins(): List<BrokenJsPlugin> {
        if (!initialized) refresh()
        return broken
    }

    /**
     * 插件盘面的持续观察。
     *
     * 首次订阅时启动目录监听，并异步做一次全量扫描。
     * 监听只负责「之后的变化」，首扫由这里显式触发：它可以发生在任意线程上，
     * 而监听器启动时不该假设「已经初始化过」。
     */
    fun snapshots(): StateFlow<PluginList> {
        watcher.start()
        if (!initialized) {
            watcherScope.launch {
                runCatching { refresh() }
                    .onFailure { AppLogger.error("首次插件扫描失败", it) }
            }
        }
        return state.asStateFlow()
    }

    fun find(pluginId: String): LoadedEntry? = plugins().firstOrNull { it.pluginId == pluginId }

    /**
     * 安装一个 `.jsplugin` 包。
     *
     * @param source 用户选中的文件。桌面端 `FilePicker` 交出的 `uri` 就是绝对路径
     */
    suspend fun install(source: File): JsPluginInstallService.Outcome =
        withContext(Dispatchers.IO) {
            // 桌面端只跑 JS 轨。用户手里常有 `.apk` 插件包（Android 侧的真实格式），
            // 不在这里拦的话它会一路走到 zip 解析，最后报「不是合法的插件包」——
            // 用户分不清是自己文件坏了还是格式不支持，只能反复重试。
            if (!source.name.endsWith(PluginFileSystem.JS_SUFFIX, ignoreCase = true)) {
                return@withContext JsPluginInstallService.Outcome.Failed(
                    "桌面端仅支持 ${PluginFileSystem.JS_SUFFIX} 插件包，不支持 ${source.extension.ifBlank { "该" }} 格式"
                )
            }

            // 先把源文件复制到暂存目录：`install` 无论成功失败都会删掉输入文件，
            // 直接传用户选的路径等于把他的原文件删了
            val staged = installer.stage(source.readBytes(), source.name)
                ?: return@withContext JsPluginInstallService.Outcome.Failed(
                    "无法读取所选文件：${source.name}"
                )

            when (val outcome = installer.install(staged)) {
                is JsPluginInstallService.Outcome.Installed -> {
                    refresh()
                    outcome
                }
                is JsPluginInstallService.Outcome.Conflict -> {
                    // 暂存文件由 install 带出来，确认时再重新校验一次落盘
                    stagedConflict = StagedConflict(outcome.staged, outcome.incomingArchiveName)
                    outcome
                }
                is JsPluginInstallService.Outcome.Failed -> outcome
            }
        }

    /**
     * 确认覆盖同名插件。
     *
     * 覆盖后必须**重新走一遍信任判定**：新包的内容哈希与旧的不同，
     * 已信任记录自然失效，用户要再确认一次。这里不预先信任，
     * 只是把它重新扫进「待信任」列表——否则覆盖就等于绕过了确认。
     */
    suspend fun confirmOverwrite(): Boolean = withContext(Dispatchers.IO) {
        val staged = stagedConflict ?: return@withContext false
        stagedConflict = null
        val ok = installer.confirmOverwrite(staged.file, staged.targetName)
        if (ok) {
            refresh()
        }
        ok
    }

    /** 放弃这次覆盖安装 */
    fun discardStaged() {
        stagedConflict?.let { installer.discard(it.file) }
        stagedConflict = null
    }

    /** 信任一个身份，并让它立刻进入可加载状态 */
    @Synchronized
    fun trust(identity: PluginIdentity) {
        trustStore.trust(identity)
        revokedIds -= identity.packageName
        // 记录安装版本：防降级与后续签名一致性都以它为基准
        pending.firstOrNull { it.packageName == identity.packageName }
            ?.let { pendingInfo ->
                loadInfo(pendingInfo.archiveName)?.let { trustStore.recordInstalled(it) }
            }
        refresh()
    }

    /**
     * 撤销信任。
     *
     * 只翻标记、停调度，不删包也不删数据：用户可能只是暂时不放心。
     * 撤销后重新扫描，让该插件从可加载列表里消失。
     */
    @Synchronized
    fun revokeTrust(pluginId: String) {
        val entry = loaded.firstOrNull { it.pluginId == pluginId }
        val packageName = entry?.packageInfo?.packageName ?: pluginId

        revokedIds += packageName
        // 撤销的是**身份**而不是某个包：同一包名下的全部签名都失效，
        // 否则用户重新装一个同 id 的包会因旧签名还留在信任表里而被自动信任
        trustStore.revokePackage(packageName)
        trustStore.forgetInstalled(packageName)

        loaded = loaded.map {
            if (it.pluginId == pluginId) it.copy(trusted = false) else it
        }
        refresh()
    }

    /** 卸载：删包、撤销信任，保留凭据与私有数据 */
    @Synchronized
    fun uninstall(pluginId: String) {
        val entry = loaded.firstOrNull { it.pluginId == pluginId } ?: return
        loader.remove(entry.archiveName)
        trustStore.revoke(entry.packageInfo.identity)
        trustStore.forgetInstalled(entry.packageInfo.packageName)
        revokedIds -= entry.packageInfo.packageName
        refresh()
    }

    /** 删掉一个还没信任（或加载失败）的包 */
    @Synchronized
    fun uninstallArchive(archiveName: String) {
        loader.remove(archiveName)
        refresh()
    }

    /** 清空某插件全部凭据，让它回到未登录 */
    suspend fun clearCredentials(pluginId: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            credentials.clearPlugin(pluginId)
        }.getOrElse { error ->
            AppLogger.error("清除插件凭据失败：$pluginId", error)
            0
        }
    }

    /** 退出时释放全部 QuickJS 运行时 */
    @Synchronized
    fun closeAll() {
        watcher.stop()
        loader.closeAll()
        loaded = emptyList()
        initialized = false
    }

    /**
     * 把扫描结果装进缓存。
     *
     * `trusted` 不取共享层的结论（共享层只放行已信任的，能出现在这里的就是可信的），
     * 而是再对一遍 [revokedIds]：撤销后同一进程内重扫时，
     * 信任存储虽已清空，但若用户撤销时插件仍在内存里，这里要保证它不再被当成可信。
     */
    private fun rebuild(scanned: List<JsLoadedPlugin>): List<LoadedEntry> {
        val entries = scanned.map { plugin ->
            val info = loadInfo(plugin.archiveName) ?: plugin.packageInfo
            LoadedEntry(
                pluginId = plugin.plugin.id,
                displayName = plugin.plugin.displayName,
                packageInfo = info,
                archiveName = plugin.archiveName,
                plugin = plugin.plugin,
                trusted = plugin.plugin.id !in revokedIds,
            )
        }
        loaded = entries
        pending = loader.pendingPlugins()
        broken = loader.brokenPlugins()
        initialized = true
        publish()
        return entries
    }

    /**
     * 把当前三份列表打成一次快照推给订阅者。
     *
     * 翻译逻辑（`LoadedEntry` → `PluginSummary`、`PendingJsPlugin` → `PendingPlugin`）
     * 从 `DesktopPluginHost` 搬到这里：监听触发的重扫也必须推送，
     * 而那里只有「被调用时才算」的接口。两份翻译会让两边慢慢漂移。
     */
    private fun publish() {
        val trusted = revokedIds
        state.value = PluginList(
            installed = loaded.map { it.toSummary(trusted) },
            pending = pending.map {
                PendingPlugin(
                    archiveName = it.archiveName,
                    packageName = it.packageName,
                    versionName = it.versionName,
                    unsigned = it.unsigned,
                    reason = it.reason,
                    signatureHashes = it.identity?.signatureHashes?.sorted().orEmpty(),
                )
            },
            broken = broken.map {
                PendingPlugin(
                    archiveName = it.archiveName,
                    packageName = it.archiveName.substringBeforeLast('.'),
                    versionName = "",
                    unsigned = false,
                    reason = it.reason,
                    signatureHashes = emptyList(),
                )
            },
            ready = true,
        )
    }

    private fun LoadedEntry.toSummary(revoked: Set<String>): PluginSummary = PluginSummary(
        pluginId = pluginId,
        displayName = displayName,
        packageName = packageInfo.packageName,
        versionName = packageInfo.versionName,
        archiveName = archiveName,
        metricCount = metrics.size,
        trusted = pluginId !in revoked,
        signatureHashes = packageInfo.signatureHashes.sorted(),
        needsLogin = needsLogin,
    )

    /** 读包元数据。失败返回 null 时调用方退回共享层给出的那份 */
    private fun loadInfo(archiveName: String): PluginPackageInfo? = runCatching {
        val archive = File(fileSystem.pluginsRoot(), archiveName)
        installer.readPackageInfo(archive)
    }.getOrNull()

    /** 上一次安装挂起的覆盖请求 */
    private data class StagedConflict(val file: File, val targetName: String)
}

/**
 * 一个可用的插件及其宿主簿记。
 *
 * 与 Android 侧的 `LoadedPlugin` 同构，但独立定义：那边的簿记绑着 Android 的
 * 上下文与注册表，共用会把桌面端拖进 Android 的依赖里。
 */
data class LoadedEntry(
    val pluginId: String,
    val displayName: String,
    val packageInfo: PluginPackageInfo,
    val archiveName: String,
    val plugin: SourcePlugin,
    val trusted: Boolean,
) {
    /** 插件声明的指标，按 key 索引，落库时按 key 反查量纲 */
    val metrics: Map<String, com.lifeboard.plugin.api.MetricSpec> =
        plugin.metrics.associateBy { it.metricKey }

    /** 需要登录：声明了凭据槽位即视为需要 */
    val needsLogin: Boolean get() = plugin.credentialSpec.slots.isNotEmpty()

    override fun toString(): String = "LoadedEntry($pluginId v${packageInfo.versionCode})"
}

package com.lifeboard.server.plugin

import android.content.Context
import android.net.Uri
import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PendingPlugin
import com.lifeboard.shared.platform.PluginList
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.plugin.fs.AndroidPluginFileSystem
import com.lifeboard.shared.plugin.js.PluginDirWatcher
import com.lifeboard.shared.repository.PluginDataPurger
import com.lifeboard.server.plugin.js.JsPluginInstallService
import com.lifeboard.server.plugin.js.JsPluginLoader
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
 * 插件系统的对外入口：扫描、信任与撤销。
 *
 * UI 只跟这个类打交道，不直接碰 [JsPluginLoader] 和 [JsPluginInstallService]。
 *
 * ## 为什么这里几乎全是转发
 *
 * 「加载插件」和「把插件注册进调度」必须同时发生，否则插件在列表里看得见却永远不采数。
 * 这个约束的落实放在 [JsPluginLoader.scan] 内部（在同一个临界区里完成二者），
 * 因此本类不再承担编排职责，只负责：
 * - 把 `Context` 收敛成 `applicationContext`，避免调用方传入 Activity 造成泄漏
 * - 把「信任后立即生效」「撤销后停止调度」这两个用户动作翻译成存储 + 扫描
 *
 * 如果将来出现新的跨步骤编排需求（如安装 → 校验 → 注册），仍应加在这里。
 *
 * ## 只剩一条轨道
 *
 * APK 轨（`.apk` + `DexClassLoader`）已于 M6b 移除，现在只有 JS 轨。
 * 移除的理由不是「APK 不好用」，而是两条轨意味着两套加载器、两套安装校验、两套信任语义，
 * 而它们服务的插件数量是 1——小米健康 JS 版覆盖了 APK 版的同一 pluginId 与全部 15 个指标。
 */
object PluginManager {

    /**
     * 扫描/安装/信任的串行锁。
     *
     * 这几条路径都会重建插件注册表：`refresh` 内部先 `closeAll()` 再重新加载。
     * 两处并发进入时，后进入者会把前者刚加载好的运行时关掉，
     * 后者随即又建一套，最终注册表里指向的是被关闭的实例——表现为
     * 「插件列表里有，点进去全空」或「采集偶发拿不到数据」。
     */
    private val lifecycleLock = Any()

    /**
     * 插件盘面的推送通道。
     *
     * 初值 `ready = false`：订阅者立刻拿到「加载中」，界面显示进度而不是
     * 「一个插件都没有」——后者会诱导用户去重装一个没问题的插件。
     */
    private val state = MutableStateFlow(PluginList())

    /** 监听器所在的 scope。用应用级而非界面级：插件变化与某个页面开没开无关 */
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var watcher: PluginDirWatcher? = null

    /**
     * 插件盘面的持续观察。
     *
     * 首次调用时装上目录监听并异步做一次全量扫描。之后用户用文件管理器
     * 把包拷进插件目录、覆盖成新版本、删掉包，都会自动重扫并推给界面。
     *
     * 这里不用 `ContentObserver`：插件目录在应用私有区，外部只能通过
     * 我们自己的安装入口写入——但用户仍可能用 adb、备份恢复、或未来的
     * 「从文件夹导入」把包放进去。轮询判断与桌面端共用同一套规则，
     * 两端不会各自演化出不同的「什么时候算变化」。
     */
    fun snapshots(context: Context): StateFlow<PluginList> {
        val appContext = context.applicationContext
        ensureWatcher(appContext)
        return state.asStateFlow()
    }

    private fun ensureWatcher(appContext: Context) {
        if (watcher != null) return
        synchronized(lifecycleLock) {
            if (watcher != null) return
            watcher = PluginDirWatcher(
                fileSystem = AndroidPluginFileSystem(appContext.filesDir),
                suffix = JsPluginLoader.SUFFIX,
                scope = watcherScope,
                onChange = {
                    // 失败不往上抛：目录可能正被写入，下一次轮询会再来一遍。
                    // 抛出去会让监听协程退出，用户之后改目录就永远没反应了
                    runCatching { refresh(appContext) }
                        .onFailure { AppLogger.error("插件目录变化后重扫失败", it) }
                },
            ).also { it.start() }
        }
    }

    /**
     * 重新扫描并同步注册表。
     *
     * 触发点：应用启动、用户安装新插件、用户撤销信任、插件目录变化。
     *
     * 串行化执行：见 [lifecycleLock]。
     */
    fun refresh(context: Context): List<LoadedPlugin> {
        val appContext = context.applicationContext
        val loaded = synchronized(lifecycleLock) {
            JsPluginLoader.scan(appContext)
        }
        publish(appContext, loaded)
        return loaded
    }

    /**
     * 把一次扫描的结果推给订阅者。
     *
     * 在锁**外面**做：`CredentialVault.slots` 要查库，占着 lifecycleLock
     * 会让并发的安装/信任动作排在一次数据库读后面，而这段时间里它们本可以
     * 什么都不用等。
     */
    private fun publish(appContext: Context, loaded: List<LoadedPlugin>) {
        // 凭据槽位要查库，是挂起调用；放进 scope 里算，算完再推。
        // 不在这里 await 的原因：refresh() 的调用方遍布安装、信任、后台采集，
        // 为了一个「列表上要不要显示登录按钮」的字段把它们全改成挂起得不偿失
        watcherScope.launch {
            state.value = PluginList(
                installed = loaded.map {
                    PluginSummary(
                        pluginId = it.pluginId,
                        displayName = it.plugin.displayName,
                        packageName = it.archive.packageName,
                        versionName = it.archive.versionName,
                        archiveName = it.archiveName,
                        metricCount = it.metrics.size,
                        trusted = it.trusted,
                        signatureHashes = it.archive.signatureHashes.toList(),
                        // 有槽位就意味着这个插件曾经登录过（槽位是在登录时按需创建的），
                        // 空表示不需要账号
                        needsLogin = CredentialVault.slots(appContext, it.pluginId).isNotEmpty(),
                    )
                },
                pending = pending(appContext).map {
                    PendingPlugin(
                        archiveName = it.archiveName,
                        packageName = it.packageName,
                        versionName = it.versionName,
                        unsigned = it.unsigned,
                        reason = it.reason,
                        signatureHashes = it.identity?.signatureHashes?.toList().orEmpty(),
                    )
                },
                broken = broken(appContext).map {
                    PendingPlugin(
                        archiveName = it.archiveName,
                        packageName = it.packageName,
                        versionName = "",
                        unsigned = false,
                        reason = it.reason,
                        signatureHashes = emptyList(),
                    )
                },
                ready = true,
            )
        }
    }

    /**
     * 信任一个待信任插件，并立即生效。
     *
     * 用户的意图是「现在就能用」，所以这里连带重新扫描，而不是等下次启动。
     */
    fun trust(context: Context, identity: PluginIdentity) {
        val appContext = context.applicationContext
        synchronized(lifecycleLock) {
            PluginTrustStore.trust(appContext, identity)
        }
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
        val archive = JsPluginLoader.find(appContext, pluginId)?.archive ?: return
        PluginTrustStore.revokePackage(appContext, archive.packageName)
        JsPluginLoader.markUntrusted(appContext, pluginId)
        // 注册表里该插件的 Provider 仍在，但 isSchedulable 会返回 false，
        // PluginDataSource 也会因 trusted=false 返回空数据与「已撤销」状态
    }

    /** 当前已加载的插件 */
    fun loaded(context: Context): List<LoadedPlugin> =
        JsPluginLoader.loadedPlugins(context.applicationContext)

    /** 加载失败的插件及原因，供 UI 展示 */
    fun broken(context: Context): List<BrokenPluginEntry> =
        JsPluginLoader.brokenPlugins(context.applicationContext).map {
            // 包的包名读不出来时用文件名兜底，否则列表里会出现空白项
            BrokenPluginEntry(
                archiveName = it.archiveName,
                packageName = it.archiveName.substringBeforeLast('.'),
                reason = it.reason,
            )
        }

    /** 已安装但尚未信任/无签名的插件，供 UI 展示信任入口 */
    fun pending(context: Context): List<PendingPluginEntry> =
        JsPluginLoader.pendingPlugins(context.applicationContext).map {
            PendingPluginEntry(
                archiveName = it.archiveName,
                packageName = it.packageName,
                versionName = it.versionName,
                identity = it.identity,
                unsigned = it.unsigned,
                reason = it.reason,
            )
        }

    /**
     * 卸载插件：删包 + 清除安装记录 + 撤销信任 + 重新扫描。
     *
     * @param clearData true 时一并清掉该插件产生的全部数据（私有目录、凭据、
     *   历史采集）。false 时保留——用户可能只是想换版本重装，账号和缓存
     *   没了就得重新登录、历史曲线也断了。
     */
    suspend fun uninstall(context: Context, pluginId: String, clearData: Boolean) {
        val appContext = context.applicationContext
        val metricKeys = synchronized(lifecycleLock) {
            val js = JsPluginLoader.find(appContext, pluginId) ?: return
            val keys = js.plugin.metrics.map { it.metricKey }
            JsPluginLoader.closeAll(appContext)
            JsPluginLoader.remove(appContext, js.archiveName)
            PluginTrustStore.revokePackage(appContext, js.archive.packageName)
            keys
        }
        if (clearData) {
            clearPluginData(appContext, pluginId, metricKeys)
        }
        refresh(appContext)
    }

    /**
     * 清空某插件产生的全部数据，但保留插件包与信任关系。
     *
     * 与「清除凭据」不同：那个只让插件回到未登录，这里连历史采集一起删。
     * 编排在共享层的 [PluginDataPurger]，平台差异只有「私有目录在哪」。
     */
    suspend fun clearPluginData(context: Context, pluginId: String) {
        val appContext = context.applicationContext
        val keys = JsPluginLoader.find(appContext, pluginId)?.plugin?.metrics?.map { it.metricKey }.orEmpty()
        clearPluginData(appContext, pluginId, keys)
    }

    private suspend fun clearPluginData(
        appContext: Context,
        pluginId: String,
        metricKeys: Collection<String>,
    ) {
        PluginDataPurger(
            pluginStore = DatabaseProviders.pluginStore(appContext),
            metricStore = DatabaseProviders.metricStore(appContext),
            clearCredentials = { id -> CredentialVault.clearPlugin(appContext, id) },
            deleteDataDir = {
                // 删整个插件目录而不只是 data 子目录：目录里若将来还有别的东西
                // （缓存、导出文件），「清除数据」都不该留下
                File(File(appContext.filesDir, "plugins"), it).deleteRecursively()
            },
        ).purge(pluginId, metricKeys)
    }

    /**
     * 卸载一个尚未信任的包。
     *
     * 它没被加载过，拿不到 [LoadedPlugin]，只能靠文件名删。
     */
    fun uninstallPending(context: Context, archiveName: String) {
        val appContext = context.applicationContext
        synchronized(lifecycleLock) {
            JsPluginLoader.closeAll(appContext)
            JsPluginLoader.remove(appContext, archiveName)
        }
        refresh(appContext)
    }

    // ---- 登录 ----

    /**
     * 向插件发起一轮登录。
     *
     * 登录**流程**完全在插件里，宿主只做三件事：给它一个绑定了 pluginId 的上下文、
     * 把用户填的值传进去、把结果原样交回 UI。
     *
     * 之所以要这个入口而不是让 UI 直接调插件：
     * [HostPluginContext] 的构造需要收敛 `applicationContext`，且插件实例只能从
     * [JsPluginLoader] 拿到手。UI 不应该知道这两件事。
     *
     * @param inputs 上一轮 [LoginResult.NeedInput] 要用户填的值
     * @return 插件本轮结果；插件不存在或未信任时返回可展示的失败
     */
    suspend fun login(
        context: Context,
        pluginId: String,
        inputs: Map<String, String> = emptyMap(),
    ): LoginResult {
        val appContext = context.applicationContext
        val plugin = findPlugin(appContext, pluginId)
            ?: return LoginResult.Failure("插件未加载，请重新扫描")
        if (!plugin.trusted) {
            return LoginResult.Failure("插件已停止使用，请先信任再登录")
        }

        // 插件自己的异常不能打到 UI 上：契约要求它返回 Failure，
        // 但第三方插件未必守规矩，这里兜一层并给出可读原因。
        //
        // 必须切到 IO：JS 插件的调用要等 HTTP 往返，内部是阻塞式等专用线程返回，
        // 直接在调用方线程（通常是主线程）跑会把界面卡到超时。
        return withContext(Dispatchers.IO) {
            runCatching {
                plugin.plugin.login(HostPluginContext(pluginId, appContext), inputs)
            }.getOrElse { e ->
                LoginResult.Failure("插件登录时出错：${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * 查询插件当前登录态；失败按「未登录」处理，避免 UI 卡在没有状态。
     *
     * 未信任时不进插件代码：撤销信任只是给已加载实例打标记，实例本身还在，
     * 继续调用就等于「已经让用户停用了，却还在执行插件的代码、还能碰凭据」。
     */
    suspend fun authState(context: Context, pluginId: String): AuthState? {
        val appContext = context.applicationContext
        val plugin = findPlugin(appContext, pluginId) ?: return AuthState.LoggedOut
        if (!plugin.trusted) return AuthState.LoggedOut
        // 同 login：插件调用内部会等 HTTP 往返，不能占着调用方线程。
        // 失败返回 null 而不是 LoggedOut：那是「这次问不出来」，
        // 若当成插件给出的结论，界面就会在用户明明登录着的时候显示未登录
        return withContext(Dispatchers.IO) {
            runCatching {
                plugin.plugin.authState(HostPluginContext(pluginId, appContext))
            }.getOrElse {
                AppLogger.warn("插件 $pluginId 的 authState 调用失败：${it.message ?: it::class.simpleName}")
                null
            }
        }
    }

    /**
     * 让插件清理自己名下的凭据。
     *
     * 未信任时同样不调用插件：用户要的是「别再用它了」，
     * 这时候再跑它的登出逻辑，等于让一个已被停用的插件继续接触凭据。
     * 真要清干净，走详情页的「清除凭据」，那是宿主直接删库。
     */
    suspend fun logout(context: Context, pluginId: String) {
        val appContext = context.applicationContext
        val plugin = findPlugin(appContext, pluginId) ?: return
        if (!plugin.trusted) return
        withContext(Dispatchers.IO) {
            runCatching { plugin.plugin.logout(HostPluginContext(pluginId, appContext)) }
        }
    }

    // ---- 详情页 ----

    /**
     * 插件自己在详情页要展示的内容。
     *
     * 插件未加载、未信任或抛异常时返回空列表：详情页还有宿主固定的通用信息与通用操作，
     * 不该因为插件自己出错就整页打不开；未信任的更不该再被调用。
     */
    suspend fun detailUi(context: Context, pluginId: String): List<UiBlock> {
        val appContext = context.applicationContext
        val plugin = findPlugin(appContext, pluginId) ?: return emptyList()
        if (!plugin.trusted) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                plugin.plugin.detailUi(HostPluginContext(pluginId, appContext))
            }.getOrElse { emptyList() }
        }
    }

    /**
     * 用户在详情页点了插件自己的按钮。
     *
     * [ActionResult.TriggerSync] 由本类接管：插件拿不到数据库入口，手动同步必须走
     * 宿主既有的采集链路（`DailyAggregateWorker` → `SourceProviderRegistry` → `metric` 表），
     * 否则健康度、去重与量纲校验都会被绕过。
     *
     * 用 [DailyAggregateWorker.collectNow] 而不是 `runNow`：后者只是入队，
     * 写库完成时间不确定，用户点完按钮立刻读库会拿到旧值，表现为「点一次没反应」。
     * 写完后再重新问一次 [detailUi]，插件返回的区块就能反映最新数据。
     */
    suspend fun onAction(context: Context, pluginId: String, actionId: String): ActionResult {
        val appContext = context.applicationContext
        val plugin = findPlugin(appContext, pluginId)
            ?: return ActionResult.ShowMessage("插件未加载，请重新扫描")

        if (!plugin.trusted) {
            return ActionResult.ShowMessage("插件已停止使用，请先信任再操作")
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                plugin.plugin.onAction(HostPluginContext(pluginId, appContext), actionId)
            }.getOrElse { e ->
                // 第三方插件未必守规矩，异常不能打到 UI 上
                ActionResult.ShowMessage("插件处理操作时出错：${e.message ?: e::class.simpleName}")
            }
        }

        if (result !is ActionResult.TriggerSync) return result

        val ok = DailyAggregateWorker.collectNow(appContext)
        if (!ok) {
            // 采集链路的失败原因（网络、未登录、脚本报错）由 Worker 与插件自己写进日志，
            // 这里只给用户一句可执行的提示，不把内部细节抛到界面上
            AppLogger.warn("插件 $pluginId 手动同步未成功，详见运行日志")
            return ActionResult.ShowMessage("同步未完成，请检查登录状态与网络后重试")
        }
        // 采集成功：优先用插件自己刚算好的区块，它最清楚该显示什么
        return ActionResult.RefreshUi(detailUi(appContext, pluginId))
    }

    // ---- 安装 ----

    /**
     * 从用户选中的文件安装插件。
     *
     * **只接受 `.jsplugin`**，非该后缀在入口处直接给明确提示——用户手里可能还留着
     * 别的格式的包，让它走到解包再报「不是合法的插件包」，他分不清是文件坏了
     * 还是格式不支持。
     *
     * 安装**不等于**信任：装完只是进了待处理列表，脚本一行都不会执行。
     */
    suspend fun install(context: Context, uri: Uri): InstallOutcome {
        val appContext = context.applicationContext
        val name = (JsPluginInstallService.displayName(appContext, uri) ?: uri.lastPathSegment.orEmpty())
            .lowercase()

        if (!name.endsWith(JsPluginLoader.SUFFIX)) {
            return InstallOutcome.Failed(
                "只支持 ${JsPluginLoader.SUFFIX} 插件包，" +
                    "APK 格式的插件已不再支持"
            )
        }

        return when (val outcome = JsPluginInstallService.installFromUri(appContext, uri)) {
            is com.lifeboard.shared.plugin.js.JsPluginInstallService.Outcome.Installed ->
                InstallOutcome.Installed(outcome.archiveName, outcome.info)
            is com.lifeboard.shared.plugin.js.JsPluginInstallService.Outcome.Conflict ->
                InstallOutcome.Conflict(
                    existingArchiveName = outcome.existingArchiveName,
                    existing = outcome.existing,
                    incomingArchiveName = outcome.incomingArchiveName,
                    incoming = outcome.incoming,
                    stagedFile = outcome.staged,
                )
            is com.lifeboard.shared.plugin.js.JsPluginInstallService.Outcome.Failed ->
                InstallOutcome.Failed(outcome.reason)
        }
    }

    /** 用户确认覆盖后落盘 */
    suspend fun confirmOverwrite(context: Context, staged: File, archiveName: String): Boolean =
        JsPluginInstallService.confirmOverwrite(context.applicationContext, staged, archiveName)

    /** 放弃一次待确认的安装 */
    fun discardInstall(staged: File) {
        JsPluginInstallService.discard(staged)
    }

    // ---- 查找 ----

    /**
     * 按 pluginId 找到插件实例。
     *
     * 对外公开是因为详情页要读插件的指标声明与 `credentialSpec`（渲染通用信息区），
     * 而这两项在 [LoadedPlugin] 上，没有别的方式拿到。
     */
    fun findPlugin(context: Context, pluginId: String): LoadedPlugin? =
        JsPluginLoader.find(context.applicationContext, pluginId)
}

/**
 * 一次安装的结果。
 *
 * 保留 sealed 结构而不是直接返回布尔值：UI 的动作是三种，成功就刷新列表、
 * 冲突就弹覆盖确认、失败就显示原因，每种都要带不同的数据。
 */
sealed class InstallOutcome {

    /** 已落盘、待用户信任 */
    data class Installed(
        val archiveName: String,
        val info: PluginPackageInfo,
    ) : InstallOutcome()

    /** 同 id 插件已存在，等用户确认覆盖 */
    data class Conflict(
        val existingArchiveName: String,
        val existing: PluginPackageInfo,
        /** 覆盖后要落成的文件名，确认时原样传回 [PluginManager.confirmOverwrite] */
        val incomingArchiveName: String,
        val incoming: PluginPackageInfo,
        val stagedFile: File,
    ) : InstallOutcome()

    data class Failed(val reason: String) : InstallOutcome()
}

/**
 * 一个加载失败的插件条目。
 *
 * 留着 [reason] 是为了让用户能判断该删还是该重装，
 * 而不是只看到一个「加载失败」的哑条目。
 */
data class BrokenPluginEntry(
    val archiveName: String,
    val packageName: String,
    val reason: String,
)

/**
 * 一个等待用户信任的插件条目。
 *
 * @property identity 信任身份。为 null 表示连指纹都没有，UI 不该给信任按钮
 * @property unsigned 是否缺少指纹。缺指纹意味着「无法建立信任」，而非「等用户点头」
 */
data class PendingPluginEntry(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val identity: PluginIdentity?,
    val unsigned: Boolean,
    val reason: String,
)

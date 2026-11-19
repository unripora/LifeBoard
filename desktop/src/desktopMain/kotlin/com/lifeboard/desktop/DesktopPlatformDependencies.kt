package com.lifeboard.desktop

import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.desktop.apps.DesktopAppCatalog
import com.lifeboard.desktop.backend.DesktopCollectionBackend
import com.lifeboard.desktop.backend.DesktopRemindBackend
import com.lifeboard.desktop.backend.DesktopRulesBackend
import com.lifeboard.desktop.log.DesktopAppLogger
import com.lifeboard.desktop.notify.DesktopNotifier
import com.lifeboard.desktop.plugin.DesktopPluginCollector
import com.lifeboard.desktop.plugin.DesktopPluginDetailStore
import com.lifeboard.desktop.plugin.DesktopPluginHost
import com.lifeboard.desktop.plugin.DesktopPluginInvoker
import com.lifeboard.desktop.plugin.DesktopPluginRepository
import com.lifeboard.desktop.schedule.DesktopScheduler
import com.lifeboard.desktop.screentime.DesktopScreenTimeBackend
import com.lifeboard.desktop.screentime.NativeFrontWindowProbe
import com.lifeboard.desktop.screentime.UnavailableFrontWindowProbe
import com.lifeboard.desktop.secret.DpapiSecretCodec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.dashboard.DashboardLayout
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.db.openDatabase
import com.lifeboard.shared.panel.ConditionEvaluator
import com.lifeboard.shared.panel.DatabasePanelScope
import com.lifeboard.shared.panel.PluginMetric
import com.lifeboard.shared.panel.PluginMetricSet
import com.lifeboard.shared.panel.SourceHealthEntry
import com.lifeboard.shared.platform.AppIconProvider
import com.lifeboard.shared.platform.AppInfo
import com.lifeboard.shared.platform.CaptureBackend
import com.lifeboard.shared.platform.DashboardBackend
import com.lifeboard.shared.platform.FilePicker
import com.lifeboard.shared.platform.Notifier
import com.lifeboard.shared.platform.PendingPlugin
import com.lifeboard.shared.platform.PickedFile
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PluginHost
import com.lifeboard.shared.platform.PluginInstallResult
import com.lifeboard.shared.platform.PluginInvoker
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.platform.SecretCodec
import com.lifeboard.shared.platform.SettingsStore
import com.lifeboard.shared.platform.SourceHealthProbe
import com.lifeboard.shared.platform.TextFileExporter
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.host.HostPluginContext
import com.lifeboard.shared.repository.CredentialVault
import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.model.AppUsageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 桌面端依赖装配。
 *
 * ## M3 → M4 的变化
 *
 * M3 这一版的策略是「能简单做到的就真做，做不到的显式抛未实现」，
 * 目的是让两端共用界面代码先跑起来。M4 补上真正可用的桌面实现：
 *
 * | 能力 | M3 | M4 |
 * |---|---|---|
 * | 发通知 | 抛未实现 | 系统托盘气泡（`TrayIcon`） |
 * | 定时唤醒 | 抛未实现 | 进程内协程定时器 |
 * | 提醒链路 | 抛未实现 | 完整接通（判定 → 投递 → 落库） |
 * | 规则后端 | 抛未实现 | 真实现（读同一份 metric 表） |
 * | 自动刷新 | `Unsupported` | 接上周期调度 |
 * | 凭据加密 | 无 | Windows DPAPI |
 *
 * 仍然抛未实现的只剩三块，且都**有充分理由**：
 *
 * - [PluginHost]：桌面端的插件安装/信任模型涉及解包 apk 与签名校验，
 *   属于 M5 的范围
 * - [ScreenTimeBackend]：桌面端不采集屏幕时长（也不该偷偷采集）
 * - 各类「读屏/通知监听」状态：桌面端根本没有这套机制，返回 false 而非抛异常
 */
object DesktopPlatformDependencies {

    /**
     * 库文件位置。
     *
     * 放用户主目录下的 `.lifeboard/lifeboard.db`：
     * Windows 上是 `C:\Users\<用户>\.lifeboard\`，不写进安装目录——
     * 那个目录在 Program Files 下通常不可写，且升级时会整个被替换掉。
     */
    private val dbFile: File
        get() = File(System.getProperty("user.home"), ".lifeboard/lifeboard.db")

    /**
     * 数据层装配，进程内单例。
     *
     * 必须单例：`JdbcSqliteDriver` 内部维护连接池，每次新建会让多个连接
     * 各自对同一个库跑迁移，第二条会因「表已存在」直接失败。
     */
    private val stores: DatabaseStores by lazy {
        dbFile.parentFile?.mkdirs()
        DatabaseStores(openDatabase(DatabaseDriverFactory(), dbFile.absolutePath))
    }

    /**
     * 应用主 scope。
     *
     * 所有「同步回调 → 异步工作」的转换都挂在它上面。
     * 与界面 scope 分开：界面重组不该影响提醒的投递，
     * 关掉主窗口也不该让正在写的日志被取消。
     */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private val settings: SettingsStore by lazy { PrefsSettingsStore() }

    /** 进程内定时器。退出时要 `shutdown()`，否则挂起的协程会拖住 JVM */
    private val schedulerInternal: DesktopScheduler by lazy { DesktopScheduler() }

    private val notifierInternal: Notifier by lazy { DesktopNotifier() }

    /**
     * 提醒后端，进程内单例。
     *
     * 必须单例而不是在 `create()` 里新建：它持有定时器的 tag → 回调注册表。
     * 若每次 `create()` 都造一个新的，`bootstrap()` 重排时注册的回调
     * 与界面拿到的那个实例就不是同一批对象——表现是「提醒到点了但什么都没发生」，
     * 而且不会报错。
     */
    private val remindBackendInternal: DesktopRemindBackend by lazy {
        DesktopRemindBackend(stores, schedulerInternal, notifierInternal, appScope)
    }

    /** 采集/刷新后端，同样必须单例（理由同上） */
    private val collectionBackendInternal: DesktopCollectionBackend by lazy {
        DesktopCollectionBackend(stores, schedulerInternal, settings, appScope)
    }

    /**
     * 屏幕时长后端（M11）。
     *
     * 必须单例：它持有「上次采样是哪个应用、几点」这份内存状态。
     * 多实例会让每份各记一半，最终写库时互相覆盖，
     * 表现是「用了一小时，趋势页显示二十分钟」。
     */
    private val screenTimeBackendInternal: DesktopScreenTimeBackend by lazy {
        DesktopScreenTimeBackend(
            metricStore = stores.metricStore,
            frontWindow = if (isWindows()) {
                NativeFrontWindowProbe()
            } else {
                // 非 Windows 不硬编 return null：让面板如实显示「当前环境不支持」，
                // 而不是一个永远 0 分钟、看起来在记其实没记的面板
                UnavailableFrontWindowProbe
            },
        )
    }

    /**
     * 凭据加解密。
     *
     * Windows 上用 DPAPI；其它平台没有 Crypt32，构造时会抛
     * `UnsatisfiedLinkError`，所以按平台选择。
     *
     * 非 Windows 落到 [SecretCodec.Insecure] 而不是直接崩：
     * 让界面能打开、能看数据，只是「存凭据」这个动作不安全。
     * 这条会在启动日志里明确写出来，不静默。
     */
    private val secretCodec: SecretCodec by lazy {
        if (isWindows()) {
            DpapiSecretCodec()
        } else {
            System.err.println(
                "[LifeBoard] 非 Windows 平台：插件凭据将以明文保存（无 DPAPI 可用）。",
            )
            SecretCodec.Insecure
        }
    }

    /**
     * 插件仓库（M5）。
     *
     * 必须单例：它缓存了一次全量扫描的结果，也持有全部运行中的 QuickJS 运行时。
     * 每次 `create()` 新建一个会让界面拿到一份新的空缓存，而旧的那份运行时
     * 依然活着——表现是「装好的插件列表里看不见，内存却一直涨」。
     */
    private val pluginRepository: DesktopPluginRepository by lazy {
        DesktopPluginRepository(settings, credentials)
    }

    /**
     * 插件采集器（M5-5.8）。
     *
     * 与 [pluginInvokerInternal] 共用同一个 `rawRecordSink` 实例：
     * 两者都可能在同一次采集里写同一个指标，共用一个 sink 才能保证
     * 「组装 + 落库」走同一套归约规则。
     */
    private val pluginCollector: DesktopPluginCollector by lazy {
        DesktopPluginCollector(
            repository = pluginRepository,
            metricStore = stores.metricStore,
            credentialStoreFactory = { id -> credentialsStore(id) },
            rawRecordSink = pluginRawRecordSink,
        )
    }

    /** 裸记录落库的共用通道 */
    private val pluginRawRecordSink: DesktopRawRecordSink by lazy {
        DesktopRawRecordSink(stores) { pluginRepository }
    }

    /**
     * 插件业务调用（M5）。
     *
     * `collectRunner` 接的是插件采集器而非 `DesktopCollectionBackend`：
     * 后者只判定任务达成，不拉插件数据。两条链路互不替代——
     * 采集器负责「把插件数据写进库」，CollectionBackend 负责「按新数据重判任务」。
     */
    private val pluginInvokerInternal: PluginInvoker by lazy {
        DesktopPluginInvoker(
            repository = pluginRepository,
            metricStore = stores.metricStore,
            // 传单个插件：详情页「立即同步」与插件自己触发的同步都只针对自己
            collectRunner = { pluginId -> collectPlugin(pluginId) },
            hostContextFactory = { pluginId ->
                HostPluginContext(
                    pluginId = pluginId,
                    filesDirPathProvider = { pluginDataDir(pluginId).absolutePath },
                    credentialStoreFactory = { id -> credentialsStore(id) },
                    metricStore = stores.metricStore,
                    rawRecordSink = pluginRawRecordSink,
                )
            },
        )
    }

    fun create(): PlatformDependencies = PlatformDependencies(
        settings = settings,
        appInfo = DesktopAppInfo(),
        filePicker = SwingFilePicker(),
        notifier = notifierInternal,
        scheduler = schedulerInternal,
        remind = remindBackendInternal,
        rules = DesktopRulesBackend(stores, remindBackendInternal),
        // 与 dashboardStore 取同一个 stores：默认布局的写入要和界面读到的是同一份库
        dashboard = DesktopDashboardBackend(stores.dashboardStore),
        screenTime = screenTimeBackendInternal,
        sourceHealth = DesktopSourceHealthProbe,
        plugins = DesktopPluginHost(pluginRepository),
        pluginInvoker = pluginInvokerInternal,
        // 详情页的「全部数据」：纯查库，不碰 QuickJS 运行时
        pluginDetailStore = DesktopPluginDetailStore(stores.pluginStore) { pluginRepository },
        // 面板数据走真实数据库：两端共用同一套 SQLDelight 实现，
        // 只有「库文件在哪」是平台相关的。
        //
        // 额外覆盖 pluginMetrics：基类默认返回空表，那是 M3 时期
        // 「桌面端还没有插件运行时」的占位。M5 之后桌面端跑的是同一套
        // QuickJS，插件能采到数据，但这里若继续返回空表，
        // 「自定义数据源」页就永远列不出插件指标——数据在库里，
        // 界面上却找不到对应的源，症状与「插件没工作」完全一样。
        panelScope = object : DatabasePanelScope(stores) {
            override suspend fun pluginMetrics(): Map<String, PluginMetricSet> =
                withContext(Dispatchers.IO) {
                    // 只算已信任的：未信任的包不会被执行，列表里出现它的指标
                    // 等于让用户配一张永远不会有数据的卡片
                    pluginRepository.plugins()
                        .filter { it.trusted }
                        .associate { entry ->
                            entry.pluginId to PluginMetricSet(
                                displayName = entry.displayName,
                                metrics = entry.metrics.mapValues { (_, spec) ->
                                    PluginMetric(
                                        displayName = spec.displayName,
                                        bucket = spec.bucket,
                                        unit = spec.unit,
                                    )
                                },
                            )
                        }
                }

            /**
             * 任务保存后重排提醒。
             *
             * 基类默认是空实现，那是「桌面端没有系统闹钟」时期的占位。
             * 现在桌面端的提醒由进程内定时器承载，任务改了时段/档位却不重排，
             * 表现就是「改了提醒时间还是按老时间响」——而且不会报错。
             *
             * 整个重排而不是只排这一条，与 Android 端保持同一套语义：
             * 修改可能涉及时段与重复规则，单条重排会在跨任务的时间冲突上算错。
             */
            override suspend fun onTaskSaved(taskId: Long) {
                remindBackendInternal.rescheduleAll()
            }

            /** 任务删除后撤销它已登记的定时器（不撤回已弹出的托盘通知） */
            override suspend fun onTaskDeleted(taskId: Long) {
                remindBackendInternal.cancelTask(taskId)
            }
        },
        // 布局存储与面板数据同源，必须取同一个 stores 实例
        dashboardStore = stores.dashboardStore,
        // 设置页的三块平台能力：
        // 刷新周期：M4 已接上进程内周期调度，supportsAutoRefresh = true
        // 读屏采集：桌面没有读屏，available = false，开关整块不画
        collection = collectionBackendInternal,
        capture = CaptureBackend.Unavailable,
        appIcon = DesktopAppIconProvider,
        // 日志导出（M3-3.2d）：桌面端没有「分享」，直接弹保存对话框
        textExporter = SwingTextFileExporter,
        // 日志页与面板同源：同一个 stores 实例，不新开连接
        logStore = stores.logStore,
        // 规则编辑页要「选应用」（M4-4.6）：桌面端扫开始菜单快捷方式拿真实 exe 路径
        appCatalog = DesktopAppCatalog(),
    )

    /**
     * 应用启动时的初始化（M4-4.1）。
     *
     * 必须在界面构造**之前**调。要做两件事：
     *
     * 1. **重新登记定时器**——进程内的定时器上次退出时全丢了，
     *    不重排就等于「配好的提醒在重启后静默失效」。
     *    这不会报错，只会「该响的时候没响」，所以必须显式做
     * 2. 把已保存的自动刷新周期接上
     */
    suspend fun bootstrap() {
        // 日志通道最先起来：后面的初始化即使失败，原因也得留下，
        // 否则用户遇到的只有「打不开」，没有任何可查的线索
        DesktopAppLogger.init { runCatching { stores.logStore }.getOrNull() }
        remindBackendInternal.rescheduleAll()
        collectionBackendInternal.restoreOnStartup()
        startScreenTimeSampling()
    }

    /**
     * 起一个后台循环，定期采样前台窗口（M11）。
     *
     * ## 为什么必须在这里起，而不是等用户打开趋势页
     *
     * 屏幕时长的数据是**攒出来的**：只有一直有人在采样，
     * 「今天用了多久」才有意义。等到用户点开趋势页才开始记，
     * 记到的永远是从「他点开那一刻」起的时长，不是他今天的真实使用。
     *
     * ## 采样间隔为什么是 30 秒
     *
     * - 太密（1 秒）没必要：前台应用不会一秒一换，纯浪费 CPU
     * - 太稀（5 分钟）会把「切到浏览器看了 2 分钟再切回来」整段算给错的应用
     *
     * 30 秒的误差上限是「每次切换最多误记 30 秒」，对以分钟为单位的统计足够。
     *
     * ## 循环本身不抛异常
     *
     * `tick()` 内部已经把 Win32 调用包在 `runCatching` 里，
     * 这里再加一层 `runCatching` 是为了防「写库失败」——
     * 磁盘满、库被锁的瞬间不该让采样循环整个死掉。
     */
    private fun startScreenTimeSampling() {
        appScope.launch {
            while (isActive) {
                delay(SCREEN_TIME_SAMPLE_INTERVAL_MILLIS)
                runCatching { screenTimeBackendInternal.tick() }
            }
        }
    }

    /**
     * 退出清理。
     *
     * `scope.cancel()` 会把所有挂起的 `delay` 一起结束，
     * 不留下还在计时的协程让 JVM 无法退出。
     */
    fun shutdown() {
        schedulerInternal.shutdown()
        appScope.cancel()
    }

    /**
     * 凭据保管实例。
     *
     * 供插件宿主使用（M5 接入）。放在装配层而不是让调用方各 new 一个：
     * codec 内部可能持有系统密钥库的句柄，多份实例没有好处。
     */
    val credentials: CredentialVault by lazy {
        CredentialVault(stores.pluginStore, secretCodec)
    }

    /**
     * 插件私有数据目录。
     *
     * 与库文件同根，`~/.lifeboard/plugins/<id>/`。每个插件一个目录，
     * 卸载时只删包不删它——用户可能只是想重装，数据还在就有意义。
     */
    private fun pluginDataDir(pluginId: String): File {
        // pluginId 最终会拼进路径。打包层若被塞了 "../"，这里就要写到插件目录之外去。
        // 共享层构造 HostPluginContext 时也会校验，但那是下游；目录在更早的
        // 上下文构建里就被用到，早一步拦住代价更低
        require(HostPluginContext.isSafePluginId(pluginId)) { "非法 pluginId：$pluginId" }
        return File(System.getProperty("user.home"), ".lifeboard/plugins/$pluginId")
            .apply { mkdirs() }
    }

    /** 某插件的凭据存储。共享的 `CredentialVault` 按 pluginId 分槽，这里不需要再分文件 */
    private fun credentialsStore(pluginId: String): com.lifeboard.plugin.api.CredentialStore =
        DesktopCredentialStore(pluginId, credentials)

    /**
     * 跑一遍插件采集（所有已信任插件），并随后重判当日任务。
     *
     * 两步都要做：数据进了库，若不重判任务，用户的打卡状态不会更新，
     * 界面上看起来「同步了但没变化」。判定失败不影响采集结论的返回——
     * 数据已经落库是事实，判定只是派生结果，下次刷新还会再算。
     */
    private suspend fun collectAllPlugins(): Boolean =
        finishCollection(pluginCollector.collectAll())

    /** 只采一个插件（详情页「立即同步」），同样要重判任务 */
    private suspend fun collectPlugin(pluginId: String): Boolean =
        finishCollection(pluginCollector.collectOne(pluginId))

    private suspend fun finishCollection(collected: Boolean): Boolean {
        runCatching {
            ConditionEvaluator(stores.taskStore, stores.metricStore)
                .evaluateAll(Dates.todayString(), persist = true)
        }
        return collected
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** 统一的「这个能力在桌面端还没做」异常 */
    internal fun notImplemented(capability: String): Nothing =
        throw UnsupportedOperationException("桌面端尚未实现：$capability（计划在后续里程碑补齐）")
}

/**
 * 前台窗口采样间隔（M11）。
 *
 * 30 秒的依据写在 [DesktopPlatformDependencies.startScreenTimeSampling] 的注释里：
 * 密了浪费 CPU，稀了会把短暂切换整段算给错的应用。
 */
private const val SCREEN_TIME_SAMPLE_INTERVAL_MILLIS = 30_000L

/**
 * 桌面端的应用自身图标（M3-3.3b）。
 *
 * 图标取自 `desktop/src/desktopMain/resources/icon.png`（与 Android 的
 * `app/src/img/logo.png` 同一张图），由 Gradle 打进 jar 的根路径。
 *
 * 读不到时返回 null 而不是造一张假图标：调用方（「关于」页）会退化成
 * 首字母占位，这是**预期行为**——图标缺失是打包配置问题，
 * 用一个凭空画出来的方块掩盖它只会让人更难发现。
 */
internal object DesktopAppIconProvider : AppIconProvider {
    override suspend fun appIconPng(): ByteArray? = runCatching {
        DesktopAppIconProvider::class.java.getResourceAsStream("/icon.png")?.use { it.readBytes() }
    }.onFailure {
        com.lifeboard.shared.log.AppLogger.warn("读取桌面端图标失败：${it.message ?: it::class.simpleName}")
    }.getOrNull()
}

/**
 * [SettingsStore] 的桌面实现，落在 `java.util.prefs`。
 *
 * 选 `Preferences` 而不是自己写 properties 文件：
 * 它自带「按用户 + 按包名隔离」的路径规则，Windows 上落在注册表
 * `HKCU\Software\JavaSoft\Prefs`，不用操心多用户与权限问题。
 *
 * 注意它**没有 `apply()`**：`put*` 之后由 JVM 异步刷盘，
 * 这与 Android `SharedPreferences.apply()` 的语义一致，
 * 所以契约里刻意没有暴露 flush。
 */
private class PrefsSettingsStore : SettingsStore {

    private val prefs: Preferences = Preferences.userRoot().node("com/lifeboard/desktop")

    override fun getString(key: String, default: String): String = prefs.get(key, default)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    // Preferences 不接受 null 值，这里用空串表示「清空」而不是 remove，
    // 与 Android 侧 putString(key, "") 的行为对齐
    override fun putString(key: String, value: String) = prefs.put(key, value)

    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)

    override fun putLong(key: String, value: Long) = prefs.putLong(key, value)

    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)

    override fun remove(key: String) = prefs.remove(key)

    override fun contains(key: String): Boolean = prefs.get(key, null) != null
}

/**
 * [TextFileExporter] 的桌面实现：Swing 保存对话框，直接写到用户选的路径（M3-3.2d）。
 *
 * ## 与 Android 的差异
 *
 * Android 走「写缓存 + FileProvider + 分享面板」——用户拿到后多半是要发出去。
 * 桌面没有「分享」这一环：文件系统就是出口，用户选一个位置存下来，
 * 之后自己用邮件或聊天工具发。所以这里给的是保存对话框，不是「选个应用」。
 *
 * ## 必须在 EDT 上弹
 *
 * 与 [SwingFilePicker] 同理，`showSaveDialog` 在非 EDT 线程调用会画出空白窗口。
 * 对话框本身是模态阻塞的，所以 `suspend` 语义与契约一致：
 * 拿到返回值的时刻就是用户点完按钮之后。
 */
private object SwingTextFileExporter : TextFileExporter {

    override val available: Boolean = true

    override suspend fun export(fileName: String, mimeType: String, content: String): Boolean {
        val chooser = JFileChooser().apply {
            dialogTitle = "导出日志"
            // 预填文件名：用户点导出就是想存一份，不该再让他起名字
            selectedFile = File(defaultDirectory(), fileName)
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return false

        val target = chooser.selectedFile ?: return false
        return runCatching {
            // 对话框自己会问「是否覆盖」，这里直接写
            target.parentFile?.mkdirs()
            target.writeText(content)
            true
        }.getOrDefault(false)
    }

    /**
     * 默认落在用户主目录。
     *
     * 不用「上次选的目录」：那要额外存一个偏好键，而导出日志是低频操作，
     * 从主目录点两下就能到桌面/下载，不值得为此多一份状态要维护。
     */
    private fun defaultDirectory(): File =
        File(System.getProperty("user.home")).takeIf { it.isDirectory } ?: File(".")
}

/** [AppInfo] 的桌面实现，读 JVM 系统属性 */
private class DesktopAppInfo : AppInfo {

    override val packageId: String = "com.lifeboard.desktop"

    override val versionName: String
        get() = DesktopAppInfo::class.java.`package`?.implementationVersion ?: "dev"

    override val osDescription: String
        get() = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    override val deviceDescription: String
        get() = "${System.getProperty("os.arch")} · ${System.getProperty("java.version")}"

    override fun nowForExport(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    override val appLabel: String = "LifeBoard"

    // 桌面版没有「构建号」这个概念（版本名里已经带了构建信息），
    // 给 0 让界面省略括号部分，而不是编一个假数字
    override val buildNumber: Long = 0L

    override val minPlatform: String = "Windows 10"

    override val targetPlatform: String = "Windows 11"

    override val updatedAt: String = "未知"
}

/**
 * [FilePicker] 的桌面实现，用 Swing 的 [JFileChooser]。
 *
 * ## 必须在 EDT 上弹
 *
 * Swing 的对话框不是线程安全的。从 AWT 事件线程以外的地方调用
 * `showOpenDialog`，在 Windows 上轻则画出空白窗口，重则抛
 * `NullPointerException` 或直接把窗口卡死。Compose Desktop 的渲染线程
 * 虽然通常就是 EDT，但协程调度器并不保证这一点——`scope.launch` 的续体
 * 可能落在别的线程上，所以这里显式切回 EDT，不依赖调用方碰巧在主线程。
 *
 * ## 为什么用 invokeAndWait 而不是 invokeLater
 *
 * 契约是 `suspend`，调用方要拿到用户选择的结果才能继续。`invokeLater`
 * 会立即返回、拿不到值，等于把「等用户选完」这件事丢掉，只能靠回调绕回来，
 * 那样 `pickFile` 的返回值就失去意义了。
 *
 * `invokeAndWait` 会阻塞当前线程直到对话框关闭。这里阻塞是安全的：
 * 调用方在 IO 或 UI 协程里，不在渲染的关键路径上。
 */
private class SwingFilePicker : FilePicker {

    override suspend fun pickFile(mimeTypes: List<String>): PickedFile? {
        // 已经在 EDT 上时不能再 invokeAndWait——会自己等自己，永久死锁
        return if (SwingUtilities.isEventDispatchThread()) {
            showChooser(mimeTypes)
        } else {
            val holder = arrayOfNulls<PickedFile>(1)
            try {
                SwingUtilities.invokeAndWait { holder[0] = showChooser(mimeTypes) }
            } catch (e: InterruptedException) {
                // 线程被中断说明用户关了窗口或应用正在退出，当作「没选」处理
                Thread.currentThread().interrupt()
                return null
            } catch (e: InvocationTargetException) {
                // 对话框内部异常，交给上层当「没选」，不要升级成崩溃
                AppLogger.error("文件选择器异常", e.targetException ?: e)
                return null
            }
            holder[0]
        }
    }

    private fun showChooser(mimeTypes: List<String>): PickedFile? {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择插件文件"
            fileSelectionMode = JFileChooser.FILES_ONLY

            // 只筛 `.jsplugin`。
            //
            // 这里曾经写的是 apk/lbp/zip——那是 APK 插件轨时代的扩展名，
            // 轨已移除，留着只会让用户选到装不了的文件，再收到一句
            // 「桌面端仅支持 .jsplugin」。过滤器和安装器的判断必须一致。
            //
            // 契约传的 MIME 为空（调用方不做预筛），所以不看 mimeTypes；
            // 真传了也只可能是想放宽，这里保持「至少能看到 .jsplugin」。
            fileFilter = FileNameExtensionFilter("LifeBoard 插件包 (*.jsplugin)", "jsplugin")
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val file: File = chooser.selectedFile ?: return null
        return PickedFile(
            // 桌面端 uri 就是绝对路径，契约明确允许这种不透明标识
            uri = file.absolutePath,
            displayName = file.name,
            sizeBytes = file.length(),
        )
    }
}

// ───────────────────────── 以下为桌面端确实不具备的能力 ─────────────────────────

/**
 * 仪表盘默认布局。
 *
 * 这一条到 M3-3.2c 才有真实现：页签管理搬进共享界面后，桌面端也要能
 * 「新建页签 / 删除页签 / 恢复默认」，这些都会走到这里。之前挂的是
 * `UnsupportedDashboardBackend`，一点「+」就抛「未实现」。
 *
 * 逻辑与 Android 端完全一样，而且**本来就是同一份**——
 * [DashboardLayout.ensureInitialized] 就在 `commonMain` 里。
 * 这里只是把 Store 交给它，没有第二份默认布局清单。
 */
private class DesktopDashboardBackend(
    private val store: DashboardStore,
) : DashboardBackend {

    override suspend fun ensureDefaults() {
        DashboardLayout.ensureInitialized(store)
    }

    /**
     * 页签表是否为空。
     *
     * 判空口径必须与 [DashboardLayout.ensureInitialized] 一致（只看页签表），
     * 否则会出现「界面说空、恢复默认却什么都不做」的割裂。
     */
    override suspend fun isLayoutEmpty(): Boolean = store.getTabs().isEmpty()
}

/**
 * 健康度探测：桌面端没有通知监听与无障碍，没有任何源会因为「缺权限」而不健康。
 *
 * 返回空列表是**正确结果**而不是「未实现」——桌面端本来就该是「所有源都健康」。
 * 这与其余能力不同：那些是「还没做」，这个是「做了也是空的」。
 */
private object DesktopSourceHealthProbe : SourceHealthProbe {
    override suspend fun probeAll(): List<SourceHealthEntry> = emptyList()

    override fun openSettings(actionKey: String): Boolean = false
}

/**
 * 插件上报的原始条目落库。
 *
 * 这里要同时喂两处，缺一处界面就会缺一块：
 *
 * 1. **`metric` 表**——把条目归约成一个当天值，供仪表盘图表、每日记录使用。
 *    归约取「求和」：这是插件 SDK 里 `reportRawRecords` 的默认语义
 *    （例如步数分多段上报，一天的总量是各段之和）。
 * 2. **`plugin_raw_record` 表**——保留每一条明细，供详情页「全部数据」展示。
 *
 * 早先这里只做了第 1 步，症状是「采集日志写着写入 8 条、仪表盘也有数，
 * 但插件详情页的『全部数据』永远是空的」——因为详情页读的是
 * `plugin_raw_record`，而那张表一条都没写过。归约后的聚合值再拆不回明细，
 * 所以两步都必须做，不能靠事后补算。
 *
 * ## 替换粒度必须是「某天的某个指标」
 *
 * 与 Android 的 `RawRecordCollector` 保持一致：先删掉这一格再整批插入。
 * 粒度再粗（按插件、按天）会在「这次只采到步数」时把睡眠的历史抹掉，
 * 而小米健康上睡眠靠手环上报、经常只有步数，是常态而非异常。
 * 反过来说，收到一组新的就该整组换掉——留着重叠会让人看到两份互相矛盾的数据。
 */
internal class DesktopRawRecordSink(
    private val stores: DatabaseStores,
    private val repository: () -> DesktopPluginRepository,
) : com.lifeboard.shared.plugin.host.RawRecordSink {

    /**
     * 串行化写库。
     *
     * 一个插件的多个指标各自会调一次 `stage`，并发执行时 delete 与 insert
     * 会交错，可能删掉刚写进去的那一批。
     */
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun stage(
        pluginId: String,
        metricKey: String,
        records: List<com.lifeboard.plugin.api.RawRecord>,
        date: String,
    ) {
        if (records.isEmpty() || date.isBlank()) return

        // 先落明细：它不依赖插件是否加载成功，插件被卸载后历史仍该看得到
        writeDetailsLocked(pluginId, metricKey, records, date)

        // 再归约成当天值。量纲必须取插件自己声明的那个：
        // `metric` 表主键是 (sourceType, date, bucket)，猜会一路掉到 COUNT，
        // 与查询侧对不上，写进去的数据就永远读不回来
        val spec = repository().find(pluginId)?.metrics?.get(metricKey) ?: return
        runCatching {
            stores.metricStore.saveMetric(
                com.lifeboard.core.model.MetricRecord(
                    source = com.lifeboard.plugin.api.SourceRef.Plugin(
                        pluginId = pluginId,
                        metricKey = metricKey,
                        displayName = spec.displayName,
                        bucket = spec.bucket,
                    ),
                    date = date,
                    value = records.sumOf { it.value },
                ),
            )
        }.onFailure {
            com.lifeboard.shared.log.AppLogger.warn(
                "归约 $pluginId/$metricKey 的指标值失败：${it.message ?: it::class.simpleName}",
            )
        }
    }

    /**
     * 只写 `plugin_raw_record` 与查询痕迹，不碰 `metric`。
     *
     * 抽出来单独一条路径，是为了能脱离插件运行时验证它真的写了库
     * （构造一个完整的 `LoadedEntry` 要造 `SourcePlugin`，
     * 而这段逻辑与插件本身无关）。
     */
    internal suspend fun writeDetailsLocked(
        pluginId: String,
        metricKey: String,
        records: List<com.lifeboard.plugin.api.RawRecord>,
        date: String,
    ) {
        if (records.isEmpty() || date.isBlank()) return
        writeMutex.withLock {
            runCatching {
                stores.pluginStore.deleteRawRecords(pluginId, date, metricKey)
                stores.pluginStore.insertRawRecords(
                    records.mapIndexed { index, record ->
                        com.lifeboard.shared.repository.model.PluginRawRecord(
                            pluginId = pluginId,
                            date = date,
                            metricKey = metricKey,
                            timestamp = record.timestamp,
                            value = record.value,
                            detail = record.detail,
                            seq = index,
                        )
                    },
                )
                // 有条目说明这次确实查过，顺手补一条查询痕迹：
                // 详情页的时间段筛选靠它列出「查过但没有数据」的指标，
                // 漏记会让「有数据」的指标在某些日期看起来从未采集
                stores.pluginStore.recordMetricQueries(pluginId, date, listOf(metricKey))
            }.onFailure {
                com.lifeboard.shared.log.AppLogger.warn(
                    "写入 $pluginId/$metricKey 的原始条目失败：${it.message ?: it::class.simpleName}",
                )
            }
        }
    }

    override suspend fun markQueried(pluginId: String, metricKey: String, date: String) {
        // 只记痕迹不写条目：详情页据此把「查过但没数据」的指标也列出来显示「暂无」。
        // 与 Android 侧同名实现语义一致——早先这里是空实现，
        // 导致详情页连「这一天查过哪些指标」都不知道，只能显示空白
        if (pluginId.isBlank() || metricKey.isBlank() || date.isBlank()) return
        runCatching {
            stores.pluginStore.recordMetricQueries(pluginId, date, listOf(metricKey))
        }.onFailure {
            com.lifeboard.shared.log.AppLogger.warn(
                "记录 $pluginId/$metricKey 的查询痕迹失败：${it.message ?: it::class.simpleName}",
            )
        }
    }
}

/**
 * 某插件的凭据存储。
 *
 * 共享的 `CredentialVault` 已经按 pluginId 分好槽位，这里只做转发：
 * 插件触不到 pluginId，越权在类型层面不可表达。
 */
private class DesktopCredentialStore(
    private val pluginId: String,
    private val vault: CredentialVault,
) : com.lifeboard.plugin.api.CredentialStore {

    override suspend fun get(slot: String): String? = vault.read(pluginId, slot)

    override suspend fun put(slot: String, value: String) {
        vault.write(pluginId, slot, value)
    }

    override suspend fun remove(slot: String) {
        vault.clearSlot(pluginId, slot)
    }

    override suspend fun clear() {
        vault.clearPlugin(pluginId)
    }
}

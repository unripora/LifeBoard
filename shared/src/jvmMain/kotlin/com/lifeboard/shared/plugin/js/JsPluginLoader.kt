package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.SourcePlugin
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.PluginIdentity
import com.lifeboard.shared.plugin.PluginPackageInfo
import com.lifeboard.shared.plugin.PluginTrust
import com.lifeboard.shared.plugin.PluginTrustStore
import com.lifeboard.shared.plugin.TrustState
import com.lifeboard.shared.plugin.TrustVerdict
import com.lifeboard.shared.plugin.fs.PluginFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 扫描并加载 `.jsplugin` 包。
 *
 * ## 加载流程
 *
 * ```
 * 插件根目录下的 .jsplugin 包
 *   → 解 ZIP 到 {根}/{名}/
 *   → 读 manifest.json，校验必需字段
 *   → 计算内容哈希（作为签名指纹）
 *   → 在专用单线程上创建 JsRuntime、装桥接、执行入口模块
 *   → 包装成 JsSourcePlugin + LoadedPlugin
 * ```
 *
 * ## 隔离程度
 *
 * JS 加载器用 QuickJS 沙箱，脚本只能通过宿主显式注入的 `ctx.*` 触达外部能力，
 * 拿不到文件系统、网络或平台 API。
 *
 * ## 失败处理
 *
 * 单个包失败不影响其它包：解压、manifest、模块执行任一环节出错都记进
 * [brokenPlugins]，UI 据此展示具体原因。绝不因为一个坏包让整个列表为空。
 *
 * ## 为什么依赖 [PluginFileSystem] 而不是 `Context`
 *
 * 平台差异只有「插件根目录在哪」这一件事。把它收敛成一个接口后，
 * 加载、校验、信任判定的主体逻辑两端共用一份——这正是「一份运行时」的落点。
 */
class JsPluginLoader(
    private val fileSystem: PluginFileSystem,
    private val trustStore: PluginTrustStore,
    /**
     * 是否启用「扫描只登记元数据、首次业务调用才创建运行时」。
     *
     * 默认 **关闭**：关闭时行为与改造前完全一致（扫描即实例化）。这是
     * 刻意保守的默认——延迟实例化会改变所有调用方对「拿到实例」时序的假设，
     * 必须在专项测试通过后由各端显式开启。
     */
    @Volatile var lazyInstantiation: Boolean = false,
    /**
     * 可注入的时钟，仅用于测试空闲判定。
     *
     * 生产走 [System.currentTimeMillis]；若不给测试注入能力，验证"到点回收"
     * 就只能真的等 60 秒——那种测试没人会跑，等于没测。
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * HTTP 异步 token 的全局序号。
     *
     * 必须是进程级唯一：每个插件运行时各自持有一张 token 表，
     * 但 `resolvePromise` 的 token 会跨运行时重载复用同一个字符串空间，
     * 一旦重复就会把结果投给错误的 Promise。
     */
    private val httpTokenSeq = AtomicLong(0)

    /**
     * WebSocket 连接 id / 异步 token 的全局序号。
     *
     * 与 [httpTokenSeq] 分开，避免两类 token 落在同一串空间里，
     * 排查日志时分不清是哪个桥发起的。
     */
    private val wsTokenSeq = AtomicLong(0)

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var broken: List<BrokenJsPlugin> = emptyList()

    /**
     * 延迟实例化时保存的「启动配方」：包已校验通过，但运行时还没建。
     *
     * 键是插件 id。首次业务调用时用它在专用线程上创建运行时并执行入口脚本。
     * 非延迟模式下这个表始终为空。
     */
    @Volatile
    private var pendingLaunches: Map<String, PendingLaunch> = emptyMap()

    /**
     * 延迟插件对外的委托外壳。
     *
     * 列表与调用方拿到的是这些对象，它们在首次业务调用时才真正创建运行时。
     * 已启动的插件会从这张表移除并进入 [entries]（或管理器）。
     */
    @Volatile
    private var deferredEntries: Map<String, JsLoadedPlugin> = emptyMap()

    /**
     * 本轮扫描开始时的外壳快照。
     *
     * `scan()` 会重建外壳表，但扫描期间仍可能有调用方（详情页刷新、会话端点）
     * 进来取外壳。没有这份快照，它们拿到的对象会在扫描收尾时被替换，
     * 手里那个变成孤儿：监听注册在旧对象上，而列表/后续调用走新对象。
     */
    @Volatile
    private var pendingShellCache: Map<String, JsLoadedPlugin> = emptyMap()

    /** 本轮扫描结束时，把权威外壳固定下来，后续 `find()`/`loadPlugins()` 都以它为准 */
    private fun commitShellCache() {
        pendingShellCache = deferredEntries
    }

    /**
     * 启动配方（manifest + 模块源码 + 内容哈希），**按已安装插件长期保留**。
     *
     * 与 [pendingLaunches] 的区别：那个只表示"尚未启动"，启动后即移除；
     * 而运行时被空闲回收后需要配方来重建，所以配方必须留到插件被卸载/更新为止。
     */
    @Volatile
    private var launchRecipes: Map<String, PendingLaunch> = emptyMap()

    /**
     * 委托外壳登记的会话监听器，等真实实例出现时补挂。
     *
     * 外壳本身不是插件实例，不能把监听挂到自己身上；而卸载钩子期间不允许
     * 读写上下文，所以这里只保存在加载器里，由 `withStarted` 在拉起后统一转交。
     */
    private val pendingSessionListeners =
        java.util.concurrent.ConcurrentHashMap<String, (com.lifeboard.plugin.api.SessionUiEvent) -> Unit>()

    /** 外壳登记监听（未启动时）；传 null 表示解绑 */
    fun rememberSessionListener(
        pluginId: String,
        listener: ((com.lifeboard.plugin.api.SessionUiEvent) -> Unit)?,
    ) {
        if (listener == null) pendingSessionListeners.remove(pluginId)
        else pendingSessionListeners[pluginId] = listener
    }

    /** 当前登记的监听器（供外壳在实例重建后补挂） */
    fun sessionListenerOf(pluginId: String): ((com.lifeboard.plugin.api.SessionUiEvent) -> Unit)? =
        pendingSessionListeners[pluginId]

    /**
     * 一次启动配方（内容在扫描期就已校验通过）。
     *
     * **刻意不持有模块源码**：配方要按「已安装」长期保留，若把源码一起存着，
     * 50 个插件会常驻数 MB —— 与「按需加载省内存」的目标自相矛盾。
     * 重建时按 [archiveName] 重新读一次包（几十毫秒，且只在回收后重新打开时发生）。
     */
    private class PendingLaunch(
        val archiveName: String,
        val manifest: JsPluginManifest,
        val contentHash: String,
    )

    @Volatile
    private var pending: List<PendingJsPlugin> = emptyList()

    /**
     * 已安装的全部插件，含「已启动」与「待启动」。
     *
     * 待启动项是 [DeferredSourcePlugin]，首次业务调用时才创建运行时。
     */
    fun loadedPlugins(): List<JsLoadedPlugin> {
        val real = entries.map { it.plugin } + lazyRuntimeManager.snapshot().map { it.plugin }
        val realIds = real.mapTo(mutableSetOf()) { it.plugin.id }
        // 已安装但当前没有运行时的（从未启动 / 被回收）：回退成委托外壳，
        // 否则用户会看到"装好的插件突然没了"
        val shells = launchRecipes.values
            .filter { it.manifest.id !in realIds }
            .map { shellFor(it) }
        return real + shells
    }

    fun brokenPlugins(): List<BrokenJsPlugin> = broken

    /** 包已落盘、但还没得到用户信任（或因内容哈希缺失无法建立信任）的插件 */
    fun pendingPlugins(): List<PendingJsPlugin> = pending

    /**
     * 按 id 取对外句柄。
     *
     * **优先返回委托外壳**：外壳每次调用现申请租约，而 [Entry] 里的真实实例
     * 是没有租约保护的裸引用。若这里把真实实例交出去，调用方（列表、详情页、
     * 会话端点）就会绕过租约，空闲回收可以在会话进行中把运行时关掉。
     * 只有内部路径（[realEntry]）才拿真实实例。
     */
    fun find(pluginId: String): JsLoadedPlugin? =
        deferredEntries[pluginId]
            ?: launchRecipes[pluginId]?.let { shellFor(it) }
            ?: entries.firstOrNull { it.plugin.plugin.id == pluginId }?.plugin
            ?: lazyRuntimeManager.snapshot().firstOrNull { it.plugin.plugin.id == pluginId }?.plugin

    /** 取得（必要时创建）某配方的委托外壳，供列表与查找回退使用 */
    private fun shellFor(recipe: PendingLaunch): JsLoadedPlugin = stableShell(recipe)

    /**
     * 复用一个**已存在**的外壳，或按配方新建。
     *
     * 外壳是对外的稳定身份：详情页、会话控制器都长期持有它。
     * 重扫时必须按 id 复用同一个对象，否则「手里的那个」会指向一个
     * 再也不会被加载流程更新的旧外壳——会话监听、租约都挂在它身上。
     */
    private fun stableShell(recipe: PendingLaunch): JsLoadedPlugin {
        val id = recipe.manifest.id
        // 权威来源是**扫描开始时固定的那份表**，其次才是当前表。
        // 扫描过程中会重建 deferredEntries（含「清掉失去信任的」），
        // 若只看当前表，期间新建的外壳会在收尾时被丢弃，调用方手里那个就成孤儿。
        val cached = pendingShellCache[id] ?: deferredEntries[id]
        if (cached != null) {
            // 始终把权威对象写回当前表，保证 find()/loadPlugins() 与调用方拿到同一个
            if (deferredEntries[id] !== cached) deferredEntries = deferredEntries + (id to cached)
            return cached
        }
        val shell = JsLoadedPlugin(
            packageInfo = recipe.manifest.toPackageInfo(recipe.contentHash),
            archiveName = recipe.archiveName,
            plugin = DeferredSourcePlugin(
                id = id,
                displayName = recipe.manifest.name,
                credentialSpec = recipe.manifest.credentialSpec,
                metrics = recipe.manifest.metrics,
                loader = this,
            ),
        )
        deferredEntries = deferredEntries + (id to shell)
        pendingShellCache = pendingShellCache + (id to shell)
        return shell
    }

    /**
     * 延迟实例化模式下「已安装、未启动」的插件元数据。
     *
     * 这些包已通过信任与内容校验，只差执行入口脚本。界面可以据此列出插件，
     * 而**不需要**唤醒运行时——这正是按需加载要守住的那条线。
     */
    fun deferredPlugins(): List<DeferredPlugin> =
        launchRecipes.values.filter { realEntry(it.manifest.id) == null }.map {
            DeferredPlugin(
                pluginId = it.manifest.id,
                displayName = it.manifest.name,
                archiveName = it.archiveName,
                packageInfo = it.manifest.toPackageInfo(it.contentHash),
            )
        }

    /**
     * 一个已安装但尚未启动的插件。
     *
     * 只包含列表展示所需的静态元数据；业务能力（登录、采集、会话）
     * 必须先通过 [ensureStarted] 获得实例。
     */
    data class DeferredPlugin(
        val pluginId: String,
        val displayName: String,
        val archiveName: String,
        val packageInfo: PluginPackageInfo,
    )

    /**
     * 延迟实例化模式：插件是否已安装但运行时尚未创建。
     *
     * 界面据此知道「有这个插件，但还没跑起来」，而不必（也不应）触发加载。
     */
    fun hasPendingLaunch(pluginId: String): Boolean =
        launchRecipes.containsKey(pluginId) && realEntry(pluginId) == null

    /**
     * 按需创建运行时。已实例化时直接返回现有条目。
     *
     * 只在 [lazyInstantiation] 打开时有实际作用；关闭时等价于 [find]。
     * 创建过程可能较慢（解包内容已在扫描期读入，这里主要是 QuickJS 初始化 +
     * 执行入口脚本），调用方应在 IO 线程上调用。
     *
     * @return 成功返回可用的条目；创建失败返回 `null` 并把原因记进日志
     */
    fun ensureStarted(pluginId: String): JsLoadedPlugin? {
        // 只能查真实条目：find() 会返回委托外壳，而外壳的 startProvider 正是
        // 调用本方法，用它做判断会变成「外壳启动外壳」的自递归
        realEntry(pluginId)?.let { return it.plugin }
        if (!lazyInstantiation) return null
        if (launchRecipes[pluginId] == null) return null

        synchronized(instantiateLock) {
            realEntry(pluginId)?.let { return it.plugin }
            // 通过管理器创建：后续调用才能拿到租约、才可能被按需回收
            return when (val result =
                lazyRuntimeManager.acquire(
                        pluginId,
                        resolvePolicy(pluginId, policyFor(pluginId)),
                        revocable = revocableFor(pluginId),
                    )) {
                is PluginRuntimeManager.AcquireResult.Ready -> {
                    val entry = result.lease.withRuntime { it as Entry }
                    // ensureStarted 只负责「让它起来」，租约在这里立即归还；
                    // 真正的调用期保活由 withStarted 负责
                    result.lease.release()
                    // 启动成功**不删外壳**：它是对外的稳定身份，回收/重建后仍要复用。
                    // 只清「待启动」标记，配方与外壳都保留。
                    pendingLaunches = pendingLaunches - pluginId
                    if (entry != null) {
                        AppLogger.info("JS 插件 $pluginId 已按需启动")
                        entry.plugin
                    } else null
                }
                is PluginRuntimeManager.AcquireResult.Unavailable -> {
                    AppLogger.error("JS 插件 $pluginId 按需启动失败：${result.reason}")
                    null
                }
            }
        }
    }

    /** 只查已真实启动的条目，不含委托外壳 */
    private fun realEntry(pluginId: String): Entry? =
        entries.firstOrNull { it.plugin.plugin.id == pluginId }
            ?: lazyRuntimeManager.snapshot().firstOrNull { it.plugin.plugin.id == pluginId }

    /**
     * 只取**真实**插件实例，绝不回退到委托外壳。
     *
     * 委托外壳的 `setSessionUiListener` 需要「挂到真实实例上」，
     * 若这里像 [find] 那样回退成外壳，就会变成「外壳挂外壳」的无限递归。
     */
    fun realPlugin(pluginId: String): SourcePlugin? = realEntry(pluginId)?.plugin?.plugin

    /**
     * 撤销一条启动配方，并清掉对应的外壳与策略。
     *
     * 用在「重读发现包变了/失去信任」这类不该继续启动的场景：
     * 保留配方就等于保留一条绕过信任确认的启动路径。
     */
    private fun invalidateRecipe(pluginId: String, archiveName: String, reason: String) {
        synchronized(instantiateLock) {
            launchRecipes = launchRecipes - pluginId
            pendingLaunches = pendingLaunches - pluginId
            deferredEntries = deferredEntries - pluginId
            pendingShellCache = pendingShellCache - pluginId
            pendingSessionListeners.remove(pluginId)
        }
        // 包装成坏包提示：用户需要知道「为什么用不了」，而不是静默消失
        synchronized(instantiateLock) {
            broken = broken.filterNot { it.archiveName == archiveName } +
                BrokenJsPlugin(archiveName, reason)
        }
        AppLogger.warn("JS 插件 $pluginId 的启动配方已撤销：$reason")
    }

    /** 保护「检查后创建」的原子性，避免并发申请重复执行入口脚本 */
    private val instantiateLock = Any()

    /**
     * 延迟实例化模式的运行时管理器。
     *
     * 只在 [lazyInstantiation] 打开时承载已启动的实例：它负责租约、代际与
     * 空闲回收。**自动回收总开关默认关闭**——先让租约接线跑通，再单独开启。
     *
     * eager 模式不使用它，保持与改造前完全一致的行为，降低回归风险。
     */
    private val lazyRuntimeManager: PluginRuntimeManager<Entry> = PluginRuntimeManager(
        create = { key ->
            // 配方**必须长期保留**：运行时被空闲回收后要能重建。
            // 早先启动成功就删配方，导致"回收后再调用直接失败"——真实环境验证抓到的 bug
            val launch = launchRecipes[key]
                ?: error("插件 $key 没有可用的启动配方")
            // 重读包：配方只存元数据，源码不常驻内存。
            // 但磁盘上的包随时可能被替换：必须重算内容哈希并复核信任，
            // 否则「装好之后再改包」就能绕开信任确认直接执行。
            val files = runCatching { fileSystem.readArchive(launch.archiveName) }
                .getOrElse { error("无法读取插件包 ${launch.archiveName}：${it.message}") }
            val actualHash = JsPluginManifest.computeContentHash(files)
            if (actualHash != launch.contentHash) {
                invalidateRecipe(key, launch.archiveName, "包内容在启动前发生了变化，请重新确认信任")
                error("插件包内容已变化，请重新确认信任")
            }
            // manifest 不参与内容哈希（只改描述不该要求重新信任），
            // 但入口、指标、凭据槽这些运行元数据都来自它：
            // 启动前必须逐字段比对，否则「换一个 manifest 保留 main.js」就能改掉运行行为。
            val manifestBytes = files[JsPluginManifest.MANIFEST_NAME]
                ?: run {
                    invalidateRecipe(key, launch.archiveName, "插件包缺少 manifest.json")
                    error("插件包缺少 manifest.json")
                }
            val actualManifest = when (
                val parsed = JsPluginManifest.parseDetailed(manifestBytes.toString(Charsets.UTF_8))
            ) {
                is ParseResult.Success -> parsed.manifest
                is ParseResult.Failure -> {
                    invalidateRecipe(key, launch.archiveName, "manifest 解析失败：${parsed.reason}")
                    error("manifest 解析失败：${parsed.reason}")
                }
            }
            if (actualManifest != launch.manifest) {
                invalidateRecipe(key, launch.archiveName, "manifest 在启动前发生了变化，请重新确认信任")
                error("插件 manifest 已变化，请重新确认信任")
            }
            val verdict = PluginTrust.evaluate(
                launch.manifest.toPackageInfo(actualHash),
                trustStore.load(),
            )
            if (verdict !is TrustVerdict.Trusted) {
                invalidateRecipe(key, launch.archiveName, "插件已不再受信任")
                error("插件已不再受信任")
            }
            when (val result = instantiate(
                archiveName = launch.archiveName,
                manifest = launch.manifest,
                files = files,
                contentHash = launch.contentHash,
            )) {
                is LoadResult.Success -> result.entry
                is LoadResult.Failure -> error(result.broken.reason)
                else -> error("插件 $key 启动状态异常")
            }
        },
        close = { closeEntry(it) },
        autoEvictEnabled = false,
        nowMs = nowMs,
    ).apply {
        // 空闲回收允许尽力清理；强制停机（撤权/清除）不执行插件代码
        idleClose = { closeEntry(it, StopReason.IDLE) }
    }

    /**
     * 是否允许被自动回收。
     *
     * **只有显式声明了 `lifecycle` 的包才允许**：旧包没声明，说明它可能
     * 依赖"宿主不会卸载我"这个假设，自动回收会让它凭空丢状态。
     * 这是设计里"旧包兼容保活"的落点。
     */
    private fun revocableFor(pluginId: String): Boolean =
        launchRecipes[pluginId]?.manifest?.lifecycleDeclared == true

    /** 某插件生效的策略；延迟模式目前都按 manifest 声明，不做常驻名额调整 */
    private fun policyFor(pluginId: String): PluginRuntimePolicy =
        policies[pluginId] ?: PluginRuntimePolicy.of(PluginLifecycle.DEFAULT)

    /** 插件 id → 生效策略；扫描时登记，启动后仍要保留（配方会被清掉） */
    @Volatile
    private var policies: Map<String, PluginRuntimePolicy> = emptyMap()

    /** 当前占用常驻名额的插件 id（声明 resident 且已获准的） */
    private val residentGrants = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 因连续同步预算超限被停用的插件。
     *
     * **跨重扫保留**：一个写死循环的插件不应该因为用户切了一次页签就复活，
     * 必须由用户显式重新启用或安装新版本才重试。
     */
    private val disabledPlugins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 连续同步预算超限达到此次数即停用 */
    private val syncFaultLimit = 3

    /** 常驻名额上限；超出时按 LRU 降级空闲者，无候选则拒绝新增 */
    @Volatile
    private var residentCapacity: Int = 2

    private val residentLock = Any()

    /**
     * 计算某插件的生效策略（含常驻准入）。
     *
     * 规则：
     * - 非 resident 声明：直接按声明执行
     * - resident 且已获准：保持常驻
     * - resident 且未获准、名额未满：授予
     * - resident 且名额已满：降级**空闲**的常驻者；全都忙则本次按需运行并给出原因
     *
     * **绝不为了腾名额去关一个正在使用的实例**——那正是"LRU 断开活跃会话"的风险。
     */
    private fun resolvePolicy(pluginId: String, declared: PluginRuntimePolicy): PluginRuntimePolicy {
        if (declared.declared != PluginLifecycle.RESIDENT) {
            residentGrants.remove(pluginId)
            return declared
        }
        synchronized(residentLock) {
            if (pluginId in residentGrants) {
                return declared.copy(effective = PluginLifecycle.RESIDENT)
            }
            if (residentGrants.size < residentCapacity) {
                residentGrants += pluginId
                return declared.copy(effective = PluginLifecycle.RESIDENT)
            }
            // 名额已满：优先降级最久未用且空闲的常驻实例
            val victim = lazyRuntimeManager.residentIdleLru()
                ?.takeIf { it != pluginId && it in residentGrants }

            if (victim != null) {
                residentGrants -= victim
                val degraded = PluginRuntimePolicy.of(
                    declared = PluginLifecycle.RESIDENT,
                    effective = PluginLifecycle.ON_DEMAND,
                    degradeReason = "常驻名额已满，该插件已改为按需加载",
                )
                lazyRuntimeManager.updatePolicy(victim, degraded)
                policies = policies + (victim to degraded)
                residentGrants += pluginId
                AppLogger.info("常驻名额已满：$victim 降级为按需，$pluginId 获准常驻")
                return declared.copy(effective = PluginLifecycle.RESIDENT)
            }

            // 所有常驻者都在使用：不抢占，本次按需运行并说明原因
            val reason = "常驻名额已满（$residentCapacity 个均在使用中），本插件暂时按需加载"
            AppLogger.warn("$pluginId 未获常驻名额：$reason")
            return declared.copy(effective = PluginLifecycle.ON_DEMAND, degradeReason = reason)
        }
    }

    /** 设置常驻名额上限（默认 2）；调小不会立刻关闭实例，只影响后续准入 */
    fun setResidentCapacity(capacity: Int) {
        residentCapacity = capacity.coerceAtLeast(0)
    }

    /**
     * 启用/停用空闲回收。
     *
     * 默认**关闭**：回收会真正关闭运行时，必须在专项测试与真机验证通过后
     * 由宿主显式开启。启用后会启动周期清扫。
     */
    fun setEvictionEnabled(enabled: Boolean) {
        lazyRuntimeManager.autoEvictEnabled = enabled
        if (enabled) lazyRuntimeManager.startSweeper() else lazyRuntimeManager.stopSweeper()
        AppLogger.info("插件空闲回收已${if (enabled) "启用" else "停用"}")
    }

    /** 立即执行一次清扫；返回实际回收的实例数，供测试与手动诊断 */
    fun evictIdleNow(): Int = lazyRuntimeManager.evictIdle()

    /**
     * 当前**真实存活**的运行时数量。
     *
     * 必须同时统计两条路径：eager 模式实例化在 [entries]，延迟模式实例化在
     * 管理器里。只数其中一边会得出"0 个运行时"的假象——这正是本项目的
     * 资源基线要避免的错误度量。
     */
    fun liveRuntimeCount(): Int = entries.size + lazyRuntimeManager.liveCount()

    /**
     * 在租约保护下执行一次插件调用。
     *
     * 这是 4.8 的落点：业务入口不再直接持有插件实例，而是「申请租约 → 执行 →
     * 归还」。租约期间即使空闲阈值到期也不会被回收，避免调用中被判空闲。
     *
     * eager 模式下没有拉起管理器的必要，直接透传；lazy 模式才走租约。
     *
     * @return 插件已不可用时返回 `null`，调用方应给出「插件加载失败」而非「没有数据」
     */
    /** 一次「在租约保护下调用」的结果，带明确原因 */
    sealed interface StartedResult<out R> {
        data class Ok<R>(val value: R) : StartedResult<R>
        /** 因连续执行超限被停用 */
        data class Disabled(val reason: String) : StartedResult<Nothing>
        /** 未能启动：包读取失败、初始化失败或预算拒绝 */
        data class Unavailable(val reason: String) : StartedResult<Nothing>
    }

    /**
     * 在租约保护下执行一次插件调用，并给出**可区分的原因**。
     *
     * 与 [withStarted] 的区别只是返回值：那个返回 `null` 时调用方无法区分
     * 「被停用」「加载失败」「预算拒绝」，界面上只能写一句"不可用"。
     *
     * @return [StartedResult.Ok] 或带原因的失败分支
     */
    suspend fun <R> withStartedDetailed(
        pluginId: String,
        block: suspend (SourcePlugin) -> R,
    ): StartedResult<R> {
        // 已停用（连续同步超限）：不再拉起运行时
        if (pluginId in disabledPlugins) {
            return StartedResult.Disabled("插件因连续执行超限已停用，请重新安装或显式启用")
        }
        if (!lazyInstantiation) {
            val entry = realEntry(pluginId) ?: return StartedResult.Unavailable("插件未加载")
            return StartedResult.Ok(block(entry.plugin.plugin).also { checkFaults(pluginId, entry) })
        }
        // 已由管理器承载的实例：走租约
        val result = lazyRuntimeManager.acquire(
                        pluginId,
                        resolvePolicy(pluginId, policyFor(pluginId)),
                        revocable = revocableFor(pluginId),
                    )
        return when (result) {
            is PluginRuntimeManager.AcquireResult.Ready -> {
                try {
                    val entry = result.lease.withRuntime { it as Entry }
                        ?: return StartedResult.Unavailable("运行时已关闭，正在重建")
                    // 首次经租约拉起（或回收后重建）：外壳保留，仍是对外稳定身份。
                    // 监听器在**每次**实例就位时都要重新挂上：回收会关掉旧运行时，
                    // 新实例是干净的，不补挂就等于会话再也收不到任何推送。
                    pendingSessionListeners[pluginId]
                        ?.let { listener -> runCatching { entry.plugin.plugin.setSessionUiListener(listener) } }
                    StartedResult.Ok(block(entry.plugin.plugin).also { checkFaults(pluginId, entry) })
                } finally {
                    result.lease.release()
                }
            }
            is PluginRuntimeManager.AcquireResult.Unavailable -> {
                AppLogger.warn("插件 $pluginId 暂时不可用：${result.reason}")
                StartedResult.Unavailable(result.reason)
            }
        }
    }

    /**
     * [withStartedDetailed] 的简化版：只关心成功与否。
     *
     * 需要向用户解释"为什么不可用"时请用 detailed 版本。
     */
    suspend fun <R> withStarted(pluginId: String, block: suspend (SourcePlugin) -> R): R? =
        (withStartedDetailed(pluginId, block) as? StartedResult.Ok<R>)?.value

    /**
     * 取得一条**跨调用持有**的运行时租约，用于「会话存续期间」这类长时间使用。
     *
     * 与 [withStarted] 的区别：那个在调用返回时就归还，只保护一次调用；
     * 会话要跨越多次输入、持续几分钟到几小时，期间若被空闲回收，
     * 监听注册、订阅和消息缓冲会一起丢掉。所以由调用方显式申请、显式归还。
     *
     * eager 模式没有管理器可走，返回 `null`：那里没有自动回收，也不需要保活。
     */
    fun acquireSessionLease(pluginId: String): SessionLease? {
        if (!lazyInstantiation || pluginId in disabledPlugins) return null
        if (launchRecipes[pluginId] == null) return null
        return when (val result = lazyRuntimeManager.acquire(
            pluginId,
            resolvePolicy(pluginId, policyFor(pluginId)),
            revocable = revocableFor(pluginId),
        )) {
            is PluginRuntimeManager.AcquireResult.Ready -> SessionLease(result.lease, pluginId)
            is PluginRuntimeManager.AcquireResult.Unavailable -> {
                AppLogger.warn("插件 $pluginId 无法取得会话租约：${result.reason}")
                null
            }
        }
    }

    /**
     * 会话租约。
     *
     * 让宿主在会话存续期间贴着真实实例：既能压住空闲回收，也能在实例被
     * 强制停机（撤权/更新）后立刻察觉（[isAlive] 变 false），而不是继续拿着
     * 一个已关闭的运行时空转。
     */
    class SessionLease internal constructor(
        private val lease: Lease,
        val pluginId: String,
    ) {
        @Volatile
        private var released = false

        val isReleased: Boolean get() = released

        /** 租约对应的实例是否仍然存活且与本租约同代际 */
        fun isAlive(): Boolean =
            !released && lease.withRuntime { it } != null

        /** 在租约保护下执行；实例已被强制停机时返回 null */
        fun <R> withPlugin(block: (SourcePlugin) -> R): R? {
            if (released) return null
            return lease.withRuntime { entry ->
                @Suppress("UNCHECKED_CAST")
                block((entry as Entry).plugin.plugin)
            }
        }

        /** 挂起版本：插件调用要等网络往返，必须能让出线程 */
        suspend fun <R> withPluginSuspend(block: suspend (SourcePlugin) -> R): R? {
            if (released) return null
            val plugin = lease.withRuntime { entry ->
                @Suppress("UNCHECKED_CAST")
                (entry as Entry).plugin.plugin
            } ?: return null
            return block(plugin)
        }

        fun release() {
            if (released) return
            released = true
            lease.release()
        }
    }
    /** 连续同步超限达到阈值：停用并回收，避免反复打断、反复重建 */
    private fun checkFaults(pluginId: String, entry: Entry) {
        if (entry.runtime.syncBudgetExceededCount() < syncFaultLimit) return
        // 状态改动放进锁：与 scan/ensureStarted 并发时不能丢更新。
        // 停机（可能较慢）放到锁外，避免长时间持锁
        val firstFault = synchronized(instantiateLock) {
            if (!disabledPlugins.add(pluginId)) {
                false
            } else {
                entries = entries.filterNot { it.plugin.plugin.id == pluginId }
                pendingLaunches = pendingLaunches - pluginId
                deferredEntries = deferredEntries - pluginId
                pendingShellCache = pendingShellCache - pluginId
                true
            }
        }
        if (!firstFault) return
        AppLogger.error(
            "插件 $pluginId 连续 $syncFaultLimit 次同步预算超限，已停用；" +
                "重新安装或显式启用后才会再尝试"
        )
        runCatching { lazyRuntimeManager.shutdown(pluginId) }
    }

    /** 是否因连续故障被停用，供界面提示 */
    fun isDisabled(pluginId: String): Boolean = pluginId in disabledPlugins

    /** 显式重新启用（用户操作或安装新版本后调用） */
    fun enablePlugin(pluginId: String) {
        disabledPlugins.remove(pluginId)
        AppLogger.info("插件 $pluginId 已重新启用")
    }

    /**
     * 删除一个插件包及其解压目录。
     *
     * **不删插件私有数据**（凭据、缓存）：用户可能只是换版本重装，重装后
     * 不该要求重新登录。
     * 调用方负责在删除后重新 [scan]，否则内存里还留着已关闭的运行时。
     *
     * @return 是否真的删掉了包文件
     */
    fun remove(archiveName: String): Boolean {
        val removed = fileSystem.deleteArchive(archiveName)
        // 解压目录名就是去掉后缀的包名，跟着一起清
        fileSystem.deleteDir(fileSystem.pluginsRoot() + File.separator + archiveName.substringBeforeLast('.'))
        return removed
    }

    /**
     * 全量扫描并重建插件状态。
     *
     * 会先释放所有已加载的运行时再重新加载——热重载语义干净，
     * 也不需要处理「旧插件残留的 JS 全局状态」。
     *
     * 与 APK 加载器一致，**未信任的包不会被执行**：JS 沙箱虽然拿不到系统能力，
     * 但脚本一旦跑起来就能通过宿主注入的 `ctx.http` 外发数据，
     * 因此「先信任、后执行」这条边界必须在这一层守住，不能指望调用方自觉。
     */
    fun scan(): List<JsLoadedPlugin> {
        // 旧条目先留着，供本轮复用；扫完再把没被复用的关掉。
        //
        // 早先这里是 `closeAll()` 一把清空，等于「每次重扫都把所有运行时重建」。
        // 而重扫触发点很多（安装成功后、目录监听发现变化、撤销信任……），
        // 详情页手里那份 JsSourcePlugin 指向的运行时会被下一次重扫关掉，
        // 之后任何调用都落进「运行时已关闭」分支、返回 null，
        // 最终报成「fetch 未实现或执行失败」——一个与真实原因毫无关系的文案。
        //
        // 延迟模式下已启动的实例住在管理器里，必须一并纳入「可复用」判断，
        // 否则重扫会把它当成新包重新登记成待启动，列表里出现两份。
        // 记录本轮之前的配方哈希：用于识别"换了内容"，从而解除故障停用
        val previousHashes = launchRecipes.mapValues { it.value.contentHash }
        val managedEntries = lazyRuntimeManager.snapshot()
        // 扫描期间保留旧外壳，收尾重建时按 id 复用同一对象
        pendingShellCache = deferredEntries
        val managedByArchive = managedEntries.associateBy { it.plugin.archiveName }
        val previous = (entries + managedEntries).associateBy { it.plugin.archiveName }
        val reused = mutableSetOf<String>()
        entries = emptyList()

        val archives = fileSystem.listArchives(PluginFileSystem.JS_SUFFIX)
        val trustState = trustStore.load()

        val ok = mutableListOf<Entry>()
        val bad = mutableListOf<BrokenJsPlugin>()
        val waiting = mutableListOf<PendingJsPlugin>()
        // 本轮登记的延迟启动配方；扫完整体替换，顺带清掉已删除/已撤销的
        val deferred = mutableMapOf<String, PendingLaunch>()

        // 插件 ID 是全局标识：凭据、私有数据、信任记录、调度注册表全按它索引。
        // 两个包声明同一个 id 时，后来的那个会静默覆盖前者的凭据与数据——
        // 用户会看到「刚登录的账号莫名变成另一个插件的」。这里把它当坏包挡掉。
        val seenIds = mutableSetOf<String>()
        // 只有**本轮校验通过且仍受信任**的 id 才能继续持有启动配方。
        // 用 seenIds 会把「待信任/已撤权」的 id 也算进去，
        // 于是旧配方残留下来，find()/ensureStarted() 还能把它重新拉起来。
        val trustedIds = mutableSetOf<String>()

        for (archiveName in archives) {
            when (val result = loadOne(archiveName, trustState, previous[archiveName])) {
                is LoadResult.Success -> {
                    val pluginId = result.entry.plugin.plugin.id
                    if (!seenIds.add(pluginId)) {
                        // 后加载的重复项直接释放，不进入 entries
                        closeEntry(result.entry, StopReason.REVOKED)
                        bad += BrokenJsPlugin(
                            result.entry.plugin.archiveName,
                            "插件 id「$pluginId」与其它包重复，已跳过",
                        )
                        AppLogger.warn(
                            "JS 插件 id 重复：${result.entry.plugin.archiveName} 的 $pluginId 已被占用"
                        )
                    } else {
                        trustedIds += pluginId
                        if (managedByArchive[archiveName] === result.entry) {
                            // 已在管理器中承载：标记复用即可，不要再加入 entries，
                            // 否则同一个插件会同时存在于两处、列表出现两份
                            reused += archiveName
                        } else {
                            // 复用了旧运行时的话，记下来，扫完不要把它关掉
                            previous[archiveName]?.let { old ->
                                if (result.entry.runtime === old.runtime) reused += archiveName
                            }
                            ok += result.entry
                        }
                    }
                }
                is LoadResult.Deferred -> {
                    val pluginId = result.launch.manifest.id
                    if (!seenIds.add(pluginId)) {
                        bad += BrokenJsPlugin(
                            result.launch.archiveName,
                            "插件 id「$pluginId」与其它包重复，已跳过",
                        )
                        AppLogger.warn("JS 插件 id 重复：${result.launch.archiveName} 的 $pluginId 已被占用")
                    } else {
                        trustedIds += pluginId
                        // 包变了/换了身份：管理器里的旧实例必须先停掉，
                        // 否则同一 id 会在管理器里留下两个代际
                        managedEntries.firstOrNull { it.plugin.plugin.id == pluginId }?.let {
                            runCatching { lazyRuntimeManager.shutdown(pluginId) }
                        }
                        deferred[pluginId] = result.launch
                        // 登记配方：延迟模式下运行时还没建，靠它首次启动与回收后重建
                        launchRecipes = launchRecipes + (pluginId to result.launch)
                    }
                }
                is LoadResult.Failure -> bad += result.broken
                is LoadResult.Skipped -> {
                    // 待信任的包同样要占住 id：若两个未信任包声明同一个 id，
                    // 用户依次信任后仍会落到同一个 id 上
                    if (!seenIds.add(result.pending.packageName)) {
                        bad += BrokenJsPlugin(
                            result.pending.archiveName,
                            "插件 id「${result.pending.packageName}」与其它包重复，已跳过",
                        )
                    } else {
                        waiting += result.pending
                    }
                }
            }
        }

        entries = ok
        broken = bad
        pending = waiting
        // 已实例化的插件不再需要配方；未变更的保留，已消失的自然被替换掉
        val liveIds = ok.map { it.plugin.plugin.id }.toSet()
        pendingLaunches = deferred.filterKeys { it !in liveIds }
        // 配方只留给本轮校验通过且受信任的 id：待信任/已撤权的必须立刻丢弃，
        // 否则 find()/ensureStarted() 仍能顺着旧配方把脚本重新跑起来。
        launchRecipes = launchRecipes.filterKeys { it in trustedIds }
        policies = policies.filterKeys { it in trustedIds }
        // 卸载/撤销的插件不该继续占常驻名额
        residentGrants.retainAll(trustedIds)
        // 换了内容（重装新版）说明作者可能已修好：解除停用，给它一次机会；
        // 被删除的也一并清掉，避免重装后仍带着旧停用标记
        disabledPlugins.retainAll(trustedIds)
        previousHashes.forEach { (id, oldHash) ->
            val newHash = launchRecipes[id]?.contentHash
            if (newHash != null && newHash != oldHash) disabledPlugins.remove(id)
        }
        // 登记生效策略：启动后配方会被清掉，策略要留到运行时回收为止
        policies = policies + deferred.mapValues { (_, launch) ->
            PluginRuntimePolicy.of(launch.manifest.lifecycle, launch.manifest.idleTimeoutSec)
        }
        // 外壳表只增补、不重建：保留已有对象是「稳定身份」的全部意义。
        // 详情页与会话控制器长期持有外壳，重建会让它们手里的监听与租约变成孤儿。
        for ((id, recipe) in launchRecipes) {
            if (deferredEntries[id] == null) stableShell(recipe)
        }
        // 失去信任/已卸载的配方已被清掉，对应外壳也要一并移除
        deferredEntries = deferredEntries.filterKeys { it in launchRecipes }
        commitShellCache()

        // 释放这一轮没能复用的旧运行时：包被删、被替换、或已失去信任。
        // 复用过的不动——正被详情页持有，关掉就等于把还在用的插件掐死。
        // 管理器承载的实例要走管理器停机，不能直接 closeEntry（否则管理器仍以为它活着）
        previous.forEach { (archiveName, old) ->
            if (archiveName !in reused) {
                if (managedByArchive[archiveName] === old) {
                    runCatching { lazyRuntimeManager.shutdown(old.plugin.plugin.id) }
                } else {
                    // 包被删除/替换：旧代际按「更新」处理，允许尽力清理
                    runCatching { closeEntry(old, StopReason.UPDATE) }
                }
            }
        }

        AppLogger.info(
            "JS 插件扫描完成：已启动 ${ok.size + lazyRuntimeManager.liveCount()}，" +
                "待启动 ${deferredEntries.size}，待信任 ${waiting.size}，失败 ${bad.size}"
        )
        // 失败原因单独打出来：只报数量的话，用户在界面上看到「加载失败」
        // 却拿不到任何线索，排查只能靠猜
        bad.forEach { AppLogger.warn("插件「${it.archiveName}」加载失败：${it.reason}") }
        // **托管实例必须继续以委托外壳对外**：
        // 外壳每次调用都现申请租约，而真实实例是没有租约保护的裸引用，
        // 把它交给调用方（列表、详情页）会让空闲回收在会话进行中把运行时关掉。
        // 因此「已启动」不等于「直接交出真实实例」——只有内部路径（realEntry）才拿真实实例。
        val managedIds = lazyRuntimeManager.snapshot().mapTo(mutableSetOf()) { it.plugin.plugin.id }
        // eager 条目直接对外（它们没有管理器，也就没有回收）；其余一律走外壳。
        val eager = ok.filter { it.plugin.plugin.id !in managedIds }
        val eagerIds = eager.mapTo(mutableSetOf()) { it.plugin.plugin.id }
        // 已在外壳表里的直接复用，保证同一个 id 始终是同一个对象；
        // 每个 id 只出现一次——托管实例与待启动外壳曾同时被加入，列表里出现两份。
        val shells = linkedMapOf<String, JsLoadedPlugin>()
        deferredEntries.values.filter { it.plugin.id !in eagerIds }.forEach { shells[it.plugin.id] = it }
        managedIds.filter { it !in eagerIds }.forEach { id ->
            launchRecipes[id]?.let { shells.getOrPut(id) { stableShell(it) } }
        }
        // 待启动项（含扫描期间被 find()/ensureStarted() 新建的外壳）也必须出现在列表里，
        // 否则用户会以为「包没装上」——它们既不在 entries 也不在管理器里。
        pendingLaunches.keys.filter { it !in eagerIds && it !in shells }.forEach { id ->
            launchRecipes[id]?.let { shells[id] = stableShell(it) }
        }
        return eager.map { it.plugin } + shells.values
    }

    /** 释放全部运行时。宿主退出或重新扫描前调用。 */
    fun closeAll() {
        entries.forEach { closeEntry(it) }
        entries = emptyList()
        // 延迟模式启动的实例由管理器承载，一并回收
        runCatching { lazyRuntimeManager.close() }
        // 待启动项没有运行时，只需清掉登记
        pendingLaunches = emptyMap()
        deferredEntries = emptyMap()
        pendingShellCache = emptyMap()
        pendingSessionListeners.clear()
        launchRecipes = emptyMap()
        residentGrants.clear()
    }

    /**
     * 加载单个包。
     *
     * 顺序固定为「解包 → 读 manifest → 算哈希 → 信任判定 → 执行入口脚本」，
     * 把信任判定放在执行之前，未信任的包一行脚本都不会跑。
     *
     * @param reusable 上一轮加载的同一个包。内容哈希与 manifest 都一致且仍受信任时直接复用它，
     *   不再新建运行时——重扫是很频繁的动作，每次都重建会让在途调用全部失效。
     */
    private fun loadOne(
        archiveName: String,
        trustState: TrustState,
        reusable: Entry? = null,
    ): LoadResult {
        val idFromName = archiveName.substringBeforeLast('.')

        return try {
            // 每次加载都重新解压：包可能已被替换，复用旧目录会读到混合内容
            fileSystem.deleteDir(fileSystem.pluginsRoot() + File.separator + idFromName)

            val files = fileSystem.readArchive(archiveName)
            if (files.isEmpty()) {
                return LoadResult.Failure(BrokenJsPlugin(archiveName, "包里没有脚本文件"))
            }

            // 解压到磁盘：入口脚本之外的相对 import 由引擎按模块表解析，
            // 但插件的 `ctx.files` 数据目录需要根目录存在
            fileSystem.extractArchive(archiveName)

            val manifestBytes = files[JsPluginManifest.MANIFEST_NAME]
                ?: return LoadResult.Failure(BrokenJsPlugin(archiveName, "缺少 manifest.json"))

            val manifest = when (val parsed = JsPluginManifest.parseDetailed(manifestBytes.toString(Charsets.UTF_8))) {
                is ParseResult.Success -> parsed.manifest
                is ParseResult.Failure ->
                    return LoadResult.Failure(BrokenJsPlugin(archiveName, "manifest 解析失败：${parsed.reason}"))
            }

            if (files[manifest.entry] == null) {
                return LoadResult.Failure(
                    BrokenJsPlugin(archiveName, "缺少入口脚本 ${manifest.entry}")
                )
            }

            // 接口版本判定放在「信任判定」之前：版本不兼容的包即使用户信任了
            // 也跑不起来，先拒掉能省掉一次无意义的信任确认。
            //
            // 这一步不能省成「跑起来再看」：缺函数的失败点在插件业务代码中间，
            // 报出来是 `xxx is not a function`，用户与作者都看不出是版本问题。
            when (val verdict = JsPluginApi.evaluate(manifest.apiVersion)) {
                is JsPluginApi.Verdict.TooNew -> {
                    return LoadResult.Failure(BrokenJsPlugin(archiveName, verdict.reason))
                }
                is JsPluginApi.Verdict.Compatible -> {
                    if (verdict.assumed) {
                        AppLogger.debug(
                            "JS 插件 ${manifest.id} 未声明 apiVersion，按 v${verdict.declared} 处理"
                        )
                    }
                }
            }

            // 能力协商：显式声明 requires 的插件必须在执行入口前满足全部必需能力。
            // 缺省不校验——旧包没有这个字段，强制校验会让它们全部被拒。
            val unmet = HostCapabilities.unmet(manifest.requires)
            if (unmet.isNotEmpty()) {
                return LoadResult.Failure(
                    BrokenJsPlugin(
                        archiveName,
                        HostCapabilities.rejectReason(manifest.requires, unmet),
                    )
                )
            }

            // 内容哈希：既做完整性校验，也当签名指纹用
            val contentHash = JsPluginManifest.computeContentHash(files)

            // 发布者声明了哈希就比对，不一致说明包被改过
            manifest.declaredHash?.let { declared ->
                if (!declared.equals(contentHash, ignoreCase = true)) {
                    return LoadResult.Failure(
                        BrokenJsPlugin(archiveName, "包内容与 manifest 声明的 sha256 不一致")
                    )
                }
            }

            val info = manifest.toPackageInfo(contentHash)
            when (val verdict = PluginTrust.evaluate(info, trustState)) {
                // JS 包的内容哈希必定非空，Unsigned 理论上不会出现；
                // 真的出现说明 manifest id 与哈希推导异常，按不可信任处理
                TrustVerdict.Unsigned -> return LoadResult.Skipped(
                    PendingJsPlugin(
                        archiveName = archiveName,
                        packageName = manifest.id,
                        versionName = manifest.version,
                        identity = null,
                        unsigned = true,
                        reason = "插件包缺少内容指纹，无法建立信任",
                    )
                )
                is TrustVerdict.Untrusted -> return LoadResult.Skipped(
                    PendingJsPlugin(
                        archiveName = archiveName,
                        packageName = manifest.id,
                        versionName = manifest.version,
                        identity = verdict.identity,
                        unsigned = false,
                        reason = "尚未信任，确认来源后才会加载",
                    )
                )
                is TrustVerdict.Rejected ->
                    return LoadResult.Failure(BrokenJsPlugin(archiveName, verdict.reason))
                TrustVerdict.Trusted -> Unit
            }

            // 信任指纹刻意不包含 manifest（例如只改描述不应要求重新信任），
            // 但运行时行为和对外声明取决于 manifest：入口、指标、凭据等变化都要重建。
            // 因此在信任校验通过后，独立比较 manifest 决定是否复用。
            if (reusable != null &&
                reusable.contentHash == contentHash &&
                reusable.manifest == manifest
            ) {
                AppLogger.debug("复用已加载的 JS 插件 ${manifest.id}（脚本与 manifest 均未变化）")
                return LoadResult.Success(reusable)
            }

            // 延迟实例化：校验全部通过后只登记「启动配方」，不创建运行时。
            // 这是按需加载的关键一步——列表可以列出插件，但不执行任何脚本。
            if (lazyInstantiation) {
                AppLogger.debug("登记待启动插件 ${manifest.id}（延迟实例化）")
                return LoadResult.Deferred(
                    PendingLaunch(archiveName, manifest, contentHash)
                )
            }

            instantiate(archiveName, manifest, files, contentHash)
        } catch (e: Exception) {
            AppLogger.error("JS 插件加载失败：$archiveName", e)
            LoadResult.Failure(BrokenJsPlugin(archiveName, e.message ?: "加载失败"))
        }
    }

    /** 信任已通过，创建运行时并装配桥接 */
    private fun instantiate(
        archiveName: String,
        manifest: JsPluginManifest,
        files: Map<String, ByteArray>,
        contentHash: String,
    ): LoadResult {
        val runtime = JsPluginRuntime.create(manifest.id)

        // 只把 .js 文件作为模块注册；manifest 已在上面消费掉
        val sources = files
            .filterKeys { it.endsWith(".js") }
            .mapValues { it.value.toString(Charsets.UTF_8) }

        // 桥接需要访问「本次调用的 PluginContext」，由适配器在调用时注入，
        // 这里先用一个占位容器，加载完成后由 JsSourcePlugin 接上
        val contextRef = ContextHolder()

        val httpHolder = arrayOfNulls<JsHttpBridge>(1)
        val wsHolder = arrayOfNulls<JsWebSocketBridge>(1)
        val bridgeHolder = arrayOfNulls<JsPluginContextBridge>(1)
        // 增量的接收方是 JsSourcePlugin，它要等 start 之后才构造出来。
        // 入口脚本在模块顶层就 emit 是允许的，那时没有实例，按「无人接收」丢弃。
        val streamHolder = arrayOfNulls<JsSourcePlugin>(1)

        val startResult = runtime.start(
            modules = sources,
            entry = manifest.entry,
        ) { rt ->
            val contextBridge = JsPluginContextBridge(
                contextProvider = { contextRef.require() },
                pluginIdProvider = { manifest.id },
            )
            // WebSocket 桥同样只持有 runtime：OkHttp 回调在别的线程，
            // 帧统一投回插件专用线程再落地 Promise
            val ws = JsWebSocketBridge(
                runtime = runtime,
                tokenProvider = { "ws#${wsTokenSeq.incrementAndGet()}" },
                pinnedCaSha256 = manifest.pinnedCaSha256,
            )
            // HTTP 桥不持有 JsRuntime：OkHttp 回调在别的线程，
            // 必须把结果投回插件专用线程，由 JsPluginRuntime 统一调度
            val http = JsHttpBridge(
                runtime = runtime,
                tokenProvider = { "js#${httpTokenSeq.incrementAndGet()}" },
                pinnedCaSha256 = manifest.pinnedCaSha256,
                // 配对时插件只调用一次 `ctx.http.trustCa`，但网关的 HTTPS 与 WSS
                // 用的是同一张自签 CA。只装 HTTP 的后果是配对顺利、详情页一建
                // `wss://` 就 PKIX 失败——从界面看只是「上次连接失败」，
                // 很难联想到另一套客户端还没信任这张证书。
                onCaTrusted = { der -> ws.installPinnedCa(der) },
            )
            val crypto = JsCryptoBridge()
            httpHolder[0] = http
            wsHolder[0] = ws
            bridgeHolder[0] = contextBridge

            val stream = JsStreamBridge { text -> streamHolder[0]?.emitStreamDelta(text) }
            // 会话事件同样落在 JsSourcePlugin 上，复用上面的 holder
            val session = JsSessionBridge { event -> streamHolder[0]?.emitSessionUiEvent(event) }

            CompositeHostDispatcher(
                listOf(
                    contextBridge.functionNames() to contextBridge,
                    http.functionNames() to http,
                    crypto.functionNames() to crypto,
                    ws.functionNames() to ws,
                    stream.functionNames() to stream,
                    session.functionNames() to session,
                )
            )
        }

        if (startResult.isFailure) {
            runtime.close()
            val reason = startResult.exceptionOrNull()?.message ?: "入口脚本执行失败"
            return LoadResult.Failure(BrokenJsPlugin(archiveName, reason))
        }

        val contextBridge = bridgeHolder[0]
            ?: run {
                runtime.close()
                return LoadResult.Failure(BrokenJsPlugin(archiveName, "桥接初始化失败"))
            }

        val plugin = JsSourcePlugin(
            id = manifest.id,
            displayName = manifest.name,
            credentialSpec = manifest.credentialSpec,
            metrics = manifest.metrics,
            runtime = runtime,
            entryModule = manifest.entry,
            contextBridge = contextBridge,
            bindContext = { ctx -> contextRef.set(ctx) },
            declaredApiVersion = manifest.apiVersion,
            pinnedCaSha256 = manifest.pinnedCaSha256,
        )
        streamHolder[0] = plugin

        // 记录配方：进入延迟模式后若运行时被回收，靠它重建
        launchRecipes = launchRecipes + (manifest.id to PendingLaunch(
            archiveName = archiveName,
            manifest = manifest,
            contentHash = contentHash,
        ))

        return LoadResult.Success(
            Entry(
                plugin = JsLoadedPlugin(
                    packageInfo = manifest.toPackageInfo(contentHash),
                    archiveName = archiveName,
                    plugin = plugin,
                ),
                runtime = runtime,
                httpBridge = httpHolder[0],
                wsBridge = wsHolder[0],
                contentHash = contentHash,
                manifest = manifest,
            )
        )
    }

    /**
     * 关闭原因：决定是否给插件一次「尽力清理」的机会。
     *
     * **不是所有关闭都该回调插件代码**：撤销信任、清除数据、故障中断时
     * 继续执行插件逻辑本身就是错的，而且可能把刚清掉的数据写回去。
     */
    enum class StopReason(
        /** 是否调用 onUnload */
        val callsHook: Boolean,
    ) {
        /** 空闲回收 */
        IDLE(callsHook = true),
        /** 会话范围插件最后使用者离开 */
        SESSION_ENDED(callsHook = true),
        /** 包更新：旧代际被替换 */
        UPDATE(callsHook = true),
        /** 进程退出 */
        SHUTDOWN(callsHook = true),
        /** 撤销信任 / 卸载：不再执行插件代码 */
        REVOKED(callsHook = false),
        /** 清除凭据或数据：禁止旧代际回写 */
        CLEARED(callsHook = false),
        /** 看门狗/OOM 等故障：运行时已不可信 */
        FAULTED(callsHook = false),
    }

    /**
     * 把一个已加载条目彻底释放。
     *
     * **顺序固定**：先尽力调用 `onUnload`（按原因），再取消 HTTP/WS 与重试定时器，
     * 最后关运行时。反过来会让已经发出的回调在运行时销毁后仍尝试落地 Promise。
     *
     * 按需回收、重扫替换、撤信任、进程退出都走这里，避免各处漏掉某一项。
     */
    fun releaseEntry(entry: JsLoadedPlugin, reason: StopReason = StopReason.SHUTDOWN) {
        val pluginId = entry.plugin.id
        val internal = entries.firstOrNull { it.plugin === entry }
        if (internal != null) {
            closeEntry(internal, reason)
            // 关闭后必须从列表移除：否则 find() 仍会返回一个已关闭的实例，
            // 后续调用直接落进"运行时已关闭"分支
            entries = entries.filterNot { it === internal }
        }
        // 延迟模式下条目住在管理器里，entries 查不到——必须单独停机，
        // 否则撤销信任/卸载时运行时根本不会被关
        if (lazyRuntimeManager.snapshot().any { it.plugin.plugin.id == pluginId }) {
            runCatching { lazyRuntimeManager.shutdown(pluginId) }
        }
        deferredEntries = deferredEntries - pluginId
        pendingShellCache = pendingShellCache - pluginId
    }

    /** 内部释放：桥接先撤，运行时后关 */
    private fun closeEntry(entry: Entry, reason: StopReason = StopReason.SHUTDOWN) {
        // 先给插件一次尽力清理的机会（按原因），再撤销外部连接
        if (reason.callsHook) {
            runCatching { entry.runtime.callUnloadHook(entry.manifest.entry, UNLOAD_HOOK_TIMEOUT_MS) }
                .onFailure { AppLogger.debug("插件 ${entry.manifest.id} 的 onUnload 未完成：${it.message}") }
        }
        runCatching { entry.httpBridge?.cancelAll() }
            .onFailure { AppLogger.warn("取消插件 HTTP 请求失败：${entry.manifest.id}（${it.message}）") }
        runCatching { entry.wsBridge?.cancelAll() }
            .onFailure { AppLogger.warn("关闭插件 WebSocket 失败：${entry.manifest.id}（${it.message}）") }
        runCatching { entry.runtime.close() }
            .onFailure { AppLogger.warn("关闭插件运行时失败：${entry.manifest.id}（${it.message}）") }
    }

    /** 加载成功的结果，附带运行时用于卸载时释放。 */
    private data class Entry(
        val plugin: JsLoadedPlugin,
        val runtime: JsPluginRuntime,
        val httpBridge: JsHttpBridge?,
        /** 长连接桥；卸载时必须 cancelAll，否则后台还挂着未关闭的 socket */
        val wsBridge: JsWebSocketBridge?,
        /**
         * 包内容的 sha256。
         *
         * 重扫时靠它判断「这个包变没变」：一样就复用旧运行时，
         * 不一样才销毁重建。没有它就只能无差别重建，
         * 而重建会把正在被调用的运行时掐掉。
         */
        val contentHash: String,
        /** Manifest 不参与信任指纹，但其运行元数据变化时必须重建实例。 */
        val manifest: JsPluginManifest,
    )

    private sealed interface LoadResult {
        data class Success(val entry: Entry) : LoadResult
        data class Failure(val broken: BrokenJsPlugin) : LoadResult

        /** 包本身没问题，只差用户信任，不执行脚本 */
        data class Skipped(val pending: PendingJsPlugin) : LoadResult

        /** 校验通过但延迟实例化：只登记启动配方，不创建运行时 */
        data class Deferred(val launch: PendingLaunch) : LoadResult
    }

    /**
     * 承载本次调用的 [PluginContext]。
     *
     * 桥接是 [JsPluginRuntime.start] 里创建的（那时才拿得到 runtime），
     * 而 `JsSourcePlugin` 需要它，两者构造顺序互相依赖，用一次性容器传递。
     * 同时它也是「当前调用上下文」的载体：桥接从宿主函数里同步回调进来时，
     * 拿到的必须是本次 `fetch` / `login` 传入的那个 context。
     */
    private class ContextHolder {
        @Volatile
        private var current: PluginContext? = null

        fun set(context: PluginContext) {
            current = context
        }

        fun require(): PluginContext =
            current ?: throw IllegalStateException(
                // 卸载钩子期间上下文未绑定，这里会失败——这是刻意的：
                // 钩子只允许 log，不允许读写凭据/文件，避免旧代际把已清数据写回
                "插件上下文未注入（卸载钩子期间仅允许日志）"
            )
    }

    private companion object {
        /**
         * `onUnload` 的等待上限。
         *
         * 与设计一致：2 秒是「宿主停止等待并撤销写权限」的预算，
         * 不承诺任意阻塞 I/O 一定在 2 秒内结束。超时后请求中断并继续回收。
         */
        const val UNLOAD_HOOK_TIMEOUT_MS = 2_000L
    }
}

/**
 * 一个加载成功的 JS 插件。
 *
 * 刻意不引用各端的 `LoadedPlugin`：那份类型还带着「是否已信任」这类
 * 运行期状态，属于宿主自己的簿记。共享层只交出「包元数据 + 插件实例 +
 * 包文件名」，各端用自己的结构包一层即可，两端不必为了统一而互相迁就。
 *
 * [plugin] 声明为 [SourcePlugin] 而不是具体的 [JsSourcePlugin]：延迟实例化下
 * 它可能是委托外壳，调用方不应假设拿到的一定是已启动的实例。真正需要
 * JS 专属信息（如 `jsApiVersion`）的地方请显式转换并处理空值。
 */
class JsLoadedPlugin(
    val packageInfo: PluginPackageInfo,
    val archiveName: String,
    val plugin: SourcePlugin,
)

/** 一个加载失败的 JS 插件。 */
data class BrokenJsPlugin(
    val archiveName: String,
    val reason: String,
)

/**
 * 一个已落盘、但还不能用的 JS 插件。
 *
 * 与 [BrokenJsPlugin] 的区别在「用户还能做什么」：前者是包本身有问题，
 * 只能重新下载；本类是包没问题，只差用户确认信任。
 *
 * @property identity 信任身份（包 id + 内容哈希）。无内容指纹时为空，UI 不给信任入口
 * @property unsigned 是否缺少内容指纹。此时不是「等信任」，而是**根本不能信任**
 */
data class PendingJsPlugin(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val identity: PluginIdentity?,
    val unsigned: Boolean,
    val reason: String,
)

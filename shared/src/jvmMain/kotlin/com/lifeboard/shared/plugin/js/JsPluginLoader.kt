package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.PluginContext
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

    @Volatile
    private var pending: List<PendingJsPlugin> = emptyList()

    /** 已成功加载的插件。 */
    fun loadedPlugins(): List<JsLoadedPlugin> = entries.map { it.plugin }

    fun brokenPlugins(): List<BrokenJsPlugin> = broken

    /** 包已落盘、但还没得到用户信任（或因内容哈希缺失无法建立信任）的插件 */
    fun pendingPlugins(): List<PendingJsPlugin> = pending

    fun find(pluginId: String): JsLoadedPlugin? =
        entries.firstOrNull { it.plugin.plugin.id == pluginId }?.plugin

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
        val previous = entries.associateBy { it.plugin.archiveName }
        val reused = mutableSetOf<String>()
        entries = emptyList()

        val archives = fileSystem.listArchives(PluginFileSystem.JS_SUFFIX)
        val trustState = trustStore.load()

        val ok = mutableListOf<Entry>()
        val bad = mutableListOf<BrokenJsPlugin>()
        val waiting = mutableListOf<PendingJsPlugin>()

        // 插件 ID 是全局标识：凭据、私有数据、信任记录、调度注册表全按它索引。
        // 两个包声明同一个 id 时，后来的那个会静默覆盖前者的凭据与数据——
        // 用户会看到「刚登录的账号莫名变成另一个插件的」。这里把它当坏包挡掉。
        val seenIds = mutableSetOf<String>()

        for (archiveName in archives) {
            when (val result = loadOne(archiveName, trustState, previous[archiveName])) {
                is LoadResult.Success -> {
                    val pluginId = result.entry.plugin.plugin.id
                    if (!seenIds.add(pluginId)) {
                        // 后加载的重复项直接释放，不进入 entries
                        result.entry.httpBridge?.cancelAll()
                        result.entry.wsBridge?.cancelAll()
                        result.entry.runtime.close()
                        bad += BrokenJsPlugin(
                            result.entry.plugin.archiveName,
                            "插件 id「$pluginId」与其它包重复，已跳过",
                        )
                        AppLogger.warn(
                            "JS 插件 id 重复：${result.entry.plugin.archiveName} 的 $pluginId 已被占用"
                        )
                    } else {
                        // 复用了旧运行时的话，记下来，扫完不要把它关掉
                        previous[archiveName]?.let { old ->
                            if (result.entry.runtime === old.runtime) reused += archiveName
                        }
                        ok += result.entry
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

        // 释放这一轮没能复用的旧运行时：包被删、被替换、或已失去信任。
        // 复用过的不动——正被详情页持有，关掉就等于把还在用的插件掐死。
        previous.forEach { (archiveName, old) ->
            if (archiveName !in reused) {
                runCatching { old.httpBridge?.cancelAll() }
                runCatching { old.wsBridge?.cancelAll() }
                runCatching { old.runtime.close() }
            }
        }

        AppLogger.info(
            "JS 插件扫描完成：成功 ${ok.size}，待信任 ${waiting.size}，失败 ${bad.size}"
        )
        // 失败原因单独打出来：只报数量的话，用户在界面上看到「加载失败」
        // 却拿不到任何线索，排查只能靠猜
        bad.forEach { AppLogger.warn("插件「${it.archiveName}」加载失败：${it.reason}") }
        return ok.map { it.plugin }
    }

    /** 释放全部运行时。宿主退出或重新扫描前调用。 */
    fun closeAll() {
        entries.forEach {
            it.httpBridge?.cancelAll()
            it.wsBridge?.cancelAll()
            it.runtime.close()
        }
        entries = emptyList()
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

            val manifest = JsPluginManifest.parse(manifestBytes.toString(Charsets.UTF_8))
                ?: return LoadResult.Failure(
                    BrokenJsPlugin(archiveName, "manifest.json 格式不合法或缺少必需字段")
                )

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
            // HTTP 桥不持有 JsRuntime：OkHttp 回调在别的线程，
            // 必须把结果投回插件专用线程，由 JsPluginRuntime 统一调度
            val http = JsHttpBridge(
                runtime = runtime,
                tokenProvider = { "js#${httpTokenSeq.incrementAndGet()}" },
            )
            val crypto = JsCryptoBridge()
            // WebSocket 桥同样只持有 runtime：OkHttp 回调在别的线程，
            // 帧统一投回插件专用线程再落地 Promise
            val ws = JsWebSocketBridge(
                runtime = runtime,
                tokenProvider = { "ws#${wsTokenSeq.incrementAndGet()}" },
            )
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
        )
        streamHolder[0] = plugin

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
            current ?: throw IllegalStateException("插件上下文未注入")
    }
}

/**
 * 一个加载成功的 JS 插件。
 *
 * 刻意不引用各端的 `LoadedPlugin`：那份类型还带着「是否已信任」这类
 * 运行期状态，属于宿主自己的簿记。共享层只交出「包元数据 + 插件实例 +
 * 包文件名」，各端用自己的结构包一层即可，两端不必为了统一而互相迁就。
 */
class JsLoadedPlugin(
    val packageInfo: PluginPackageInfo,
    val archiveName: String,
    val plugin: JsSourcePlugin,
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

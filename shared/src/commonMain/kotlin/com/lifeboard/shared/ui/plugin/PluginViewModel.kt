package com.lifeboard.shared.ui.plugin

import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.LoginIntent
import com.lifeboard.plugin.api.LoginOptions
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.CollectOutcome
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PickedFile
import com.lifeboard.shared.platform.PluginInstallResult
import com.lifeboard.shared.platform.PluginList
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.task.TaskCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 插件页面上的一行「已装可用」插件。
 *
 * [healthy] 三态而不是布尔：`null` 表示**还没采集过**。
 * 这不是「正常」也不是「异常」——把它折成任何一个都会误导用户：
 * 说正常，用户以为数据在更新；说异常，用户去重装一个没问题的插件。
 */
data class PluginRow(
    val pluginId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val archiveName: String,
    val metricCount: Int,
    val trusted: Boolean,
    val healthy: Boolean?,
    val healthMessage: String,
    val signatureHashes: List<String>,
    val needsLogin: Boolean = false,
    /** 登录态文案，如「已登录：138****1234」；未登录时为空 */
    val loginText: String = "",
    /** 是否该在界面上给一个「登录」按钮 */
    val needsLoginAction: Boolean = false,
    /**
     * 这次没问出登录态（运行时未就绪/已关闭/插件抛异常）。
     *
     * 与「不需要登录」和「明确未登录」都不同：界面要说「登录状态待确认」，
     * 且不给登录按钮——问题不在凭据，盲目再登一次只会失败第二次。
     */
    val authStateUnknown: Boolean = false,
)

/**
 * 待信任 / 无签名的插件，用户在这里做决定。
 *
 * [unsigned] 与 [signatureHashes] 分离是有意的：没签名时列表为空，
 * 界面要据此隐藏「信任」按钮——没有指纹就没法让用户核对来源，
 * 允许信任等于把安全决策降级成一次点击。
 */
data class PendingRow(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val unsigned: Boolean,
    val reason: String,
    val signatureHashes: List<String>,
)

/** 加载失败的包。留着是为了给用户一条删除的出路，否则坏包占着同名位置删不掉 */
data class BrokenRow(
    val archiveName: String,
    val packageName: String,
    val reason: String,
)

/**
 * 一次待确认的覆盖安装。
 *
 * @property existingVersionName 用户要能看出「从哪个版本换成哪个版本」
 */
data class PendingInstall(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val signatureHashes: List<String>,
    val isOverwrite: Boolean,
    val existingVersionName: String = "",
)

/**
 * 登录进行中的状态。
 *
 * [fields] 由插件给出，宿主不知道「账号」和「验证码」的区别——
 * 只按 [InputField.secret] 决定要不要掩码。
 *
 * @property canRestart 是否可以退回「重新输入账号」。
 *   停在验证码那一步时没有退路，用户会卡死在中间状态里：
 *   取消对话框并不会让插件忘掉这轮登录，再点登录又回到同一个验证码界面。
 */
data class LoginUi(
    val pluginId: String,
    val displayName: String,
    val prompt: String,
    val fields: List<InputField>,
    val captchaImage: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val values: Map<String, String> = emptyMap(),
    val canRestart: Boolean = false,
)

data class PluginUiState(
    val loading: Boolean = true,
    val installed: List<PluginRow> = emptyList(),
    val pending: List<PendingRow> = emptyList(),
    val broken: List<BrokenRow> = emptyList(),
    /** 有值表示要弹覆盖确认框 */
    val pendingInstall: PendingInstall? = null,
    /**
     * 正在导入插件包。
     *
     * 界面据此禁用「选择插件文件」按钮。连续点击会并发跑多个 install，
     * 它们往同一个插件目录解压、同名叫的包互相覆盖、还各自触发一次全量重扫，
     * 结果是不可预期的报错。
     */
    val importing: Boolean = false,
    /** 有值表示正在登录，UI 应展示插件要求填的表单 */
    val login: LoginUi? = null,
    /** 一次性提示，展示后由 UI 调 [PluginViewModel.consumeMessage] 清掉 */
    val message: String? = null,
    /**
     * 本机跑不了 JS 插件。
     *
     * true 时界面只显示一条说明，不列插件、不给导入入口：
     * 引擎 native 库缺失（未覆盖的 CPU 架构）时，任何「安装」动作
     * 都会在解压后失败，摆出按钮反而是在骗用户。
     */
    val engineUnavailable: Boolean = false,
)

/**
 * 插件列表页的状态机，两端共用。
 *
 * ## 为什么搬到 shared
 *
 * 这段逻辑一行 Android API 都没有：读列表、装包、信任、登录表单流转，
 * 全部经由 [PlatformDependencies] 的契约完成。留在 Android 侧意味着
 * 桌面端要照着抄一遍，而「登录多轮状态机」是最容易抄出差异的那种代码。
 *
 * ## 为什么不继承 AndroidX 的 ViewModel
 *
 * `:shared` 不依赖 `androidx.lifecycle`。改为接收一个 [CoroutineScope]：
 * Android 传 `viewModelScope`，桌面传应用级 scope。
 * 这样「谁负责取消协程」由调用方决定，共享层不假设有 ViewModel 生命周期。
 *
 * ## 健康度为什么读表
 *
 * [PluginInvoker.health] 读的是 `source_health` 表，不现调插件的 `health()`。
 * 后者会真的发网络请求——打开一次列表就同步一次，代价太大，
 * 而用户点开列表只是想看看装了什么。
 */
class PluginViewModel(
    private val deps: PlatformDependencies,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    /**
     * 宿主的盘面流。
     *
     * 只取一次：实现方（两端都是 `StateFlow`）在 `snapshots()` 里
     * 顺带启动目录监听，[refresh] 若重复调用会重复登记一遍。
     * 引擎不可用时为 null，此时不订阅、也不扫描。
     */
    private val snapshotFlow: Flow<PluginList>? =
        if (deps.plugins.engineAvailable()) deps.plugins.snapshots() else null

    /**
     * 订阅插件盘面。
     *
     * 宿主在插件目录变化时会自己重扫并推新值，所以这里不需要轮询，
     * 也不需要用户点刷新——把包拷进目录、覆盖成新版本、删掉包，列表都会自己跟上。
     *
     * 刻意**不**在订阅前手动调一次 `deps.plugins.refresh()`：
     * 那会与宿主的首扫重复触发一次全量扫描（重建全部 QuickJS 运行时），
     * 而界面拿到的首个 `ready = false` 已经足以显示加载态。
     */
    init {
        // 引擎不可用时直接定版：不再订阅盘面。宿主在缺库的机型上
        // 扫描必然失败，订阅只会让界面在「加载中」与空列表之间空转
        val flow = snapshotFlow
        if (flow != null) {
            scope.launch {
                flow.collect { list -> applyList(list) }
            }
        } else {
            _state.value = PluginUiState(loading = false, engineUnavailable = true)
        }
    }

    /**
     * 最近一次从宿主收到的盘面。
     *
     * **为什么必须自己存**：实现方返回的是 `StateFlow`，宿主重扫后若
     * 装了什么、版本、指标都没变，它推来的 `PluginList` 与旧值**相等**，
     * StateFlow 会直接吞掉这次发射。而「登录态变了」恰恰不体现在
     * `PluginList` 里（它在 [toRow] 里现问插件），于是列表会一直停在
     * 「已登录」——登录成功、登出、清除凭据后都遇到过这个问题。
     *
     * 存一份原样盘面，动作完成后就能自己重跑一遍 [applyList] 把登录态刷新出来。
     */
    private var lastList: PluginList? = null

    /** 把宿主推来的盘面翻译成界面状态。健康度与登录态在这一层补 */
    private suspend fun applyList(list: PluginList) {
        if (!list.ready) return
        lastList = list
        val rows = list.installed.map { it.toRow(deps) }
        _state.value = _state.value.copy(
            loading = false,
            installed = rows,
            pending = list.pending.map {
                PendingRow(
                    archiveName = it.archiveName,
                    packageName = it.packageName,
                    versionName = it.versionName,
                    unsigned = it.unsigned,
                    reason = it.reason,
                    signatureHashes = it.signatureHashes,
                )
            },
            broken = list.broken.map {
                BrokenRow(
                    archiveName = it.archiveName,
                    packageName = it.packageName,
                    reason = it.reason,
                )
            },
        )
    }

    /**
     * 用最近一次盘面重算列表（不重新扫描）。
     *
     * 给「插件集合没变、只有登录态或健康度变了」的动作收尾用：
     * 登录、登出、清除凭据、立即同步。
     */
    private suspend fun reloadRows() {
        lastList?.let { applyList(it) }
    }

    /**
     * 手动刷新。
     *
     * 列表本身已经靠 [snapshotFlow] 自动更新，这个入口只保留给
     * 「刚改完文件、不想等轮询间隔」的场景与界面的显式刷新按钮。
     *
     * 扫完一定再 [reloadRows] 一次：重扫结果与旧值相等时 StateFlow 不会发射，
     * 只靠订阅的话 `loading` 会永远停在 true、登录态也不会更新。
     */
    fun refresh() {
        scope.launch {
            // 宿主已经扫过（盘面 ready）就直接用缓存，**不再重扫**。
            //
            // 页面重建会走到这里：切到别的页签再切回来时，`PluginScreen`
            // 的 `rememberCoroutineScope` 随组合销毁，VM 被重建、`lastList`
            // 归零。此时若照旧全量重扫，`scan()` 会释放并重建所有插件运行时——
            // 正在跑的采集会被打断，且运行时被占用时 scan 可能返回空列表，
            // 界面就变成「一个插件都没有」。用缓存盘面重建列表既快又安全。
            //
            // 接口签名是 `Flow`，取不到同步值；而两端实现都是 `StateFlow`，
            // 订阅后当前值会立刻到手。首启时宿主还没扫完（ready = false），
            // 等到超时再走下面真正的重扫，保证第一次打开仍能列出插件。
            val flow = snapshotFlow ?: return@launch
            val cached = withTimeoutOrNull(CACHED_SNAPSHOT_WAIT_MS) {
                flow.first { it.ready }
            }
            if (cached != null) {
                applyList(cached)
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            runCatching { deps.plugins.refresh() }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        message = "扫描插件失败：${error.message ?: error::class.simpleName}",
                    )
                    return@launch
                }
            reloadRows()
            // 首次刷新时可能还没收到过盘面：把 loading 收掉，别让界面一直转圈
            if (_state.value.loading) {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    private suspend fun PluginSummary.toRow(deps: PlatformDependencies): PluginRow {
        // 插件不需要登录时不去问登录态：问了也是 LoggedOut，
        // 却会在界面上留一个点了没用的错误提示。
        //
        // 注意 auth 有三态，别把后两种混在一起：
        //   AuthState.LoggedOut —— 插件明确回答「未登录」
        //   AuthState.LoggedIn / Expired —— 插件给了具体状态
        //   null —— 这次没问出来（运行时未就绪/已关闭/抛异常），**没有结论**
        val auth = if (needsLogin && trusted) {
            runCatching { deps.pluginInvoker.authState(pluginId) }
                .getOrElse { error ->
                    AppLogger.warn("读取插件 $pluginId 登录态失败：${error.message ?: error::class.simpleName}")
                    null
                }
        } else {
            null
        }
        val health = runCatching { deps.pluginInvoker.health(pluginId) }.getOrNull()
        return PluginRow(
            pluginId = pluginId,
            displayName = displayName,
            packageName = packageName,
            versionName = versionName,
            archiveName = archiveName,
            metricCount = metricCount,
            trusted = trusted,
            healthy = when (health) {
                is com.lifeboard.plugin.api.PluginHealth.Healthy -> true
                is com.lifeboard.plugin.api.PluginHealth.Degraded -> null
                else -> false
            },
            healthMessage = when (health) {
                is com.lifeboard.plugin.api.PluginHealth.Broken -> health.message
                is com.lifeboard.plugin.api.PluginHealth.Degraded -> health.message
                else -> ""
            },
            signatureHashes = signatureHashes,
            needsLogin = needsLogin,
            loginText = authText(auth),
            // 「登录」按钮只在插件确实说未登录/过期时才给。
            // 问不出来（null）不给：用户点了也只会再失败一次，
            // 而真正该做的是等运行时就绪后重试
            needsLoginAction = needsLogin && (
                auth is AuthState.LoggedOut ||
                    auth is AuthState.Expired
                ),
            // 问不出来 ≠ 未登录，界面要单独说「待确认」
            authStateUnknown = needsLogin && trusted && auth == null,
        )
    }

    private fun authText(auth: AuthState?): String = when (auth) {
        null -> ""
        is AuthState.LoggedIn -> auth.accountName?.let { "已登录：$it" } ?: "已登录"
        is AuthState.Expired -> auth.message ?: "登录已过期"
        AuthState.LoggedOut -> "未登录"
    }

    /**
     * 用户选完文件。
     *
     * 安装**不等于**信任：这里只是把包装进私有目录，然后停下来问用户。
     * 若同名插件已存在，走 [PluginInstallResult.Conflict] 分支弹覆盖确认。
     */
    fun onFilePicked(file: PickedFile) {
        scope.launch {
            // 串行化：上一次还没装完就再点一次，会往同一个插件目录并发解压。
            // 两边都按「覆盖」写入同一批文件，解压到一半的包被另一路读到，
            // 就会报出「包已损坏」这种与真实原因无关的错误。
            if (_state.value.importing) return@launch
            _state.value = _state.value.copy(importing = true)
            try {
                when (val outcome = deps.plugins.install(file)) {
                    is PluginInstallResult.Installed -> {
                        refresh()
                        _state.value = _state.value.copy(
                            message = "已导入 ${outcome.packageName}，确认指纹后才会启用",
                        )
                    }
                    is PluginInstallResult.Conflict -> {
                        _state.value = _state.value.copy(
                            pendingInstall = PendingInstall(
                                archiveName = "",
                                packageName = outcome.packageName,
                                versionName = outcome.incomingVersionName,
                                // 冲突时拿不到新包的指纹列表：安装器只回了版本号。
                                // 确认框里不显示指纹，但用户仍能在装完后的「待处理」
                                // 列表里核对——那里有完整指纹
                                signatureHashes = emptyList(),
                                isOverwrite = true,
                                existingVersionName = outcome.existingVersionName,
                            ),
                        )
                    }
                    is PluginInstallResult.Failed -> {
                        _state.value = _state.value.copy(message = outcome.reason)
                    }
                }
            } catch (e: Throwable) {
                // 安装涉及解压、验签、写盘，任何一环都可能抛。
                // 这里必须兜住：`scope` 是 Compose 的作用域，异常逃出去
                // 会让整个界面崩掉，而「一个包装不上」远不该是这个后果。
                //
                // 抓 Throwable 而不是 Exception：native 层缺库抛的是
                // UnsatisfiedLinkError，属 Error 分支，catch (Exception) 接不住。
                AppLogger.error("导入插件失败：${file.displayName}", e)
                _state.value = _state.value.copy(
                    message = "导入失败：${e.message ?: e::class.simpleName ?: "未知错误"}",
                )
            } finally {
                // 无论成功、冲突还是抛异常，都要解锁，否则按钮永久禁用
                _state.value = _state.value.copy(importing = false)
            }
        }
    }

    fun confirmOverwrite() {
        scope.launch {
            val info = _state.value.pendingInstall ?: return@launch
            val ok = runCatching { deps.plugins.confirmOverwrite(info.packageName) }
                .getOrDefault(false)
            _state.value = _state.value.copy(
                pendingInstall = null,
                message = if (ok) "已覆盖安装 ${info.packageName}" else "写入失败，请检查存储空间",
            )
            refresh()
        }
    }

    fun cancelOverwrite() {
        scope.launch {
            runCatching { deps.plugins.discardStaged() }
            _state.value = _state.value.copy(pendingInstall = null)
        }
    }

    /**
     * 信任一个待处理插件。
     *
     * 信任的就是**这套签名**：身份 = 包名 + 签名集合。换签名必须重新信任，
     * 否则作者可以把恶意代码签在同一个包名下悄悄推给已信任的用户。
     */
    fun trust(row: PendingRow) {
        scope.launch {
            runCatching { deps.plugins.trust(row.packageName, row.signatureHashes.toSet()) }
            _state.value = _state.value.copy(message = "已信任 ${row.packageName}")
            refresh()
        }
    }

    /** 撤销信任：停止调度与加载，但保留包、数据与凭据 */
    fun revokeTrust(row: PluginRow) {
        scope.launch {
            runCatching { deps.plugins.revokeTrust(row.pluginId) }
                .onFailure { AppLogger.error("停止使用插件 ${row.pluginId} 失败", it) }
            _state.value = _state.value.copy(message = "已停止使用 ${row.displayName}")
            refresh()
        }
    }

    /**
     * 卸载：删包并撤销信任。
     *
     * @param clearData true 时连私有数据、凭据与历史采集一起清掉；
     *   false 时保留，用户重装后数据与登录态都还在
     */
    fun uninstall(row: PluginRow, clearData: Boolean) {
        scope.launch {
            val result = runCatching { deps.plugins.uninstall(row.pluginId, clearData) }
            result.exceptionOrNull()?.let {
                AppLogger.error("卸载插件 ${row.pluginId} 失败", it)
            }
            _state.value = _state.value.copy(
                message = if (result.isSuccess) {
                    if (clearData) "已卸载 ${row.displayName} 并清除数据" else "已卸载 ${row.displayName}，数据已保留"
                } else {
                    "卸载失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                },
            )
            refresh()
        }
    }

    fun deletePending(row: PendingRow) {
        scope.launch {
            runCatching { deps.plugins.uninstallPending(row.archiveName) }
                .onFailure { AppLogger.error("删除插件包 ${row.archiveName} 失败", it) }
            _state.value = _state.value.copy(message = "已删除 ${row.archiveName}")
            refresh()
        }
    }

    /** 删除坏包。不提供这个入口，用户既装不上同名修复版，也去不掉这条记录 */
    fun deleteBroken(row: BrokenRow) {
        scope.launch {
            runCatching { deps.plugins.uninstallPending(row.archiveName) }
                .onFailure { AppLogger.error("删除坏包 ${row.archiveName} 失败", it) }
            _state.value = _state.value.copy(message = "已删除 ${row.archiveName}")
            refresh()
        }
    }

    /**
     * 清除某插件的全部凭据，需要重新登录。不可逆，UI 必须先确认。
     *
     * 删完库还要调一次插件的 `logout`：契约要求宿主在用户清除凭据时这么做
     * （见 `SourcePlugin.logout` 的注释）。插件通常把 token 缓存在内存里，
     * 只删库不通知它，下一次调用就会把旧凭据重新写回去——用户看到的是
     * 「清了但还显示已登录」，以为清除没生效。
     */
    fun clearCredentials(row: PluginRow) {
        scope.launch {
            val result = runCatching {
                val count = deps.plugins.clearCredentials(row.pluginId)
                // 登出失败不影响「已清除」这个结论：库里的凭据已经删掉了
                runCatching { deps.pluginInvoker.logout(row.pluginId) }
                    .onFailure { AppLogger.warn("清除凭据后通知插件 ${row.pluginId} 登出失败：${it.message}") }
                count
            }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { count ->
                        if (count > 0) "已清除 ${row.displayName} 的 $count 项凭据" else "该插件没有已保存的凭据"
                    },
                    onFailure = { error ->
                        AppLogger.error("清除插件 ${row.pluginId} 的凭据失败", error)
                        "清除凭据失败：${error.message ?: error::class.simpleName ?: "未知错误"}"
                    },
                ),
            )
            // 登录态变了，重新问一遍插件把它显示到列表行上
            refresh()
        }
    }

    /** 立即同步一次，把结果翻译成一句人话 */
    fun collectNow(row: PluginRow) {
        // 走 TaskCenter.launch 而不是 scope.launch：同步要走网络、可能十几秒，
        // 而 scope 是**页面**的生命周期（Android 是 viewModelScope，
        // 桌面端是 rememberCoroutineScope）。挂在它上面的话，用户点完
        // 「同步」随手切到别的页签，同步就被静默掐断——没有结果也没有提示。
        // 后台任务的定义就是它该活过页面，所以交给进程级的 TaskCenter 托管。
        TaskCenter.launch("同步插件", row.displayName) {
            // 取消要原样抛出，不能记成「同步失败」。
            // runCatching 会连 CancellationException 一起收走，而取消是控制流：
            // 用户切页签、退出应用时任务被取消，那不是插件的错，
            // 更不该在任务列表里留下一条红色的「Job was cancelled」。
            val outcome = try {
                deps.pluginInvoker.collectNow(row.pluginId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                CollectOutcome.Failed(e.message ?: "同步失败")
            }
            // 界面提示是一次性的 snackbar，属于「这次请求正好被看到」；
            // 任务列表是事后也能查的记录，两者失败口径略有不同：
            // 「没新数据」「未登录」都不算故障，不该在任务列表里标红
            val failure = when (outcome) {
                is CollectOutcome.Success, CollectOutcome.NoData -> null
                CollectOutcome.NotLoggedIn -> null
                is CollectOutcome.Failed -> outcome.reason
            }
            // 结果要写回界面状态，必须切回主线程：任务体跑在 Dispatchers.Default 上
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    message = when (outcome) {
                        is CollectOutcome.Success -> "同步完成，写入 ${outcome.written} 项"
                        CollectOutcome.NoData -> "同步完成，这次没有拿到数据"
                        CollectOutcome.NotLoggedIn -> "请先登录 ${row.displayName}"
                        is CollectOutcome.Failed -> outcome.reason
                    },
                )
                // 列表已经靠 snapshots 自动更新，这里只是让健康度/登录态立刻重判。
                // 重扫结果若与旧盘面相等，StateFlow 不会再发；必须自己再算一遍行
                runCatching { deps.plugins.refresh() }
                reloadRows()
            }
            // 抛出去让 TaskCenter 记成失败；正常结束由 TaskCenter 自己收尾
            failure?.let { error(it) }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- 登录 ----

    /**
     * 开始登录（首次打开登录入口）。
     *
     * 传入 [LoginIntent.OPEN]：插件只读取并展示**可恢复的阶段**，
     * 不提交、不清空中间态。旧插件忽略该意图，退化为原先的空表单语义。
     */
    fun startLogin(row: PluginRow) {
        submitLogin(row.pluginId, row.displayName, emptyMap(), LoginIntent.OPEN)
    }

    fun updateLoginValue(name: String, value: String) {
        val current = _state.value.login ?: return
        _state.value = _state.value.copy(
            login = current.copy(values = current.values + (name to value), error = null),
        )
    }

    fun submitLoginForm() {
        val current = _state.value.login ?: return
        submitLogin(current.pluginId, current.displayName, current.values, LoginIntent.SUBMIT)
    }

    /**
     * 关闭登录表单。
     *
     * 只收起界面，**不清插件中间态**：用户可能只是暂时关掉，稍后还要接着填。
     * 真正放弃走 [abandonLogin]。
     */
    fun cancelLogin() {
        _state.value = _state.value.copy(login = null)
    }

    /**
     * 退回「重新输入账号」。
     *
     * 传入 [LoginIntent.RESTART]：插件清理当前中间态并返回首轮。
     * 宿主不自己清插件状态：中间状态在插件那边，宿主碰不到。
     */
    fun restartLogin() {
        val current = _state.value.login ?: return
        submitLogin(current.pluginId, current.displayName, emptyMap(), LoginIntent.RESTART)
    }

    /**
     * 显式放弃本轮登录：清理插件中间态并收起表单。
     *
     * 传入 [LoginIntent.ABANDON]，其应答只确认清理，**不触发自动采集**。
     */
    fun abandonLogin() {
        val current = _state.value.login ?: return
        submitLogin(current.pluginId, current.displayName, emptyMap(), LoginIntent.ABANDON)
    }

    private fun submitLogin(
        pluginId: String,
        displayName: String,
        inputs: Map<String, String>,
        intent: LoginIntent,
    ) {
        scope.launch {
            // 已显示表单时先标记 busy：登录要发网络请求，不能让人连点提交
            _state.value.login?.let { current ->
                _state.value = _state.value.copy(login = current.copy(busy = true, error = null))
            }

            val result = runCatching {
                deps.pluginInvoker.login(pluginId, inputs, LoginOptions(intent))
            }.getOrElse { LoginResult.Failure(it.message ?: "登录时出错") }

            // 放弃登录的应答只表示「中间态已清理」，不是一次登录结果：
            // 若按 Success 处理就会收掉表单并触发一次采集，与用户意图相反
            if (intent == LoginIntent.ABANDON) {
                _state.value = _state.value.copy(
                    login = null,
                    message = if (result is LoginResult.Failure) {
                        "$displayName 放弃登录时出错：${result.message}"
                    } else {
                        "已退出 $displayName 的登录"
                    },
                )
                reloadRows()
                return@launch
            }

            when (result) {
                is LoginResult.Success -> {
                    _state.value = _state.value.copy(login = null, message = "$displayName 登录成功")
                    refresh()
                    // 登录成功后立刻同步一次。
                    //
                    // 健康度存在 `source_health` 表里，是**上一次采集的快照**：
                    // 上一轮因为未登录失败过，那里就留着一条「登录已失效，请重新登录」。
                    // 不重新采集就覆盖不掉，卡片会同时显示「已登录」和红色的失效提示，
                    // 看起来像是登录没生效。采集成功会 markHealth(healthy = true) 抹平它。
                    _state.value.installed.firstOrNull { it.pluginId == pluginId }
                        ?.let { collectNow(it) }
                }

                is LoginResult.NeedInput -> {
                    // 保留用户已填的值：验证码这轮要重填，账号密码不该让他再输一遍
                    val kept = _state.value.login?.values.orEmpty()
                        .filterKeys { name -> result.fields.any { it.name == name } }

                    // 表单里没有账号字段 = 已经过了第一轮（验证码、二次验证）。
                    // 这一步必须留一条回账号密码的路，否则用户卡死在验证码界面
                    val hasAccountField = result.fields.any { it.name == "account" }

                    _state.value = _state.value.copy(
                        login = LoginUi(
                            pluginId = pluginId,
                            displayName = displayName,
                            prompt = result.prompt,
                            fields = result.fields,
                            captchaImage = result.captchaImage,
                            values = kept,
                            canRestart = !hasAccountField,
                        ),
                    )
                }

                is LoginResult.Failure -> {
                    val form = _state.value.login
                    _state.value = if (form == null) {
                        // 第一轮就失败：没有表单可显示错误，只能走提示条，
                        // 否则用户点了登录却什么都看不到
                        _state.value.copy(message = "$displayName 登录失败：${result.message}")
                    } else {
                        _state.value.copy(login = form.copy(busy = false, error = result.message))
                    }
                }
            }
        }
    }

    /**
     * 登出：让插件清掉自己名下的凭据。
     *
     * 与「清除凭据」的区别见 `PluginScreen` 的确认文案：登出是**插件自己**
     * 执行的注销（清多少、清哪些由插件决定），清除凭据是**宿主直接删库**，
     * 不依赖插件实现。两者都会在结束时刷一次列表，登录态才跟得上。
     */
    fun logout(row: PluginRow) {
        scope.launch {
            runCatching { deps.pluginInvoker.logout(row.pluginId) }
                .onFailure { AppLogger.warn("登出插件 ${row.pluginId} 失败：${it.message}") }
            _state.value = _state.value.copy(message = "已退出 ${row.displayName}")
            refresh()
        }
    }

    private companion object {
        /**
         * 等「宿主已扫过的盘面」的上限。
         *
         * 页面重建时实现方给的是 `StateFlow`，当前值会立刻到手，几乎不耗时。
         * 首启时这个上限要覆盖宿主的首扫：订阅 `snapshots()` 本身就会触发一次
         * 扫描（解压 + 建 QuickJS 运行时，可能要几秒），等它出结果比在超时后
         * 再补一次全量重扫更划算——重扫会重复释放重建运行时。
         */
        const val CACHED_SNAPSHOT_WAIT_MS = 3_000L
    }
}

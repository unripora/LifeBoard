package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PluginRawPage
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.task.TaskCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 详情页展示的插件通用信息。
 *
 * 这些字段全部由宿主读出，**不来自插件自己**：插件在 UI 上说什么都可以，
 * 唯独「装的是哪个包、签名是什么、有没有被信任」必须由宿主说了算，
 * 否则详情页就成了插件自我认证的窗口。
 *
 * @property healthy null 表示还没采集过，不是「正常」也不是「异常」
 * @property healthMessage 仅在异常时有意义
 */
data class PluginDetailInfo(
    val pluginId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val metricCount: Int,
    val trusted: Boolean,
    val healthy: Boolean?,
    val healthMessage: String,
    val signatureHashes: List<String>,
    val needsLogin: Boolean,
    val loginText: String,
    val needsLoginAction: Boolean,
    /** 这次没问出登录态，界面应显示「登录状态待确认」而不是「未登录」 */
    val authStateUnknown: Boolean = false,
)

data class PluginDetailUiState(
    val loading: Boolean = true,
    /** 插件已不存在（例如在列表页停留期间被卸载），UI 应提示后退出 */
    val missing: Boolean = false,
    val info: PluginDetailInfo? = null,
    /** 插件自己决定要展示的区块；插件未实现 detailUi 时为空 */
    val blocks: List<UiBlock> = emptyList(),
    /** 「全部数据」：上次同步拿到的原始条目，按指标分组 */
    val rawGroups: List<RawRecordGroup> = emptyList(),
    /** 当前选中的时间段 */
    val rawRange: RawRange = RawRange.Today,
    /** 库里已有原始数据的日期范围 */
    val availableRange: Pair<String, String>? = null,
    /** 有动作正在执行，UI 应禁用全部插件按钮，防止连点并发调插件 */
    val busy: Boolean = false,
    /** 一次性提示，展示后由 UI 调 [PluginDetailViewModel.consumeMessage] 清掉 */
    val message: String? = null,
    /** 危险操作完成后置位，UI 据此关闭详情页回到列表 */
    val closed: Boolean = false,
)

/**
 * 插件详情页状态。
 *
 * 与 [com.lifeboard.shared.ui.plugin.PluginViewModel] 的关系：那个管「列表 + 安装 + 登录」，
 * 这个只管「单个插件的静态信息 + 危险操作」。两者都通过 [PlatformDependencies] 落地，
 * 不共享状态——详情页的操作完成后由调用方负责让列表重新刷新。
 *
 * **本页不做登录**：登录是多轮状态机（验证码、错误重试、表单位移），
 * 列表页已有一套完整实现，再开一个入口迟早不一致。详情页只读展示登录态。
 *
 * ## 为什么自己开 scope 而不是继承 ViewModel
 *
 * 放进 commonMain 就不能依赖 `androidx.lifecycle.ViewModel`——桌面端没有这个类。
 * 自己持有 [CoroutineScope] 的代价是必须显式 [close]，由承载它的界面在离开时调用。
 */
class PluginDetailViewModel(private val deps: PlatformDependencies) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PluginDetailUiState())
    val state: StateFlow<PluginDetailUiState> = _state.asStateFlow()

    /** 界面离开时调用，取消在途的协程 */
    fun close() {
        scope.cancel()
    }

    fun load(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(loading = true, missing = false)
            // 先刷新一次盘面：详情页可能是从旧列表点进来的，
            // 而插件目录随时可能被外部改动（热重启监听器也在这条链上）
            val summary = runCatching { findSummary(pluginId) }.getOrNull()
            if (summary == null) {
                _state.value = _state.value.copy(loading = false, missing = true)
                return@launch
            }

            // 插件区块单独取：它失败不该连带通用信息也打不开
            val blocks = if (summary.trusted) {
                runCatching { withContext(Dispatchers.IO) { deps.pluginInvoker.detailUi(pluginId) } }
                    .onFailure { AppLogger.warn("读取插件 $pluginId 的自定义区块失败：${it.message}") }
                    .getOrElse { emptyList() }
            } else {
                // 未信任时不去碰插件：调它的 detailUi 等于让不受信任的代码跑起来
                emptyList()
            }

            _state.value = _state.value.copy(
                loading = false,
                missing = false,
                info = buildInfo(summary),
                blocks = blocks,
            )
            reloadRaw(pluginId, _state.value.rawRange)
        }
    }

    /** 切换时间段，重新读该范围内的原始条目 */
    fun selectRange(range: RawRange) {
        val pluginId = _state.value.info?.pluginId ?: return
        scope.launch { reloadRaw(pluginId, range) }
    }

    /**
     * 按时间段读原始条目。
     *
     * 不调插件、不发网络请求：条目在采集时就已由插件上报并落库。
     * 详情页可能被频繁打开、时间段可能反复切换，每次都重新拉接口既慢又浪费。
     */
    private suspend fun reloadRaw(pluginId: String, range: RawRange) {
        val today = LocalDate.now()
        // 「全部」需要一个足够宽的起点；交给实现方按库里最早日期决定
        val (from, to) = when (range) {
            RawRange.Today -> today.toString() to today.toString()
            RawRange.Yesterday -> {
                val d = today.minusDays(1).toString()
                d to d
            }
            RawRange.Last7Days -> today.minusDays(6).toString() to today.toString()
            RawRange.Last30Days -> today.minusDays(29).toString() to today.toString()
            // All 传 null，由实现方把窗口开到自己最早的数据
            RawRange.All -> null to today.toString()
            is RawRange.Custom -> range.from to range.to
        }

        val page = runCatching {
            withContext(Dispatchers.IO) { deps.pluginDetailStore.rawRecords(pluginId, from, to) }
        }.onFailure {
            AppLogger.warn("读取插件 $pluginId 的原始记录失败：${it.message}")
        }.getOrElse { PluginRawPage() }

        _state.value = _state.value.copy(
            rawRange = range,
            rawGroups = page.groups.map {
                RawRecordGroup(
                    metricKey = it.metricKey,
                    displayName = it.displayName,
                    unit = it.unit,
                    records = it.records,
                )
            },
            availableRange = page.available,
        )
    }

    /**
     * 用户点了插件自己的按钮。
     *
     * [com.lifeboard.shared.platform.PluginInvoker.onAction] 已经把异常与 `TriggerSync`
     * 都处理好了，这里只负责把三种结果翻译成界面状态：换内容、弹提示、或什么都不做。
     */
    fun onAction(actionId: String) {
        val info = _state.value.info ?: return
        if (_state.value.busy) return

        // 走 TaskCenter 而不是 scope.launch。
        //
        // `scope` 是详情页的生命周期（桌面端 rememberCoroutineScope，
        // Android viewModelScope）。插件动作里最重的是「立即同步」——
        // 它要发网络请求、可能十几秒。挂在页面作用域上会有两个后果：
        //   1. 用户点完顺手返回列表，页面被销毁 → 同步被静默取消，
        //      界面上留下一句「Job was cancelled」，而数据其实刚拉回来
        //   2. 顶栏的圆环完全不知道有活在跑，看着像卡死
        // 后台任务的定义就是它该活过页面，所以交给进程级的 TaskCenter。
        TaskCenter.launch("插件操作", info.displayName ?: info.pluginId) {
            _state.value = _state.value.copy(busy = true)
            // 取消要原样抛出：runCatching 会把 CancellationException
            // 一起收成 null，再被翻译成「操作失败」——把「用户离开了」
            // 报成「操作失败」，排查方向就全错了
            val result = try {
                withContext(Dispatchers.IO) { deps.pluginInvoker.onAction(info.pluginId, actionId) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                AppLogger.warn("插件 ${info.pluginId} 的动作 $actionId 失败：${e.message}")
                null
            }

            // 界面状态必须回到主线程改
            withContext(Dispatchers.Main) {
                _state.value = when (result) {
                    // 成功也要有反馈：只换区块的话，用户点完「立即同步」
                    // 界面没有任何变化提示，只能靠猜
                    is ActionResult.RefreshUi ->
                        _state.value.copy(busy = false, blocks = result.blocks, message = "已完成")
                    is ActionResult.ShowMessage ->
                        _state.value.copy(busy = false, message = result.text)

                    null -> _state.value.copy(busy = false, message = "操作失败")
                    else -> _state.value.copy(busy = false, message = "已完成")
                }

                // 同步类动作可能改了健康度与登录态，通用信息要跟着变
                if (result is ActionResult.RefreshUi) {
                    findSummary(info.pluginId)?.let { summary ->
                        _state.value = _state.value.copy(info = buildInfo(summary))
                    }
                    // 同步顺带写出了新的原始条目，列表要重新读一遍，
                    // 否则用户刚同步完，下面的「全部数据」还是同步前的样子
                    reloadRaw(info.pluginId, _state.value.rawRange)
                    // 把这次同步真正落了多少原始条目报给用户：
                    // 「同步完成」但一条没写进去，和确实写了东西，是两种完全不同的结果
                    val raw = _state.value.rawGroups.sumOf { it.records.size }
                    _state.value = _state.value.copy(
                        message = if (raw > 0) {
                            "同步完成，共有 $raw 条原始记录"
                        } else {
                            "同步完成，这次没有拿到原始记录"
                        },
                    )
                }
            }
        }
    }

    /**
     * 组装通用信息。
     *
     * 健康度读宿主存的健康表而不是现调插件 `health()`：后者会真的发网络请求，
     * 用户点进来只是想看细节，不是要触发一次采集。
     */
    private suspend fun buildInfo(summary: PluginSummary): PluginDetailInfo {
        // 未信任时不去问健康度：问了也是走一遍未受信代码
        val health = if (summary.trusted) {
            runCatching {
                withContext(Dispatchers.IO) { deps.pluginInvoker.health(summary.pluginId) }
            }.onFailure {
                AppLogger.warn("读取插件 ${summary.pluginId} 健康度失败：${it.message}")
            }.getOrNull()
        } else {
            null
        }

        val healthy = when (health) {
            // 读不到就不下结论：null 在界面上是「还没采集过」，不是「异常」
            null -> null
            is PluginHealth.Healthy -> true
            is PluginHealth.Degraded, is PluginHealth.Broken -> false
        }
        val healthMessage = when (health) {
            is PluginHealth.Degraded -> health.message
            is PluginHealth.Broken -> health.message
            else -> ""
        }

        // 未信任时不去问登录态：问了也是 LoggedOut，会在界面上留一个点了没用的提示。
        // asked 单独记一下：只有「问了但没问出来」才该显示待确认，
        // 「没去问」（未信任/不需要登录）是另一回事
        val asked = summary.needsLogin && summary.trusted
        val auth = if (asked) {
            runCatching { deps.pluginInvoker.authState(summary.pluginId) }
                .onFailure {
                    AppLogger.warn("读取插件 ${summary.pluginId} 登录态失败：${it.message}")
                }
                .getOrNull()
        } else {
            null
        }

        return PluginDetailInfo(
            pluginId = summary.pluginId,
            displayName = summary.displayName,
            packageName = summary.packageName,
            versionName = summary.versionName,
            metricCount = summary.metricCount,
            trusted = summary.trusted,
            healthy = healthy,
            healthMessage = healthMessage,
            signatureHashes = summary.signatureHashes.sorted(),
            needsLogin = summary.needsLogin,
            loginText = authText(auth),
            // 「登录」只在插件确实说未登录/过期时给；问不出来时不给
            needsLoginAction = summary.needsLogin && (
                auth is AuthState.LoggedOut || auth is AuthState.Expired
                ),
            authStateUnknown = asked && auth == null,
        )
    }

    /** 从插件盘面里找一个插件。找不到说明已被卸载 */
    private suspend fun findSummary(pluginId: String): PluginSummary? {
        val list = withContext(Dispatchers.IO) { deps.plugins.refresh() }
        return list.firstOrNull { it.pluginId == pluginId }
    }

    private fun authText(auth: AuthState?): String = when (auth) {
        null -> ""
        is AuthState.LoggedIn -> auth.accountName?.let { "已登录：$it" } ?: "已登录"
        is AuthState.Expired -> auth.message ?: "登录已过期"
        AuthState.LoggedOut -> "未登录"
    }

    /** 撤销信任：停止调度，保留包、数据与凭据 */
    fun revokeTrust() {
        val info = _state.value.info ?: return
        scope.launch {
            withContext(Dispatchers.IO) { deps.plugins.revokeTrust(info.pluginId) }
            _state.value = _state.value.copy(
                message = "已停止使用 ${info.displayName}",
                closed = true,
            )
        }
    }

    /** 卸载：删包并撤销信任，保留凭据与私有数据（用户可能只是想重装） */
    fun uninstall() {
        val info = _state.value.info ?: return
        scope.launch {
            withContext(Dispatchers.IO) { deps.plugins.uninstall(info.pluginId) }
            _state.value = _state.value.copy(
                message = "已卸载 ${info.displayName}",
                closed = true,
            )
        }
    }

    /** 清空凭据不影响插件本体，留在本页刷新登录态即可 */
    fun clearCredentials() {
        val info = _state.value.info ?: return
        scope.launch {
            val count = runCatching {
                withContext(Dispatchers.IO) { deps.plugins.clearCredentials(info.pluginId) }
            }.getOrDefault(0)
            _state.value = _state.value.copy(
                message = if (count > 0) {
                    "已清除 ${info.displayName} 的 $count 项凭据"
                } else {
                    "该插件没有已保存的凭据"
                },
            )
            load(info.pluginId)
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

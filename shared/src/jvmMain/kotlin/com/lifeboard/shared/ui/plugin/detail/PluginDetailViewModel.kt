package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.ConversationBlock
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.PluginRawPage
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.plugin.session.PluginInvokerSessionEndpoint
import com.lifeboard.shared.plugin.session.PluginSessionController
import com.lifeboard.shared.plugin.session.PluginSessionRegistry
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

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

    /** 插件本次返回的原始顺序；用户切回「默认」时直接用它 */
    private var pluginBlocks: List<UiBlock> = emptyList()

    /** 当前插件的 id，用于读写顺序偏好 */
    private var currentPluginId: String = ""

    /** 当前会话控制器；插件没有会话区块时保持 null */
    private var sessionController: PluginSessionController? = null

    /**
     * 会话区块的绑定。
     *
     * 用稳定对象转发到当前控制器，而不是直接把控制器传进界面：
     * 区块在控制器装配之前就已经存在于组合树里，绑定若为 null，
     * 页面切回默认实现后就再也不会被重新订阅。
     */
    val conversation: ConversationBinding = object : ConversationBinding {
        override fun state(sessionId: String) =
            sessionController?.state(sessionId)
                ?: ConversationBinding.Unavailable.state(sessionId)

        override fun submit(sessionId: String, text: String) {
            sessionController?.submit(sessionId, text)
        }
    }

    private val _state = MutableStateFlow(PluginDetailUiState())
    val state: StateFlow<PluginDetailUiState> = _state.asStateFlow()

    /** 切换区块顺序策略：默认用插件顺序，自定义用本地保存的拖动结果 */
    fun setOrderMode(mode: BlockOrderMode) {
        if (currentPluginId.isEmpty() || _state.value.orderMode == mode) return
        deps.settings.putString(
            BLOCK_ORDER_MODE_KEY_PREFIX + currentPluginId,
            if (mode == BlockOrderMode.CUSTOM) "custom" else "default",
        )
        _state.value = _state.value.copy(
            orderMode = mode,
            blocks = displayBlocks(pluginBlocks, mode),
        )
    }

    /** 拖动结束：只在自定义模式下生效，并把新顺序落盘 */
    fun onBlockDrop(from: Int, to: Int) {
        if (_state.value.orderMode != BlockOrderMode.CUSTOM) return
        val moved = moveBlock(_state.value.blocks, from, to)
        if (moved == _state.value.blocks) return
        deps.settings.putString(
            BLOCK_ORDER_KEY_PREFIX + currentPluginId,
            Json.encodeToString(blockOrderKeys(moved)),
        )
        _state.value = _state.value.copy(blocks = moved)
    }

    private fun readOrderMode(pluginId: String): BlockOrderMode =
        if (deps.settings.getString(BLOCK_ORDER_MODE_KEY_PREFIX + pluginId, "") == "custom") {
            BlockOrderMode.CUSTOM
        } else {
            BlockOrderMode.DEFAULT
        }

    private fun readSavedOrder(pluginId: String): List<String> {
        val text = deps.settings.getString(BLOCK_ORDER_KEY_PREFIX + pluginId, "")
        if (text.isEmpty()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
    }

    private fun displayBlocks(blocks: List<UiBlock>, mode: BlockOrderMode): List<UiBlock> =
        if (mode == BlockOrderMode.CUSTOM) applyBlockOrder(blocks, readSavedOrder(currentPluginId)) else blocks

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
                info = buildDetailInfo(deps, summary),
                orderMode = readOrderMode(pluginId),
                blocks = emptyList(),
            )
            this@PluginDetailViewModel.currentPluginId = pluginId
            pluginBlocks = blocks
            val ordered = displayBlocks(blocks, _state.value.orderMode)
            _state.value = _state.value.copy(blocks = ordered)
            startConversationIfNeeded(pluginId, ordered)
            reloadRaw(pluginId, _state.value.rawRange)
        }
    }

    /**
     * 区块里出现 `conversation` 时装配并启动会话。
     *
     * 一个插件只起一条会话（首期单会话）：同一插件再次进入详情页时，
     * 注册表会复用已在运行的控制器，消息不会丢。
     */
    private fun startConversationIfNeeded(pluginId: String, blocks: List<UiBlock>) {
        val block = blocks.filterIsInstance<ConversationBlock>().firstOrNull() ?: return
        val controller = PluginSessionRegistry.controllerFor(pluginId) {
            PluginInvokerSessionEndpoint(deps.pluginInvoker, pluginId)
        }
        sessionController = controller
        if (!controller.isActive) {
            controller.startAsync(block.sessionId, SessionConfig())
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
    fun onAction(actionId: String, value: String? = null) {
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

            // 先订阅再调用：增量流没有重放，订阅晚了会丢掉开头几段。
            // 订阅只覆盖这一次操作——否则插件在别处（例如会话后台循环）
            // 推送的内容会串进当前这条消息。
            val streamJob = launch {
                deps.pluginInvoker.streamDeltas(info.pluginId).collect { delta ->
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            blocks = appendStreamDelta(_state.value.blocks, delta),
                        )
                    }
                }
            }

            // 取消要原样抛出：runCatching 会把 CancellationException
            // 一起收成 null，再被翻译成「操作失败」——把「用户离开了」
            // 报成「操作失败」，排查方向就全错了
            val result = try {
                withContext(Dispatchers.IO) { deps.pluginInvoker.onAction(info.pluginId, actionId, value) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                AppLogger.warn("插件 ${info.pluginId} 的动作 $actionId 失败：${e.message}")
                null
            } finally {
                streamJob.cancel()
            }

            // 界面状态必须回到主线程改
            withContext(Dispatchers.Main) {
                _state.value = when (result) {
                    // 成功也要有反馈：只换区块的话，用户点完「立即同步」
                    // 界面没有任何变化提示，只能靠猜
                    is ActionResult.RefreshUi -> {
                        // 刷新后仍是同一批区块，只是内容变了：顺序偏好要重新套上，
                        // 否则用户调好的顺序每同步一次就被插件打回原形
                        pluginBlocks = result.blocks
                        _state.value.copy(
                            busy = false,
                            blocks = displayBlocks(result.blocks, _state.value.orderMode),
                            message = "已完成",
                        )
                    }
                    is ActionResult.ShowMessage ->
                        _state.value.copy(busy = false, message = result.text)

                    null -> _state.value.copy(busy = false, message = "操作失败")
                    else -> _state.value.copy(busy = false, message = "已完成")
                }

                // 插件可能是在这次操作之后才给出会话区块
                startConversationIfNeeded(info.pluginId, _state.value.blocks)

                // 同步类动作可能改了健康度与登录态，通用信息要跟着变
                if (result is ActionResult.RefreshUi) {
                    findSummary(info.pluginId)?.let { summary ->
                        _state.value = _state.value.copy(info = buildDetailInfo(deps, summary))
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

    /** 从插件盘面里找一个插件。找不到说明已被卸载 */
    private suspend fun findSummary(pluginId: String): PluginSummary? {
        val list = withContext(Dispatchers.IO) { deps.plugins.refresh() }
        return list.firstOrNull { it.pluginId == pluginId }
    }

    /** 撤销信任：停止调度，保留包、数据与凭据 */
    fun revokeTrust() {
        val info = _state.value.info ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { deps.plugins.revokeTrust(info.pluginId) } }
                .onFailure { AppLogger.error("停止使用插件 ${info.pluginId} 失败", it) }
            _state.value = _state.value.copy(
                message = "已停止使用 ${info.displayName}",
                closed = true,
            )
            // 插件已不该再被调用，会话也要跟着停：留着一个继续跑的对话，
            // 等于让「已停止使用」的代码还在后台收发消息
            sessionController?.stopAsync()
            PluginSessionRegistry.remove(info.pluginId)
            sessionController = null
        }
    }

    /**
     * 卸载：删包并撤销信任。
     *
     * @param clearData true 时连私有数据、凭据与历史采集一起清掉
     */
    fun uninstall(clearData: Boolean) {
        val info = _state.value.info ?: return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { deps.plugins.uninstall(info.pluginId, clearData) }
            }
            result.exceptionOrNull()?.let { AppLogger.error("卸载插件 ${info.pluginId} 失败", it) }
            _state.value = _state.value.copy(
                message = if (result.isSuccess) {
                    if (clearData) "已卸载 ${info.displayName} 并清除数据" else "已卸载 ${info.displayName}，数据已保留"
                } else {
                    "卸载失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                },
                // 失败时留在页面上：用户需要看到原因，也需要能重试
                closed = result.isSuccess,
            )
            if (result.isSuccess) {
                sessionController?.stopAsync()
                PluginSessionRegistry.remove(info.pluginId)
                sessionController = null
            }
        }
    }

    /**
     * 清除插件产生的全部数据（私有目录、凭据、历史采集），但保留插件包。
     *
     * 清完只重读「全部数据」这一块，**不**重新 `load()`：
     * `load()` 会再进一次插件（`detailUi` / `authState`）并触发全量重扫，
     * 而这里刚把它的数据删干净，再去问它只会让界面闪一下、还可能
     * 让插件把缓存又写回来。
     */
    fun clearPluginData() {
        val info = _state.value.info ?: return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { deps.plugins.clearPluginData(info.pluginId) }
            }
            result.exceptionOrNull()?.let { AppLogger.error("清除插件 ${info.pluginId} 的数据失败", it) }
            _state.value = _state.value.copy(
                message = if (result.isSuccess) {
                    "已清除 ${info.displayName} 的数据，需要重新登录"
                } else {
                    "清除数据失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                },
                // 登录态一并没了，本地直接反映，别再去问插件
                info = if (result.isSuccess) {
                    _state.value.info?.copy(
                        loginText = if (info.needsLogin) "未登录" else "",
                        needsLoginAction = info.needsLogin,
                        authStateUnknown = false,
                    )
                } else {
                    _state.value.info
                },
            )
            reloadRaw(info.pluginId, _state.value.rawRange)
        }
    }

    /**
     * 清空凭据不影响插件本体，就地更新登录态即可。
     *
     * 这里**刻意不调 `load()`**：那会重新扫一遍插件目录、再进一次插件问
     * `authState`，而刚删掉凭据就立刻去问它，既慢又容易让界面闪回「待确认」。
     * 用户想看的结论很明确——现在是未登录。
     *
     * 删完还要通知插件登出（契约要求，见 `SourcePlugin.logout`）：
     * 插件通常把 token 缓存在内存里，不通知它，下一次调用可能把旧凭据写回来。
     */
    fun clearCredentials() {
        val info = _state.value.info ?: return
        scope.launch {
            val result = runCatching {
                val count = withContext(Dispatchers.IO) { deps.plugins.clearCredentials(info.pluginId) }
                runCatching { withContext(Dispatchers.IO) { deps.pluginInvoker.logout(info.pluginId) } }
                    .onFailure { AppLogger.warn("清除凭据后通知插件 ${info.pluginId} 登出失败：${it.message}") }
                count
            }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { count ->
                        if (count > 0) "已清除 ${info.displayName} 的 $count 项凭据" else "该插件没有已保存的凭据"
                    },
                    onFailure = { error ->
                        AppLogger.error("清除插件 ${info.pluginId} 的凭据失败", error)
                        "清除凭据失败：${error.message ?: error::class.simpleName ?: "未知错误"}"
                    },
                ),
                info = if (result.isSuccess) {
                    _state.value.info?.copy(
                        loginText = "未登录",
                        needsLoginAction = true,
                        authStateUnknown = false,
                    )
                } else {
                    _state.value.info
                },
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

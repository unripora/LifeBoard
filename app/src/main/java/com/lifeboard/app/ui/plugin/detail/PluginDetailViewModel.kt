package com.lifeboard.app.ui.plugin.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.plugin.PluginLoader
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.app.ui.task.TaskCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 详情页展示的插件通用信息。
 *
 * 这些字段全部由宿主从 [PluginLoader] 与 `source_health` 表读出，
 * **不来自插件自己**：插件在 UI 上说什么都可以，唯独「装的是哪个包、
 * 签名是什么、有没有被信任」必须由宿主说了算，否则详情页就成了
 * 插件自我认证的窗口。
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
)

data class PluginDetailUiState(
    val loading: Boolean = true,
    /** 插件已不存在（例如在列表页停留期间被卸载），UI 应提示后退出 */
    val missing: Boolean = false,
    val info: PluginDetailInfo? = null,
    /** 插件自己决定要展示的区块；插件未实现 detailUi 时为空 */
    val blocks: List<UiBlock> = emptyList(),
    /**
     * 「全部数据」：上次同步拿到的**原始条目**，按指标分组。
     *
     * 注意这不是聚合值——`MetricSeries` 那种「步数 1253」在仪表盘卡片上已经有了。
     * 这里要回答的是「插件这次同步到底返回了哪些条目」，
     * 所以展示的是插件在 `fetch` 里上报的逐条原始数据。
     */
    val rawGroups: List<RawRecordGroup> = emptyList(),
    /** 当前选中的时间段 */
    val rawRange: RawRange = RawRange.Today,
    /** 库里已有原始数据的日期范围，用于禁用没有数据的时间段 */
    val availableRange: Pair<String, String>? = null,
    /** 有动作正在执行，UI 应禁用全部插件按钮，防止连点并发调插件 */
    val busy: Boolean = false,
    /** 一次性提示，展示后由 UI 调 [PluginDetailViewModel.consumeMessage] 清掉 */
    val message: String? = null,
    /** 危险操作完成后置位，UI 据此关闭详情页回到列表 */
    val closed: Boolean = false,
)

/**
 * 一个指标的原始条目组。
 *
 * @property metricKey 指标键
 * @property displayName 展示名，取自插件声明的 [com.lifeboard.plugin.api.MetricSpec]
 * @property unit 单位
 * @property records 原始条目，按时间升序
 */
data class RawRecordGroup(
    val metricKey: String,
    val displayName: String,
    val unit: String,
    val records: List<com.lifeboard.plugin.api.RawRecord>,
)

/**
 * 「全部数据」块的时间段。
 *
 * 用枚举而不是直接传日期范围：界面上的快捷按钮是有限的几种，
 * 枚举能让「选中态」和「按钮文案」都从同一处来，不会出现按钮说「近 7 天」
 * 而实际查了 8 天这类不一致。
 *
 * [Custom] 承载弹窗里选的具体起止日期；[from]/[to] 用 `yyyy-MM-dd` 字符串，
 * 与库里 `date` 列的存储格式一致，可以直接做 BETWEEN 比较。
 */
sealed interface RawRange {
    /** 展示用的按钮文案 */
    val label: String

    data object Today : RawRange {
        override val label = "今天"
    }

    data object Yesterday : RawRange {
        override val label = "昨天"
    }

    data object Last7Days : RawRange {
        override val label = "近 7 天"
    }

    data object Last30Days : RawRange {
        override val label = "近 30 天"
    }

    data object All : RawRange {
        override val label = "全部"
    }

    /**
     * 自定义起止日期。
     *
     * @property from 起始日期（含），`yyyy-MM-dd`
     * @property to 结束日期（含），`yyyy-MM-dd`
     */
    data class Custom(val from: String, val to: String) : RawRange {
        // 按钮上显示区间本身，用户一眼能确认自己选的是哪段
        override val label get() = if (from == to) from else "$from ~ $to"
    }
}

/**
 * 插件详情页状态。
 *
 * 与 [com.lifeboard.app.ui.plugin.PluginViewModel] 的关系：那个管「列表 + 安装 + 登录」，
 * 这个只管「单个插件的静态信息 + 危险操作」。两者都通过 [PluginManager] 落地，
 * 不共享状态——详情页的操作完成后由调用方负责让列表重新 [com.lifeboard.app.ui.plugin.PluginViewModel.refresh]。
 *
 * **本页不做登录**：登录是多轮状态机（验证码、错误重试、表单位移），
 * 列表页已有一套完整实现，再开一个入口迟早不一致。详情页只读展示登录态。
 */
class PluginDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PluginDetailUiState())
    val state: StateFlow<PluginDetailUiState> = _state.asStateFlow()

    fun load(pluginId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, missing = false)
            val ctx = getApplication<Application>()
            val info = withContext(Dispatchers.IO) { readInfo(ctx, pluginId) }
            // 插件区块单独取：它失败不该连带通用信息也打不开，
            // PluginManager.detailUi 内部已兜异常，这里拿到的要么是内容要么是空列表
            val blocks = if (info == null) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) { PluginManager.detailUi(ctx, pluginId) }
            }
            _state.value = _state.value.copy(
                loading = false,
                missing = info == null,
                info = info,
                blocks = blocks,
            )
            if (info != null) reloadRaw(pluginId, _state.value.rawRange)
        }
    }

    /** 切换时间段，重新读该范围内的原始条目 */
    fun selectRange(range: RawRange) {
        val pluginId = _state.value.info?.pluginId ?: return
        viewModelScope.launch { reloadRaw(pluginId, range) }
    }

    /**
     * 按时间段读原始条目。
     *
     * 不调插件、不发网络请求：条目在采集时就已由插件上报并落库。
     * 详情页可能被频繁打开、时间段可能反复切换，每次都重新拉接口既慢又浪费。
     *
     * [RawRange.All] 不等价于「从有数据的第一天到今天」再拼一个超长区间：
     * 直接查一个足够宽的窗口，库里没有的日期自然不会返回。
     */
    private suspend fun reloadRaw(pluginId: String, range: RawRange) {
        val ctx = getApplication<Application>()
        val db = LifeBoardDatabase.get(ctx)
        val dao = db.pluginRawRecordDao()
        val queryDao = db.pluginMetricQueryDao()
        val today = LocalDate.now()

        // 「全部」不能只按条目表的最早日期算：查过但从来没数据的指标
        // 在条目表里没有任何行，按条目算会让它永远进不了这个分支
        val earliest = listOfNotNull(dao.earliestDate(pluginId), queryDao.earliestDate(pluginId))
            .minOrNull()

        val (from, to) = when (range) {
            RawRange.Today -> today.toString() to today.toString()
            RawRange.Yesterday -> {
                val d = today.minusDays(1).toString()
                d to d
            }
            RawRange.Last7Days -> today.minusDays(6).toString() to today.toString()
            RawRange.Last30Days -> today.minusDays(29).toString() to today.toString()
            RawRange.All -> earliest to today.toString()
            is RawRange.Custom -> range.from to range.to
        }

        val result = withContext(Dispatchers.IO) {
            if (from == null) {
                // 一个日期都没有：这个插件从没上报过，也没有查询痕迹
                Triple(emptyList<RawRecordGroup>(), null, null)
            } else {
                val rows = dao.getByRange(pluginId, from, to)
                // 这段时间内被查询过的指标：即使一条条目都没有（接口返回 0 条），
                // 也要出现在列表里并标注「暂无」，否则用户会以为插件漏采了这个指标
                val queried = queryDao.metricsInRange(pluginId, from, to)
                val specs = PluginLoader.find(pluginId)
                    ?.plugin?.metrics.orEmpty().associateBy { it.metricKey }
                Triple(
                    rows.groupToGroups(specs, queried),
                    earliest,
                    dao.latestDate(pluginId),
                )
            }
        }

        _state.value = _state.value.copy(
            rawRange = range,
            rawGroups = result.first,
            availableRange = if (result.second != null && result.third != null) {
                result.second!! to result.third!!
            } else {
                null
            },
        )
    }

    /**
     * 把库里的行按指标分组。
     *
     * 同一个指标在选中的时间段里可能跨多天，这里**不再按天拆开**——
     * 用户选「近 7 天」是想看这 7 天的采样点放在一起，
     * 每天再套一层分组会多出一堆只装几行的标题。
     *
     * [queried] 是这段时间内「查过」的指标键。它们即使一条条目都没有也要成组
     * （`records` 为空），界面据此显示「暂无」——否则接口返回 0 条的指标
     * 会整个消失，用户无法判断是没数据还是漏采了。
     *
     * 排序按插件声明的顺序：让「步数、睡眠、心率…」的位置固定，
     * 而不是随这次有没有数据而上下浮动。
     */
    private fun List<com.lifeboard.server.db.PluginRawRecordEntity>.groupToGroups(
        specs: Map<String, com.lifeboard.plugin.api.MetricSpec>,
        queried: List<String>,
    ): List<RawRecordGroup> {
        val byMetric = groupBy { it.metricKey }

        // 有条目的 + 查过但空的，合在一起去重
        val keys = (byMetric.keys + queried).distinct()

        // 插件声明顺序优先；未声明的（插件已删掉这个指标）排在后面，保持稳定
        val order = specs.keys.withIndex().associate { (index, key) -> key to index }
        val ordered = keys.sortedBy { order[it] ?: Int.MAX_VALUE }

        return ordered.map { metricKey ->
            val spec = specs[metricKey]
            RawRecordGroup(
                metricKey = metricKey,
                // 插件已不再声明这个指标时仍展示原始 key：数据是真实采到的，
                // 用 key 至少能看出它是谁；直接丢掉反而像数据丢了
                displayName = spec?.displayName ?: metricKey,
                unit = spec?.unit.orEmpty(),
                records = byMetric[metricKey]
                    .orEmpty()
                    .sortedWith(compareBy({ it.date }, { it.seq }))
                    .map { RawRecord(timestamp = it.timestamp, value = it.value, detail = it.detail) },
            )
        }
    }

    /**
     * 用户点了插件自己的按钮。
     *
     * [PluginManager.onAction] 已经把异常与 `TriggerSync` 都处理好了，
     * 这里只负责把三种结果翻译成界面状态：换内容、弹提示、或什么都不做。
     */
    fun onAction(actionId: String) {
        val info = _state.value.info ?: return
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val ctx = getApplication<Application>()
            // 同步动作会跑完整个采集链路（各指标一次网络请求 + 写库），
            // 回补要拉 30 天，几秒到几十秒都正常，这期间顶栏指示器该转
            val handle = TaskCenter.begin(
                "${info.displayName}：同步",
                "拉取指标与原始条目",
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    PluginManager.onAction(ctx, info.pluginId, actionId)
                }

                _state.value = when (result) {
                    is ActionResult.RefreshUi ->
                        // 成功也要有反馈：之前这里只换了区块，用户点完「立即同步」
                        // 界面没有任何变化提示，只能靠猜
                        _state.value.copy(busy = false, blocks = result.blocks, message = "同步完成")
                    is ActionResult.ShowMessage ->
                        _state.value.copy(busy = false, message = result.text)
                    // NoOp 与 TriggerSync：后者已由 PluginManager 转成前两种
                    else -> _state.value.copy(busy = false, message = "已完成")
                }

                if (result is ActionResult.ShowMessage) {
                    handle.fail(result.text)
                } else {
                    handle.finish()
                }

                // 同步类动作可能改了健康度与登录态，通用信息要跟着变
                if (result is ActionResult.RefreshUi) {
                    val fresh = withContext(Dispatchers.IO) { readInfo(ctx, info.pluginId) }
                    _state.value = _state.value.copy(info = fresh ?: _state.value.info)
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
            } catch (e: CancellationException) {
                handle.finish()
                throw e
            } catch (e: Exception) {
                handle.fail(e.message ?: "同步失败")
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /**
     * 组装通用信息。
     *
     * 健康度读 `source_health` 表而不是现调插件 `health()`：后者会真的发网络请求，
     * 用户点进来只是想看细节，不是要触发一次采集。
     */
    private suspend fun readInfo(ctx: Application, pluginId: String): PluginDetailInfo? {
        val plugin = PluginLoader.find(pluginId) ?: return null

        val health = LifeBoardDatabase.get(ctx).sourceHealthDao().getAll()
            .associateBy { it.sourceType }
        // 一个插件多个指标、每个指标一条健康记录：任意一条不健康就认为有问题
        val records = plugin.metrics.keys.mapNotNull { health["PLUGIN_$pluginId:$it"] }
        val healthy = when {
            records.isEmpty() -> null
            else -> records.all { it.healthy }
        }

        // 有凭据槽位就认为需要登录；为空说明插件不用账号，不该给它一个点了没用的状态
        val needsLogin = plugin.plugin.credentialSpec.slots.isNotEmpty()
        val auth = if (needsLogin && plugin.trusted) {
            runCatching { PluginManager.authState(ctx, pluginId) }.getOrNull()
        } else {
            null
        }

        return PluginDetailInfo(
            pluginId = pluginId,
            displayName = plugin.plugin.displayName,
            packageName = plugin.archive.packageName,
            versionName = plugin.archive.versionName,
            metricCount = plugin.metrics.size,
            trusted = plugin.trusted,
            healthy = healthy,
            healthMessage = records.firstOrNull { !it.healthy }?.message.orEmpty(),
            signatureHashes = plugin.archive.signatureHashes.sorted(),
            needsLogin = needsLogin,
            loginText = authText(auth),
            needsLoginAction = needsLogin && (auth == null ||
                auth is com.lifeboard.plugin.api.AuthState.LoggedOut ||
                auth is com.lifeboard.plugin.api.AuthState.Expired),
        )
    }

    private fun authText(auth: com.lifeboard.plugin.api.AuthState?): String = when (auth) {
        null -> ""
        is com.lifeboard.plugin.api.AuthState.LoggedIn ->
            auth.accountName?.let { "已登录：$it" } ?: "已登录"
        is com.lifeboard.plugin.api.AuthState.Expired -> auth.message ?: "登录已过期"
        com.lifeboard.plugin.api.AuthState.LoggedOut -> "未登录"
    }

    /** 撤销信任：停止调度，保留包、数据与凭据 */
    fun revokeTrust() {
        val info = _state.value.info ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.revokeTrust(ctx, info.pluginId) }
            _state.value = _state.value.copy(
                message = "已停止使用 ${info.displayName}",
                closed = true,
            )
        }
    }

    /** 卸载：删包并撤销信任，保留凭据与私有数据（用户可能只是想重装） */
    fun uninstall() {
        val info = _state.value.info ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.uninstall(ctx, info.pluginId) }
            _state.value = _state.value.copy(
                message = "已卸载 ${info.displayName}",
                closed = true,
            )
        }
    }

    /** 清空凭据不影响插件本体，留在本页刷新登录态即可 */
    fun clearCredentials() {
        val info = _state.value.info ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val count = withContext(Dispatchers.IO) {
                com.lifeboard.server.plugin.CredentialVault.clearPlugin(ctx, info.pluginId)
            }
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

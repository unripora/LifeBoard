package com.lifeboard.desktop.plugin

import com.lifeboard.core.model.SourceHealth
import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.MetricSeries
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.plugin.api.UiBlock
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.CollectOutcome
import com.lifeboard.shared.platform.PluginInvoker
import com.lifeboard.shared.platform.StreamingSourcePlugin
import com.lifeboard.shared.plugin.host.HostPluginContext
import com.lifeboard.shared.repository.MetricStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 桌面端 [PluginInvoker] 实现。
 *
 * ## 与 Android 那份的差异
 *
 * 结构一一对应，差异只在「采集怎么触发」：Android 走 `WorkManager` 的
 * `DailyAggregateWorker`，桌面走进程内的 `DesktopCollectionBackend`。
 * 其余（登录、详情区块、健康度、历史指标）逻辑相同，但**各自一份**——
 * 它们依赖各自的插件仓库与数据库装配，抽公共基类省下的代码
 * 不如「两个实现可以各自演进」来得重要。
 *
 * ## 失败一律返回而不是抛
 *
 * 契约要求如此：[PluginInvoker] 的方法失败是「插件实现的问题」，
 * 必须把原因原文透给用户。第三方 JS 插件未必守规矩（会抛异常），
 * 所以每个入口都包一层 `runCatching`，把异常翻译成可展示的失败。
 */
class DesktopPluginInvoker(
    private val repository: DesktopPluginRepository,
    private val metricStore: MetricStore,
    /**
     * 采一个插件。
     *
     * [collectRunner] 接的是宿主既有采集链路（桌面端为插件采集器 + 任务重判），
     * 不是直接调插件的 `fetch`：后者会绕过量纲校验、去重与健康度更新。
     *
     * 入参是 pluginId 而不是空的：详情页点的是「同步这个插件」，
     * 采全部插件既慢又会让结果失真——A 失败、B 成功时整体返回成功，
     * 界面上看起来 A 也同步过了。
     */
    private val collectRunner: suspend (String) -> Boolean,
    private val hostContextFactory: (pluginId: String) -> HostPluginContext,
) : PluginInvoker {

    override suspend fun login(pluginId: String, inputs: Map<String, String>): LoginResult {
        val entry = repository.find(pluginId)
            ?: return LoginResult.Failure("插件未加载，请重新扫描")
        if (!entry.trusted) return LoginResult.Failure("插件已停止使用，请先信任再登录")

        // 必须切到 IO：JS 插件的调用要等 HTTP 往返，
        // 内部是阻塞式等专用线程返回，占着调用方线程会把界面卡到超时
        return withContext(Dispatchers.IO) {
            runCatching {
                entry.plugin.login(hostContextFactory(pluginId), inputs)
            }.getOrElse { error ->
                AppLogger.error("插件登录失败：$pluginId", error)
                LoginResult.Failure("插件登录时出错：${error.message ?: error::class.simpleName}")
            }
        }
    }

    /**
     * 登出。
     *
     * 未信任时**不调用插件**：用户要的是「别再用它了」，
     * 这时候再跑它的登出逻辑，等于让一个已被停用的插件继续接触凭据。
     * 真要清干净走 `clearCredentials`，那是宿主直接删库。
     */
    override suspend fun logout(pluginId: String) {
        val entry = repository.find(pluginId) ?: return
        if (!entry.trusted) return
        withContext(Dispatchers.IO) {
            runCatching { entry.plugin.logout(hostContextFactory(pluginId)) }
        }
    }

    /**
     * 读登录态。
     *
     * 失败一律返回 `null` 而**不是** [AuthState.LoggedOut]：
     * 应用刚启动时插件运行时可能还在装配（或刚被热重启关掉），
     * 这时问一次必然失败。把它当成「未登录」会让界面写着未登录，
     * 而用户其实早就登录好了——之前就是这个症状。
     */
    override suspend fun authState(pluginId: String): AuthState? {
        val entry = repository.find(pluginId)
            ?: return AuthState.LoggedOut.also {
                AppLogger.debug("读取插件 $pluginId 登录态：插件未加载，按未登录处理")
            }
        // 未信任的插件不去执行它的脚本：那是第三方代码，没点过信任就不该跑
        if (!entry.trusted) return AuthState.LoggedOut
        return withContext(Dispatchers.IO) {
            runCatching { entry.plugin.authState(hostContextFactory(pluginId)) }
                .getOrElse { error ->
                    AppLogger.warn("插件 $pluginId 的 authState 调用失败：${error.message ?: error::class.simpleName}")
                    null
                }
        }
    }

    /** 插件自己的区块。插件未加载、未信任或抛异常时返回空列表 */
    override suspend fun detailUi(pluginId: String): List<UiBlock> {
        val entry = repository.find(pluginId) ?: return emptyList()
        if (!entry.trusted) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { entry.plugin.detailUi(hostContextFactory(pluginId)) }
                .getOrElse { emptyList() }
        }
    }

    /**
     * 执行详情页上的交互。
     *
     * [ActionResult.TriggerSync] 由本类接管：插件拿不到数据库入口，
     * 手动同步必须走宿主既有采集链路，否则健康度、去重与量纲校验都会被绕过。
     * 同步完成后重新问一次 [detailUi]，插件返回的区块就能反映最新数据。
     */
    override suspend fun onAction(
        pluginId: String,
        actionId: String,
        value: String?,
    ): ActionResult {
        val entry = repository.find(pluginId)
            ?: return ActionResult.ShowMessage("插件未加载，请重新扫描")
        if (!entry.trusted) return ActionResult.ShowMessage("插件已停止使用，请先信任再操作")

        val result = withContext(Dispatchers.IO) {
            try {
                entry.plugin.onAction(hostContextFactory(pluginId), actionId, value)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                ActionResult.ShowMessage(
                    "插件处理操作时出错：${error.message ?: error::class.simpleName}"
                )
            }
        }
        if (result !is ActionResult.TriggerSync) return result

        // 同上：取消要放行，不能收成 false 再报「同步未完成」
        val ok = try {
            collectRunner(pluginId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            AppLogger.error("插件 $pluginId 触发同步失败", error)
            false
        }
        if (!ok) {
            AppLogger.warn("插件 $pluginId 手动同步未成功，详见运行日志")
            return ActionResult.ShowMessage("同步未完成，请检查登录状态与网络后重试")
        }
        return ActionResult.RefreshUi(detailUi(pluginId))
    }

    /**
     * 增量输出。
     *
     * 返回的是插件实例上常驻的热流：**订阅之前**的推送会丢，调用方必须在
     * 发起调用前订阅。没实现 [StreamingSourcePlugin] 的插件返回空流，
     * 调用方不必写「支持 / 不支持」两条路径。
     */
    override fun streamDeltas(pluginId: String): Flow<String> =
        (repository.find(pluginId)?.plugin as? StreamingSourcePlugin)?.streamDeltas ?: emptyFlow()

    /**
     * 启动会话。
     *
     * 桌面端与 Android 共用插件契约，因此能力一致；真正决定「能不能连」
     * 的是插件自己（例如 DSH 需要账号与主机配置）。
     */
    override suspend fun sessionStart(pluginId: String, config: SessionConfig): Boolean {
        val entry = repository.find(pluginId) ?: return false
        if (!entry.trusted) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                entry.plugin.onSessionStart(hostContextFactory(pluginId), config)
            }.getOrElse {
                AppLogger.warn("插件 $pluginId 启动会话失败：${it.message ?: it::class.simpleName}")
                false
            }
        }
    }

    /** 投递一次会话操作；异常按「继续」降级，避免把插件故障当成用户取消 */
    override suspend fun sessionEvent(pluginId: String, event: SessionEvent): SessionAction {
        val entry = repository.find(pluginId) ?: return SessionAction.Continue
        if (!entry.trusted) return SessionAction.Stop("插件已停止使用")
        return withContext(Dispatchers.IO) {
            runCatching {
                entry.plugin.onSessionEvent(hostContextFactory(pluginId), event)
            }.getOrElse {
                AppLogger.warn("插件 $pluginId 处理会话事件失败：${it.message ?: it::class.simpleName}")
                SessionAction.Continue
            }
        }
    }

    override suspend fun sessionStop(pluginId: String) {
        val entry = repository.find(pluginId) ?: return
        if (!entry.trusted) return
        withContext(Dispatchers.IO) {
            runCatching { entry.plugin.onSessionStop(hostContextFactory(pluginId)) }
                .onFailure {
                    AppLogger.warn("插件 $pluginId 停止会话失败：${it.message ?: it::class.simpleName}")
                }
        }
    }

    override fun setSessionUiListener(
        pluginId: String,
        listener: ((SessionUiEvent) -> Unit)?,
    ) {
        repository.find(pluginId)?.plugin?.setSessionUiListener(listener)
    }

    /**
     * 健康度。
     *
     * 读 `source_health` 表而不是现问插件：详情页要的只是一个状态灯，
     * 为它跑一次脚本调用（可能触发网络往返）不划算。
     */
    override suspend fun health(pluginId: String): PluginHealth = withContext(Dispatchers.IO) {
        val entry = repository.find(pluginId)
            ?: return@withContext PluginHealth.Broken("插件未加载")
        if (!entry.trusted) return@withContext PluginHealth.Broken("插件已停止使用")

        val healthByKey = runCatching { metricStore.getAllSourceHealth() }
            .getOrElse { emptyList<SourceHealth>() }
            .associateBy { it.source.key }

        val records = entry.metrics.keys.mapNotNull {
            healthByKey[SourceRef.pluginKey(pluginId, it)]
        }
        val failed = records.firstOrNull { !it.healthy }
        when {
            failed != null -> PluginHealth.Broken(failed.message.ifBlank { "采集失败" })
            records.isEmpty() -> PluginHealth.Degraded("尚未采集")
            else -> PluginHealth.Healthy
        }
    }

    /**
     * 读历史指标。
     *
     * 直接查 `metric` 表：插件只负责「今天拉什么」，历史值是宿主自己攒的。
     * 缺的日期不补 0（契约要求），没数据的指标不进结果。
     */
    override suspend fun metricSeries(pluginId: String, days: Int): List<MetricSeries> =
        withContext(Dispatchers.IO) {
            if (days <= 0) return@withContext emptyList()
            val entry = repository.find(pluginId) ?: return@withContext emptyList()
            if (!entry.trusted) return@withContext emptyList()

            val today = LocalDate.now()
            val from = today.minusDays(days.toLong() - 1).toString()
            val to = today.toString()

            entry.metrics.values.mapNotNull { spec ->
                val rows = runCatching {
                    metricStore.getSourceRange(
                        sourceKey = SourceRef.pluginKey(pluginId, spec.metricKey),
                        // 量纲取插件自己声明的那个：猜会一路掉到 COUNT，
                        // 对「以分钟计的睡眠」读出来永远是空
                        bucket = spec.bucket.name,
                        from = from,
                        to = to,
                    )
                }.getOrElse { emptyList() }

                if (rows.isEmpty()) {
                    null
                } else {
                    MetricSeries(
                        metricKey = spec.metricKey,
                        displayName = spec.displayName,
                        unit = spec.unit,
                        samples = rows.sortedBy { it.date }
                            .map { MetricSample(date = it.date, value = it.value) },
                    )
                }
            }
        }

    /**
     * 一次采集。
     *
     * [collectRunner] 是宿主既有采集链路（桌面端为插件采集器 + 任务重判），
     * 不是直接调插件的 `fetch`：后者会绕过量纲校验、去重与健康度更新。
     *
     * 链路只回一个布尔，分不清「没数据」与「失败」，所以成功后再比对一次
     * 今天的库内容：一条都没有就是 [CollectOutcome.NoData]。
     */
    override suspend fun collectNow(pluginId: String): CollectOutcome {
        val entry = repository.find(pluginId)
            ?: return CollectOutcome.Failed("插件未加载，请重新扫描")
        if (!entry.trusted) return CollectOutcome.Failed("插件已停止使用，请先信任再操作")

        // 未登录时跑一遍采集只会白跑一趟并留下一条「失败」健康记录，
        // 让用户以为是插件的问题。只在插件确实声明了凭据槽位时才拦。
        // 问不出登录态（null）就放行：宁可白跑一次，也不要因为一次瞬时故障
        // 就拒绝掉用户明确点的「立即同步」
        if (entry.needsLogin && authState(pluginId) is AuthState.LoggedOut) {
            return CollectOutcome.NotLoggedIn
        }

        // 取消原样抛出：runCatching 会把 CancellationException 一起收成 false，
        // 再往上就变成「同步未完成，请检查网络」——用户切个页签就被告知网络有问题，
        // 排查方向完全错了。取消不是失败，让它按控制流正常向上传播。
        val ok = try {
            collectRunner(pluginId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            AppLogger.error("手动采集失败：$pluginId", error)
            false
        }
        if (!ok) return CollectOutcome.Failed("同步未完成，请检查网络或查看运行日志")

        val written = withContext(Dispatchers.IO) { countTodayMetrics(pluginId, entry.metrics.keys) }
        return if (written == 0) CollectOutcome.NoData else CollectOutcome.Success(written)
    }

    /**
     * 今天这个插件写了几条指标。
     *
     * 用 [SourceRef.parsePluginKey] 反解：`metric.sourceType` 存的是
     * `PLUGIN_{id}:{metric}` 拼好的字符串，表里没有分开的两列。
     */
    private suspend fun countTodayMetrics(pluginId: String, metricKeys: Set<String>): Int {
        val today = LocalDate.now().toString()
        return runCatching { metricStore.getMetricsByDate(today) }
            .getOrElse { emptyList() }
            .count { record ->
                val parsed = SourceRef.parsePluginKey(record.source.key)
                parsed != null && parsed.first == pluginId && parsed.second in metricKeys
            }
    }
}

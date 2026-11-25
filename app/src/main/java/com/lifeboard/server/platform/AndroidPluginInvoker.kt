package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.MetricSeries
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.SessionAction
import com.lifeboard.plugin.api.SessionConfig
import com.lifeboard.plugin.api.SessionEvent
import com.lifeboard.plugin.api.SessionUiEvent
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.plugin.api.PluginNode
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.server.remote.RemoteConnectionService
import com.lifeboard.shared.platform.CollectOutcome
import com.lifeboard.shared.platform.PluginInvoker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * [PluginInvoker] 的 Android 实现，全部委托给 [PluginManager] 与现有数据层。
 *
 * 与 `AndroidPluginHost` 同样的定位：**只做参数绑定与类型翻译**，
 * 判断逻辑（信任检查、异常兜底、Worker 接管）一行都不搬。
 */
class AndroidPluginInvoker(
    private val context: Context,
) : PluginInvoker {

    private val appContext = context.applicationContext

    override suspend fun login(pluginId: String, inputs: Map<String, String>): LoginResult =
        PluginManager.login(appContext, pluginId, inputs)

    override suspend fun logout(pluginId: String) {
        PluginManager.logout(appContext, pluginId)
    }

    override suspend fun authState(pluginId: String): AuthState? =
        PluginManager.authState(appContext, pluginId)

    override suspend fun detailUi(pluginId: String): PluginNode? =
        PluginManager.detailUi(appContext, pluginId)

    override suspend fun onAction(pluginId: String, actionId: String, value: String?): ActionResult =
        PluginManager.onAction(appContext, pluginId, actionId, value)

    /** 增量输出，同桌面端：热流，必须在发起调用前订阅 */
    override fun streamDeltas(pluginId: String): Flow<String> =
        PluginManager.streamDeltas(appContext, pluginId)

    override suspend fun sessionStart(pluginId: String, config: SessionConfig): Boolean {
        // 先起前台服务再连：反过来的话，握手期间用户息屏就可能被系统回收，
        // 表现是「第一次进详情页必失败、第二次成功」
        RemoteConnectionService.start(appContext, "正在连接…")
        val started = PluginManager.sessionStart(appContext, pluginId, config)
        if (started) {
            RemoteConnectionService.update(appContext, "会话已连接")
        } else {
            // 没连上就别留着一条「正在连接」的常驻通知
            RemoteConnectionService.stop(appContext)
        }
        return started
    }

    override suspend fun sessionEvent(pluginId: String, event: SessionEvent): SessionAction {
        val action = PluginManager.sessionEvent(appContext, pluginId, event)
        // 插件在事件处理里判定会话结束（例如用户发了 /exit）
        if (action is SessionAction.Stop) {
            RemoteConnectionService.stop(appContext)
        }
        return action
    }

    override suspend fun sessionStop(pluginId: String) {
        try {
            PluginManager.sessionStop(appContext, pluginId)
        } finally {
            // finally：插件停机抛异常也要撤掉通知，否则用户会看到
            // 一条永远消不掉的「会话已连接」
            RemoteConnectionService.stop(appContext)
        }
    }

    override fun setSessionUiListener(
        pluginId: String,
        listener: ((SessionUiEvent) -> Unit)?,
    ) = PluginManager.setSessionUiListener(appContext, pluginId, listener) {
        // 插件侧关闭会话时通知栏要同步消失：控制器已把会话标成结束，
        // 通知却还挂着「会话已连接」，用户会以为连接还在
        RemoteConnectionService.stop(appContext)
    }

    /**
     * 插件健康状态。
     *
     * 读 `source_health` 表而不是现问插件：详情页要的是一个状态灯，
     * 为它跑一次脚本调用（可能触发网络往返）不划算。
     * 表里的结论本来就是每次采集后写好的。真要看插件现在能不能用，走 [collectNow]。
     */
    override suspend fun health(pluginId: String): PluginHealth = withContext(Dispatchers.IO) {
        val loaded = PluginManager.findPlugin(appContext, pluginId)
            ?: return@withContext PluginHealth.Broken("插件未加载")
        if (!loaded.trusted) return@withContext PluginHealth.Broken("插件已停止使用")

        val healthByKey = DatabaseProviders.metricStore(appContext)
            .getAllSourceHealth()
            .associateBy { it.source.key }

        // 一个插件多个指标、每个指标一条健康记录：任意一条不健康就认为整体有问题，
        // 并把第一条失败原因原样带出——那通常是「登录失效」这类用户能自己处理的提示
        val records = loaded.metrics.keys.mapNotNull {
            healthByKey[SourceRef.pluginKey(pluginId, it)]
        }
        val failed = records.firstOrNull { !it.healthy }
        when {
            failed != null -> PluginHealth.Broken(failed.message.ifBlank { "采集失败" })
            // 一条记录都没有说明还没采集过：这不是「坏」，但也不能说「正常」
            records.isEmpty() -> PluginHealth.Degraded("尚未采集")
            else -> PluginHealth.Healthy
        }
    }

    /**
     * 读历史指标。
     *
     * 直接查 `metric` 表而不是让插件吐数据：插件只负责「今天拉什么」，
     * 历史值是宿主自己攒的。缺的日期不补 0（契约要求），没数据的指标不进结果。
     */
    override suspend fun metricSeries(pluginId: String, days: Int): List<MetricSeries> =
        withContext(Dispatchers.IO) {
            if (days <= 0) return@withContext emptyList()
            val loaded = PluginManager.findPlugin(appContext, pluginId)
                ?: return@withContext emptyList()
            if (!loaded.trusted) return@withContext emptyList()

            val today = LocalDate.now()
            val from = today.minusDays(days.toLong() - 1).toString()
            val to = today.toString()
            val store = DatabaseProviders.metricStore(appContext)

            loaded.metrics.values.mapNotNull { spec -> readSeries(store, pluginId, spec, from, to) }
        }

    /**
     * 一次采集。
     *
     * 走 `DailyAggregateWorker` 而不是直接调插件的 `fetch`：后者会绕过量纲校验、
     * 去重与健康度更新，用户手动点一次就可能写进脏数据。
     *
     * Worker 只回一个布尔，分不清「没数据」与「失败」。所以成功后再比对一次
     * 今天的库内容：一条都没有就是 [CollectOutcome.NoData]——这与「失败」
     * 对用户的意义完全不同，混在一起会让人以为插件坏了。
     */
    override suspend fun collectNow(pluginId: String): CollectOutcome {
        val loaded = PluginManager.findPlugin(appContext, pluginId)
            ?: return CollectOutcome.Failed("插件未加载，请重新扫描")
        if (!loaded.trusted) return CollectOutcome.Failed("插件已停止使用，请先信任再操作")

        // 未登录时跑 Worker 只会白跑一趟并留下一条「失败」健康记录，
        // 让用户以为是插件的问题。只在插件确实声明了凭据槽位时才拦。
        // 问不出登录态（null）就放行：宁可白跑一次，也不要因为一次瞬时故障
        // 就拒绝掉用户明确点的「立即同步」
        if (loaded.plugin.credentialSpec.slots.isNotEmpty()) {
            val auth = runCatching { PluginManager.authState(appContext, pluginId) }.getOrNull()
            if (auth is AuthState.LoggedOut) return CollectOutcome.NotLoggedIn
        }

        val ok = DailyAggregateWorker.collectNow(appContext)
        if (!ok) return CollectOutcome.Failed("同步未完成，请检查网络或查看运行日志")

        val written = countTodayMetrics(pluginId, loaded.metrics.keys)
        return if (written == 0) CollectOutcome.NoData else CollectOutcome.Success(written)
    }

    /**
     * 某个指标近 [from]..[to] 的全部有值日。
     *
     * 量纲必须取插件自己声明的 [MetricSpec.bucket]——猜会一路掉到 COUNT，
     * 对「以分钟计的睡眠」读出来永远是空。
     */
    private suspend fun readSeries(
        store: com.lifeboard.shared.repository.MetricStore,
        pluginId: String,
        spec: MetricSpec,
        from: String,
        to: String,
    ): MetricSeries? {
        val rows = store.getSourceRange(
            sourceKey = SourceRef.pluginKey(pluginId, spec.metricKey),
            bucket = spec.bucket.name,
            from = from,
            to = to,
        )
        if (rows.isEmpty()) return null
        return MetricSeries(
            metricKey = spec.metricKey,
            displayName = spec.displayName,
            unit = spec.unit,
            samples = rows.sortedBy { it.date }.map { MetricSample(date = it.date, value = it.value) },
        )
    }

    /**
     * 今天这个插件写了几条指标，用于区分「成功但无数据」与「成功有数据」。
     *
     * 用 [SourceRef.parsePluginKey] 反解而不是直接比字段：`metric.sourceType`
     * 存的是 `PLUGIN_{id}:{metric}` 这个拼好的字符串，表里没有分开的两列。
     */
    private suspend fun countTodayMetrics(pluginId: String, metricKeys: Set<String>): Int {
        val today = LocalDate.now().toString()
        return DatabaseProviders.metricStore(appContext)
            .getMetricsByDate(today)
            .count { record ->
                val parsed = SourceRef.parsePluginKey(record.source.key)
                parsed != null && parsed.first == pluginId && parsed.second in metricKeys
            }
    }
}

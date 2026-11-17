package com.lifeboard.server.health

import android.content.Context
import android.util.Log
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.server.plugin.PluginDataSource
import com.lifeboard.server.plugin.js.JsPluginLoader
import com.lifeboard.server.source.ScreenTimeDataSource
import com.lifeboard.shared.panel.SourceHealthEntry
import com.lifeboard.shared.panel.SourceHealthState
import com.lifeboard.shared.panel.toSourceHealth
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Android 侧的数据源健康度探测（M4.8）。
 *
 * 状态模型 [SourceHealthEntry] 与写库口径 [toSourceHealth] 在 `:shared`，
 * 这里只负责「怎么判断有没有权限」——那是平台相关的：
 * 要问使用情况访问权，桌面端根本没有这个权限。
 *
 * 判断依据集中在 `ScreenTimeDataSource.hasPermission` 与插件登录态两处，
 * 全部收在这一个文件里，避免界面与 Worker 各判一套。
 */
object SourceHealthChecker {

    private const val TAG = "LifeBoardSourceHealth"

    private const val ACTION_USAGE_ACCESS = "usage_access"

    /** 全部数据源状态。内置源只剩屏幕使用与手动打卡，其余由插件贡献 */
    fun checkAll(context: Context): List<SourceHealthEntry> {
        val screenOk = ScreenTimeDataSource(context).hasPermission()

        return listOf(
            SourceHealthEntry(
                source = builtinRef(SourceType.SCREEN_TIME),
                state = if (screenOk) SourceHealthState.OK else SourceHealthState.NO_PERMISSION,
                message = if (screenOk) "已授权，可统计屏幕使用时长" else "未授予「使用情况访问权」",
                actionKey = if (screenOk) null else ACTION_USAGE_ACCESS,
            ),
            SourceHealthEntry(
                source = builtinRef(SourceType.MANUAL),
                state = SourceHealthState.OK,
                message = "手动打卡，无需权限",
                actionKey = null,
            ),
        ) + customEntries(context) + pluginEntries(context)
    }

    /**
     * 插件源单独成组。
     *
     * 与内置源的区别在于「不健康的原因」完全由插件决定（登录过期、接口变了、契约不匹配），
     * 宿主不猜，直接展示插件返回的 message。加载失败的插件以 NOT_IMPLEMENTED 呈现，
     * 让用户看得到「为什么装了这个插件却用不了」。
     */
    private fun pluginEntries(context: Context): List<SourceHealthEntry> {
        val loaded = JsPluginLoader.loadedPlugins(context).flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                val ref = SourceRef.Plugin(
                    pluginId = plugin.pluginId,
                    metricKey = spec.metricKey,
                    displayName = "${plugin.plugin.displayName}·${spec.displayName}",
                    bucket = spec.bucket,
                )
                if (!plugin.trusted) {
                    SourceHealthEntry(
                        source = ref,
                        state = SourceHealthState.NOT_IMPLEMENTED,
                        message = "信任已撤销，已停止调度",
                        actionKey = null,
                    )
                } else {
                    // 这里只给静态结论：插件没撤销信任就算「可尝试采集」。
                    // 真实健康度（登录过期、接口变更）由 persistPluginHealth 调插件的
                    // suspend health() 后写进 source_health 表，面板以表里的为准。
                    SourceHealthEntry(
                        source = ref,
                        state = SourceHealthState.OK,
                        message = "插件源，健康度见最近一次采集结果",
                        actionKey = null,
                    )
                }
            }
        }
        val broken = JsPluginLoader.brokenPlugins(context).map { broken ->
            SourceHealthEntry(
                source = SourceRef.Builtin(
                    key = "PLUGIN_BROKEN_${broken.archiveName}",
                    displayName = broken.archiveName,
                    bucket = MetricBucket.FLAG,
                ),
                state = SourceHealthState.NOT_IMPLEMENTED,
                message = "插件加载失败：${broken.reason}",
                actionKey = null,
            )
        }
        return loaded + broken
    }

    /**
     * 自建源单独成组。
     *
     * 它们没有采集器，数据只能靠采集规则命中写入，所以状态是 [SourceHealthState.RULE_ONLY]，
     * 而不是「缺权限」或「未接入」——否则用户会去找一个根本不存在的授权开关。
     */
    private fun customEntries(context: Context): List<SourceHealthEntry> {
        val rows: List<CustomSourceRecord> = runCatching {
            runBlocking { DatabaseProviders.ruleStore(context).getCustomSources() }
        }.getOrDefault(emptyList())
        return rows.map { row ->
            SourceHealthEntry(
                // 自建源没有枚举，用它的引用承载 key 与展示名
                source = row.toRef(),
                state = SourceHealthState.RULE_ONLY,
                message = "自建源，仅由采集规则命中计数",
                actionKey = null,
                customName = row.displayName,
            )
        }
    }

    /** 打开某个源对应的设置页 */
    fun openSettings(context: Context, actionKey: String): Boolean = when (actionKey) {
        ACTION_USAGE_ACCESS -> SettingsNavigator.openUsageAccess(context)
        else -> false
    }

    /**
     * 把最新检查结果写回 source_health 表。
     *
     * 聚合 Worker 每天调一次，历史上"某源哪天开始失效"就有据可查，
     * 不用只靠用户截图反馈。
     *
     * 直连库而不走 MetricWriter：那套 Provider + JSONL 兜底是给
     * hook 进程用的，Worker 在主进程，直连即可且不会丢兜底。
     */
    suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        val metrics = DatabaseProviders.metricStore(context)
        val now = System.currentTimeMillis()
        val previous = metrics.getAllSourceHealth().associateBy { it.source.key }
        checkAll(context).toSourceHealth(previous, now).forEach { metrics.saveSourceHealth(it) }
        // 插件健康度只能在这里取：它要调插件的 suspend `health()`，
        // 而 checkAll 是同步的权限快照，塞不进去
        persistPluginHealth(context, metrics, now, previous)
    }

    /**
     * 单独写插件源的健康度。
     *
     * 为什么不并进 [checkAll]：`checkAll` 是同步的「权限是否齐全」快照，几微秒完成；
     * 而插件的 `health()` 可能要发网络请求，一慢就会拖住整个列表。
     * 这里在 suspend 上下文里逐个问插件，结果写进同一张 `source_health` 表，
     * 面板照常读表，不需要额外的 UI 逻辑。
     *
     * 单个插件抛异常不影响其他插件，也不影响整次持久化。
     */
    private suspend fun persistPluginHealth(
        context: Context,
        metrics: com.lifeboard.shared.repository.MetricStore,
        now: Long,
        previous: Map<String, SourceHealth>,
    ) {
        JsPluginLoader.loadedPlugins(context).flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                PluginDataSource(plugin, spec, context)
            }
        }.forEach { source ->
            val health = runCatching { source.health() }.getOrElse { error ->
                Log.w(TAG, "plugin health failed: ${source.source.key}", error)
                return@forEach
            }
            val key = health.source.key
            runCatching {
                metrics.saveSourceHealth(
                    SourceHealth(
                        source = health.source,
                        healthy = health.healthy,
                        lastSuccessAt = if (health.healthy) {
                            now
                        } else {
                            previous[key]?.lastSuccessAt ?: 0L
                        },
                        message = health.message,
                    ),
                )
            }.onFailure { Log.w(TAG, "persist plugin health failed: $key", it) }
        }
    }
}

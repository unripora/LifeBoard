package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.panel.PluginMetric
import com.lifeboard.shared.panel.PluginMetricSet
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PanelScope] 的 Android 实现（M3.2）。
 *
 * 面板业务逻辑已经全部搬进 `:shared`，这一层只补三样平台侧的东西：
 * 1. Store 从 [DatabaseProviders] 取（数据库位置是平台相关的）
 * 2. 插件指标声明从 [PluginManager] 取（只有 Android 有插件运行时）
 * 3. 任务写入后重排闹钟（桌面端没有系统闹钟，实现为空）
 *
 * 每次都新建一个实例而不是做单例：它自己不持有状态，
 * 内部的 Store 由 [DatabaseProviders] 保证复用同一个数据库连接，
 * 多建几个壳子的代价可以忽略，却省掉了「谁先初始化」的时序问题。
 */
class AndroidPanelScope(context: Context) : PanelScope {

    private val appContext = context.applicationContext

    override val taskStore: TaskStore get() = DatabaseProviders.taskStore(appContext)
    override val metricStore: MetricStore get() = DatabaseProviders.metricStore(appContext)
    override val ruleStore: RuleStore get() = DatabaseProviders.ruleStore(appContext)

    /**
     * 自建源。
     *
     * `getCustomSources()` 返回的是库里的行，要转成 [SourceRef.Custom] 才能参与解析。
     * 量纲存在行里（用户建源时选过），必须原样带出来：按名字猜会让「分钟数的自建源」
     * 被当成计数读。
     */
    override suspend fun customSources(): List<SourceRef.Custom> = withContext(Dispatchers.IO) {
        ruleStore.getCustomSources().map { row ->
            SourceRef.Custom(
                id = row.id,
                displayName = row.displayName,
                // 行里存的是枚举名；历史数据缺列时回落 COUNT 而不是抛异常，
                // 否则一个坏行会让整个选择器打不开
                bucket = MetricBucket.entries.firstOrNull { it.name == row.bucket }
                    ?: MetricBucket.COUNT,
            )
        }
    }

    /**
     * 插件声明的指标。
     *
     * 只收已信任的：撤销信任的插件已被停止调度，它声明的指标读出来永远是空，
     * 列进选择器等于让用户加一张注定没数据的卡。
     */
    override suspend fun pluginMetrics(): Map<String, PluginMetricSet> = withContext(Dispatchers.IO) {
        PluginManager.loaded(appContext)
            .filter { it.trusted }
            .associate { loaded ->
                loaded.pluginId to PluginMetricSet(
                    displayName = loaded.plugin.displayName,
                    metrics = loaded.metrics.mapValues { (_, spec) ->
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
     * 必须整个重排而不是只排这一条：修改可能涉及时段、重复规则、档位，
     * 而 [RemindScheduler] 的排布是「按任务集合整体算下一次触发」，
     * 单条重排会在跨任务的时间冲突上算错。
     */
    override suspend fun onTaskSaved(taskId: Long) = withContext(Dispatchers.IO) {
        RemindScheduler.rescheduleAll(appContext)
    }

    /** 任务删除后撤销它的闹钟与未完成通知 */
    override suspend fun onTaskDeleted(taskId: Long) = withContext(Dispatchers.IO) {
        RemindScheduler.cancelTask(appContext, taskId)
    }
}

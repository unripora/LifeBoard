package com.lifeboard.shared.panel

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.TaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 SQLDelight 的 [PanelScope] 通用实现（M3-3.3）。
 *
 * ## 与 `AndroidPanelScope` 的关系
 *
 * Android 侧仍然需要 `AndroidPanelScope`，因为那块多两件事：
 * 插件注册表只有 Android 有、任务保存后要排系统闹钟。
 * 但**数据读取部分两端完全相同**——都是同一套 `Sqldelight*Store`、同一个 `LifeBoardDb`。
 *
 * 所以这里只实现「数据」这一半，把「插件」与「副作用」留给子类覆盖：
 *
 * - [pluginMetrics] 默认返回空表。桌面端没有插件运行时，空表就是正确结果；
 * - [onTaskSaved] / [onTaskDeleted] 默认什么都不做。桌面端没有系统闹钟。
 *
 * Android 用 `AndroidPanelScope`，桌面可以直接用这个类。
 *
 * ## 为什么不把三件事都做成构造参数
 *
 * 那样每个调用点都要凑齐三个 lambda，而其中两个在某一端永远是空实现。
 * 用可覆盖的方法表达「这一端不需要」，比强制传空 lambda 清楚。
 */
open class DatabasePanelScope(
    private val stores: DatabaseStores,
) : PanelScope {

    override val taskStore: TaskStore get() = stores.taskStore
    override val metricStore: MetricStore get() = stores.metricStore
    override val ruleStore: RuleStore get() = stores.ruleStore

    /**
     * 自建源列表。
     *
     * 转成 [SourceRef.Custom] 才能参与 `SourceKeys` 解析。
     * 量纲必须从库里带出来——按名字猜会让「以分钟计的睡眠源」被当成计数。
     */
    override suspend fun customSources(): List<SourceRef.Custom> = withContext(Dispatchers.IO) {
        stores.ruleStore.getCustomSources().map { row ->
            SourceRef.Custom(
                id = row.id,
                displayName = row.displayName,
                // 坏行回落 COUNT 而不是抛异常：一个坏行不该让整个选择器打不开
                bucket = MetricBucket.entries
                    .firstOrNull { it.name == row.bucket }
                    ?: MetricBucket.COUNT,
            )
        }
    }

    /** 插件指标。默认空表——桌面端没有插件运行时 */
    override suspend fun pluginMetrics(): Map<String, PluginMetricSet> = emptyMap()

    /** 任务保存后的平台联动。默认空——桌面端没有系统闹钟 */
    override suspend fun onTaskSaved(taskId: Long) = Unit

    /** 任务删除后的平台清理。默认空 */
    override suspend fun onTaskDeleted(taskId: Long) = Unit
}

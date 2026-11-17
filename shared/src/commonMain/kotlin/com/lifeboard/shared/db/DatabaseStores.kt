package com.lifeboard.shared.db

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.LogStore
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.PluginStore
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.TaskStore
import com.lifeboard.shared.repository.sqldelight.SqldelightDashboardStore
import com.lifeboard.shared.repository.sqldelight.SqldelightLogStore
import com.lifeboard.shared.repository.sqldelight.SqldelightMetricStore
import com.lifeboard.shared.repository.sqldelight.SqldelightPluginStore
import com.lifeboard.shared.repository.sqldelight.SqldelightRuleStore
import com.lifeboard.shared.repository.sqldelight.SqldelightTaskStore

/**
 * 数据层装配（M3-3.3 从 `:server` 的 `DatabaseProviders` 抽出通用部分）。
 *
 * ## 为什么要有这个类
 *
 * 原来只有一个 `DatabaseProviders`，它吃 `Context`——因为 Android 需要它来决定
 * 库文件放在哪个私有目录。桌面端没有 `Context`，于是整份数据装配**复用不了**，
 * 这是「桌面端点开面板就崩」的根本原因。
 *
 * 抽出来的关键是发现：除了「打开库」这一步，其余全部与平台无关。
 * `SqldelightTaskStore` 这些实现本来就在 commonMain，它们只认 [LifeBoardDb]。
 * 所以这里持有 [LifeBoardDb]，把平台相关的「库在哪」交给调用方通过
 * [DatabaseDriverFactory] 提供。
 *
 * ## Store 为什么要缓存
 *
 * 除规则 Store 外都是每次新建。`Sqldelight*Store` 自身没有状态
 * （只包一层 queries 对象），新建的开销就是一次对象分配，
 * 但换来的是没有任何「谁先初始化」的时序问题。
 *
 * 规则 Store 单独缓存，是因为它同时被 [sourceLookup] 用到：
 * 如果每次解析自建源都新建一个，虽然便宜，但会让「同一个库同一个事务」的直觉失效，
 * 排查问题时容易误以为看到了不同连接的数据。
 *
 * ## 为什么 [sourceLookup] 直接读表
 *
 * `TaskStore` / `MetricStore` 要把库里存的 `sourceType` 字符串还原成 [SourceRef]。
 * 这一步如果走 `SourceRegistry` 会成环——那个类自己也依赖本对象。
 * 所以直接读 `custom_source` 表，是最短的一条路。
 */
class DatabaseStores(
    private val db: LifeBoardDb,
) {

    /** 规则 Store。被 [sourceLookup] 共用，所以缓存一份 */
    val ruleStore: SqldelightRuleStore by lazy { SqldelightRuleStore(db) }

    val taskStore: TaskStore by lazy { SqldelightTaskStore(db, sourceLookup) }

    val metricStore: MetricStore by lazy { SqldelightMetricStore(db, sourceLookup) }

    val dashboardStore: DashboardStore by lazy { SqldelightDashboardStore(db) }

    val logStore: LogStore by lazy { SqldelightLogStore(db) }

    val pluginStore: PluginStore by lazy { SqldelightPluginStore(db) }

    /**
     * 自建源解析端口。
     *
     * 量纲存在行里（用户建源时选过），必须原样带出来：
     * 按指标名猜会让「分钟数的自建源」被当成计数读，读出来永远是空。
     *
     * 行里的 `bucket` 存的是枚举名；历史数据缺列或写坏时回落 COUNT 而不是抛异常，
     * 否则一个坏行会让整个数据源选择器打不开。
     */
    val sourceLookup: SourceKeys.CustomSourceLookup
        get() = SourceKeys.CustomSourceLookup { id ->
            ruleStore.getCustomSource(id)?.let { row ->
                SourceRef.Custom(
                    id = row.id,
                    displayName = row.displayName,
                    bucket = MetricBucket.entries.firstOrNull { it.name == row.bucket }
                        ?: MetricSpec.bucketOf(row.bucket)
                        ?: MetricBucket.COUNT,
                )
            }
        }
}

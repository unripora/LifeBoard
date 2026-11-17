package com.lifeboard.server.db

import android.content.Context
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.db.DatabaseDriverFactory
import com.lifeboard.shared.db.LifeBoardDb
import com.lifeboard.shared.db.openDatabase
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
 * Android 侧的数据层装配点。
 *
 * ## 为什么收敛到一处
 *
 * 换成 SQLDelight 之前，每个业务类各自写 `LifeBoardDatabase.get(context).xxxDao()`。
 * 那种写法有 29 个副本，任何一次数据层改动（换库、换映射、加缓存）都得全改一遍，
 * 而且必然漏掉几处——漏掉的那几处在运行时才暴露，表现是「某个页面数据不动」。
 *
 * 现在所有数据访问都从这里取：驱动只有一份、Store 只有一份，
 * `LifeBoardDb` 的构造也只有一个入口。
 *
 * ## 为什么依赖 `CustomSourceLookup` 而不是反向查库
 *
 * `TaskStore` / `MetricStore` 要把 `sourceType` 字符串还原成 `SourceRef`，
 * 自建源那一支需要读 `custom_source`。如果让 shared 反向依赖 `:server`，
 * 桌面端就复用不了这段映射——那正是这次重构要消灭的分叉。
 * 所以抽成端口：这里实现它，shared 只认接口。
 */
object DatabaseProviders {

    /** 库文件名，与 Room 时代保持一致——升级不能换文件，否则老数据读不到 */
    const val NAME = "lifeboard.db"

    @Volatile
    private var db: LifeBoardDb? = null

    @Volatile
    private var rules: SqldelightRuleStore? = null

    /**
     * 打开（或复用）数据库。
     *
     * 双检锁：Provider 与 Worker 可能在不同线程同时首次访问，
     * 重复建驱动会让两条连接各自跑一遍迁移，第二条会因为
     * 「表已存在」直接失败。
     */
    fun database(context: Context): LifeBoardDb {
        db?.let { return it }
        return synchronized(this) {
            db ?: openDatabase(
                DatabaseDriverFactory(context.applicationContext),
                NAME,
            ).also { db = it }
        }
    }

    /**
     * 规则 Store。
     *
     * 单独缓存是因为它同时被 `CustomSourceLookup` 用到：每次解析自建源都新建一个
     * Store 对象虽然便宜，但会绕开「同一个库同一个事务」的直觉。
     */
    fun ruleStore(context: Context): SqldelightRuleStore {
        rules?.let { return it }
        return synchronized(this) {
            rules ?: SqldelightRuleStore(database(context)).also { rules = it }
        }
    }

    fun dashboardStore(context: Context): DashboardStore =
        SqldelightDashboardStore(database(context))

    fun metricStore(context: Context): MetricStore =
        SqldelightMetricStore(database(context), sourceLookup(context))

    fun logStore(context: Context): LogStore =
        SqldelightLogStore(database(context))

    fun pluginStore(context: Context): PluginStore =
        SqldelightPluginStore(database(context))

    fun taskStore(context: Context): TaskStore =
        SqldelightTaskStore(database(context), sourceLookup(context))

    /**
     * 自建源解析端口。
     *
     * 直接读 `custom_source` 表，而不是走 `SourceRegistry`：
     * 那一层自己也依赖本对象，反向调用会成环。
     */
    fun sourceLookup(context: Context): SourceKeys.CustomSourceLookup {
        val store = ruleStore(context)
        return SourceKeys.CustomSourceLookup { id ->
            store.getCustomSource(id)?.let { row ->
                SourceRef.Custom(
                    id = row.id,
                    displayName = row.displayName,
                    bucket = MetricSpec.bucketOf(row.bucket) ?: MetricBucket.COUNT,
                )
            }
        }
    }
}

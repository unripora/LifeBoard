package com.lifeboard.server.mihealth

import android.content.Context
import android.util.Log
import com.lifeboard.server.db.LifeBoardDatabase

/**
 * 小米健康从内置源迁移到插件的收尾清理。
 *
 * ## 为什么需要它
 *
 * M5 之前 `MI_HEALTH` 是内置数据源，每天往 `metric` 表写步数与睡眠，
 * 并往 `source_health` 写一行健康度。M5 起这些由 `.ext` 插件负责，
 * 内置实现已经删除——于是那些历史行**没有任何生产者**了。
 *
 * 面板会因此出现一张永远不更新的卡片，用户看到「小米健康」还在，
 * 数据却是几个月前的，会以为同步坏了。
 *
 * ## 为什么是删而不是留着
 *
 * 计划约定当前均为测试数据，不迁移老数据；且新旧实现写的是同一组 key
 * （`MI_HEALTH` / `STEPS` / `SLEEP_MINUTES`），留着也分不清哪行来自谁，
 * 反而会让「与内置实现同日数据一致」的对照验证做不了。
 *
 * ## 为什么要有标记
 *
 * 只清理一次。若不记标记，用户装了插件、插件正常出数之后，
 * 每次启动又会把插件新写的行当成「旧内置数据」删掉——
 * 那就变成了永久删数据。
 */
object MiHealthMigration {

    private const val TAG = "LifeBoardMiHealthMig"

    private const val PREFS = "lifeboard_migration"
    private const val KEY_DONE = "mi_health_legacy_rows_cleared_v1"

    /** 内置源在库里的 key，与 `SourceType.MI_HEALTH.name` 一致 */
    private const val LEGACY_SOURCE_KEY = "MI_HEALTH"

    /**
     * 清掉内置实现遗留的指标与健康度记录，只执行一次。
     *
     * 幂等：已执行过直接返回 0。不抛异常——这是启动路径上的收尾动作，
     * 失败最多是留几行旧数据，不该影响应用启动。
     */
    suspend fun cleanupLegacyRows(context: Context): Int {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return 0

        return runCatching {
            val db = LifeBoardDatabase.get(app)
            val metrics = db.metricDao().deleteBySource(LEGACY_SOURCE_KEY)
            val health = db.sourceHealthDao().deleteBySource(LEGACY_SOURCE_KEY)

            // 标记必须在删除成功之后写：先写标记再删，
            // 删失败就再也不会重试，旧数据永远留在库里
            prefs.edit().putBoolean(KEY_DONE, true).apply()

            Log.i(TAG, "cleared legacy MI_HEALTH rows: metrics=$metrics health=$health")
            metrics + health
        }.getOrElse { e ->
            Log.w(TAG, "cleanup legacy MI_HEALTH rows failed", e)
            0
        }
    }
}

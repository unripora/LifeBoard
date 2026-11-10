package com.lifeboard.server.collect

import android.content.Context

/**
 * 主动数据源的自动刷新周期。
 *
 * 存在 `SharedPreferences` 而不是 Room：只是一个枚举值，没有关系查询需求，
 * 为它加表还得跟一次 schema 迁移，不划算。
 *
 * 选项上限到「每天」：健康数据本身按天变化，比 15 分钟更勤只会白耗电，
 * 而 WorkManager 的周期任务最短也只能到 15 分钟。
 */
enum class RefreshInterval(val minutes: Long, val label: String) {

    /** 关闭自动刷新，只保留详情页的手动同步 */
    OFF(0, "关闭"),
    MIN_30(30, "每 30 分钟"),
    HOUR_1(60, "每小时"),
    HOUR_6(6 * 60, "每 6 小时"),
    HOUR_12(12 * 60, "每 12 小时"),
    DAILY(24 * 60, "每天（约 00:05）"),
    ;

    val enabled: Boolean get() = minutes > 0

    companion object {
        /** 默认沿用改造前的行为：每天一次 */
        val DEFAULT = DAILY

        /** 按名字反查，未知值（如降级回旧版本后留下的脏数据）回退默认 */
        fun fromName(name: String?): RefreshInterval =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 自动刷新周期的持久化。
 *
 * 放在 server 侧：调度（[DailyAggregateWorker]）和开机恢复（`BootReceiver`）
 * 都要读它，不能让 server 反过来依赖 app 的界面层。
 */
object CollectionPrefs {

    private const val FILE = "collection_prefs"
    private const val KEY_INTERVAL = "refresh_interval"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun interval(context: Context): RefreshInterval =
        RefreshInterval.fromName(prefs(context).getString(KEY_INTERVAL, null))

    /** 写入周期；调用方负责随后让 [DailyAggregateWorker] 重新注册或取消 */
    fun setInterval(context: Context, interval: RefreshInterval) {
        prefs(context).edit().putString(KEY_INTERVAL, interval.name).apply()
    }
}

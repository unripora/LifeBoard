package com.lifeboard.server.collect

import android.content.Context

/**
 * 主动数据源的自动刷新周期。
 *
 * ## 已经搬到 `shared`（M3-3.3b），这里只留名字
 *
 * 枚举实体移到了 `com.lifeboard.shared.platform.RefreshInterval`，
 * 因为设置页（要搬进 `commonMain`）需要把它列给用户选，
 * 而 `:shared` **不能依赖 `:server`**。
 *
 * 留 typealias 而不是改所有调用点：`:server` 侧有调度、开机恢复等十几处引用，
 * 逐个改只会制造噪声。行为与数值完全一致，因为它就是同一个枚举。
 */
typealias RefreshInterval = com.lifeboard.shared.platform.RefreshInterval

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

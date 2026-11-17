package com.lifeboard.shared.platform

/**
 * 主动数据源的自动刷新周期。
 *
 * ## 为什么这个枚举在 `shared` 而不是 `:server`
 *
 * 它原本在 `com.lifeboard.server.collect`。但设置页（要搬到 `commonMain`）需要
 * 把它列出来给用户选，而 `:shared` **不能依赖 `:server`**——那不是循环依赖
 * 的问题，而是依赖方向上「共享层」不该知道自己被哪个宿主用。
 *
 * 搬过来之后 `:server` 侧留 typealias 转发，调用点不用改。
 *
 * ## 选项上限到「每天」的理由
 *
 * 健康数据本身按天变化，比 15 分钟更勤只白耗电；而 WorkManager 的周期任务
 * 最短也只能到 15 分钟。桌面端没有这两个约束，但**选项必须一致**——
 * 两端读写同一份配置，一边能选「每 15 分钟」另一边没有，用户会以为设置丢了。
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
 * 主动数据源自动刷新的后端（M3-3.3b）。
 *
 * ## 为什么做成接口而不是让界面直接写 `CollectionPrefs`
 *
 * 设置页原本这么写：
 *
 * ```kotlin
 * CollectionPrefs.setInterval(context, option)
 * DailyAggregateWorker.schedule(context)   // 立即生效，不必重启
 * ```
 *
 * 两行里有三件平台相关的事：`SharedPreferences`、`WorkManager`、以及
 * 「改完要重排任务」这条规则。前两件各端实现不同（桌面端用
 * `ScheduledExecutorService`，然后 M4 换成更靠谱的调度），第三件**两端必须一样**
 * ——忘了重排就会出现「设置改了但刷新频率没变」，而这种漏调不会报错。
 *
 * 所以接口把「存周期」和「按新周期重排」收成一个动作 [setInterval]，
 * 调用方不可能只做一半。
 *
 * ## 为什么读和写分开而不是给一个 `var interval`
 *
 * 读要在首帧渲染前同步拿到（否则设置页会先闪一下默认选项）。写要触发异步
 * 重排。合成一个属性会让 getter 也可能阻塞，也说不清 setter 是不是 suspend。
 */
interface CollectionBackend {

    /** 当前周期。同步读——首帧就要用来画选中态 */
    fun currentInterval(): RefreshInterval

    /**
     * 写入新周期并**立即**按新周期重排刷新任务。
     *
     * `OFF` 表示取消已有任务。实现方负责让改动即时生效，
     * 不该要求用户重启应用才看到效果。
     */
    suspend fun setInterval(interval: RefreshInterval)

    /**
     * 是否支持自动刷新。
     *
     * 桌面端在没有后台常驻的实现之前返回 false，设置页据此把整块选项
     * 换成「桌面端暂不支持自动刷新」的说明，而不是给一组选了没用的按钮。
     */
    val supportsAutoRefresh: Boolean

    companion object {
        /** 什么都不做的实现，供还不支持自动刷新的宿主使用 */
        val Unsupported: CollectionBackend = object : CollectionBackend {
            override fun currentInterval(): RefreshInterval = RefreshInterval.OFF
            override suspend fun setInterval(interval: RefreshInterval) = Unit
            override val supportsAutoRefresh: Boolean = false
        }
    }
}

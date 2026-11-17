package com.lifeboard.desktop.screentime

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.builtinRef
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.repository.MetricStore
import com.lifeboard.shared.repository.model.AppUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * [ScreenTimeBackend] 的桌面实现（M11）。
 *
 * ## 为什么桌面端和 Android 的算法完全不同
 *
 * Android 有「使用情况访问权」，`UsageStatsManager` 直接给出每个应用的前台秒数，
 * 采集就是读一次系统账本。桌面端没有这个系统服务，所以换个思路：
 * **自己记账**——定时问一次「当前前台窗口属于哪个进程」，
 * 两次采样之间经过的时间就记到那个进程头上。
 *
 * 这也是 [ScreenTimeBackend] 接口注释里早就写好的方案
 * （「桌面端没有这个权限模型，改由窗口焦点事件自己记账」）。
 *
 * ## 采样与累加的关系
 *
 * 采样只记录「这一刻前台是谁」，累计发生在 [tick]：
 * 上一次采样到这一次采样之间经过了 `Δt`，这段时间归上次那个前台应用。
 * 用「上次的归属」而不是「这次的归属」，是因为两次采样之间状态可能已经变了，
 * 而人是在那一整段时间里用着**上一次**看到的那个应用。
 *
 * ## 幂等与覆盖
 *
 * `metric` 表主键是 `(sourceType, date, bucket)`，`saveMetric` 是 `INSERT OR REPLACE`。
 * 所以**不能**每次 tick 都 `saveMetric(增量)`——那会把上一次写的值覆盖掉，
 * 库里的数永远等于最后一次 tick 的增量，而不是当天累计。
 * 正确做法是内存里维护当天累计值，每次 tick 写「累计总量」。
 *
 * 内存累计在跨天时清零重算；进程重启后从库里读回已有值继续累加，
 * 否则「早上用了一小时、中午重启」会变成只剩下午的时长。
 *
 * ## 什么不算「使用电脑」
 *
 * 只统计 **LifeBoard 自己的窗口在前台** 的时间以及**其它前台应用**的时间，
 * 但空转（无前台窗口 / 锁屏 / 息屏）不记。
 * 这里没法区分「息屏」——Windows 没有不需要权限的查询方式，
 * 而 `GetLastInputInfo` 能拿到「最后一次键鼠输入距今多久」，
 * 超过 [IDLE_THRESHOLD_MILLIS] 就当作人不在，这段时间不计入任何应用。
 */
class DesktopScreenTimeBackend(
    private val metricStore: MetricStore,
    private val frontWindow: FrontWindowProbe,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val todayProvider: () -> String = { Dates.todayString() },
) : ScreenTimeBackend {

    /**
     * 采样互斥锁。
     *
     * 后台定时器与「立即采集」按钮都会触发 tick，两者若并发跑，
     * 「读上次 → 算差值 → 写累计」这个序列会互相踩，
     * 结果是同一段时间被记两次（或一次都没记）。
     */
    private val mutex = Mutex()

    /** 上一次采样时前台是谁，以及那一刻的时间戳 */
    private var lastPackage: String? = null
    private var lastLabel: String = ""
    private var lastSampleAt: Long = 0L

    /**
     * 是否已经采样过至少一次。
     *
     * 不能靠 `lastSampleAt == 0L` 判断「还没采过」——时钟本身的读数可能就是 0
     * （测试里的假时钟、以及理论上开机计时归零的瞬间），
     * 那样会把第一次采样的 Δt 算成一个巨大的负数或 0，一整天都不出数。
     * 用一个独立的布尔把「有没有上一次」这件事表达清楚。
     */
    private var hasSampled: Boolean = false

    /** 当天累计：packageName → 分钟。桌面端没有「整机总时长」，用这些算总和 */
    private val todayByApp = ConcurrentHashMap<String, Double>()

    /** 内存累计对应的日期。跨天时清空重来 */
    private var today: String = todayProvider()

    /** 立即采集时是否成功读到过前台窗口 */
    private var everProbed: Boolean = false

    override suspend fun hasPermission(): Boolean = frontWindow.available

    override suspend fun collectNow(): Boolean = withContext(Dispatchers.IO) {
        // 「立即采集」的语义是「把从上次采样到现在这段补记上」，
        // 而不是去读一个系统账本——桌面端没有账本可读
        tick()
        everProbed
    }

    override suspend fun todayMinutes(): Double = withContext(Dispatchers.IO) {
        ensureTodayLoaded()
        todayByApp.values.sum()
    }

    override suspend fun thisWeekMinutes(): Double =
        thisWeekDaily().sumOf { point -> point.minutes }

    override suspend fun thisWeekDaily(): List<ScreenTimeBackend.DailyPoint> =
        withContext(Dispatchers.IO) {
            val from = Dates.weekStart().toString()
            val to = Dates.weekEnd().toString()
            val rows = metricStore.getSourceRange(
                sourceKey = SourceType.SCREEN_TIME.name,
                bucket = SCREEN_TIME_REF.bucket.name,
                from = from,
                to = to,
            )
            val byDate = rows.associate { it.date to it.value }

            // 必须把今天这一格换成「内存里的实时值」：
            // 库里的今日值是上一次 tick 写下的，可能已经落后几分钟，
            // 而用户看到「今天 32 分」转头看本周柱状图上今天那根却是 28 分，
            // 会以为是两个统计口径
            val todayKey = todayProvider()
            ensureTodayLoaded()
            val memoryToday = todayByApp.values.sum()

            buildWeek(from = from, to = to).map { date ->
                val value = if (date == todayKey) memoryToday else byDate[date] ?: 0.0
                ScreenTimeBackend.DailyPoint(date = date, minutes = value)
            }
        }

    override suspend fun todayAppUsage(): List<AppUsageRecord> = withContext(Dispatchers.IO) {
        ensureTodayLoaded()
        val date = todayProvider()
        todayByApp.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .map { (pkg, minutes) ->
                AppUsageRecord(
                    date = date,
                    packageName = pkg,
                    minutes = minutes,
                    label = packageLabel(pkg),
                )
            }
    }

    /**
     * 采样一次并结算这段时间的使用时长。
     *
     * 由两处触发：后台定时器（每 [FrontWindowProbe] 的间隔一次）与用户点「立即采集」。
     * 幂等性由 `lastSampleAt` 保证——同一个时刻重复调用，Δt 为 0，不产生记录。
     */
    suspend fun tick() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = clock()
            ensureTodayLoaded()

            val sample = frontWindow.sample()
            if (sample != null) everProbed = true

            val previous = lastPackage
            val elapsed = if (hasSampled) (now - lastSampleAt).coerceAtLeast(0L) else 0L

            // 结算「上一次采样到这一次」这段时间。
            // 三个不记的条件：没有上一次、上次没人用（idle / 无窗口）、时间倒流
            if (previous != null && elapsed > 0L) {
                val minutes = elapsed / 60_000.0
                if (minutes > 0.0) {
                    todayByApp.merge(previous, minutes, Double::plus)
                    labels[previous] = lastLabel
                }
            }

            lastPackage = sample?.packageName
            lastLabel = sample?.label.orEmpty()
            lastSampleAt = now
            hasSampled = true

            persistToday()
        }
    }

    /**
     * 把内存累计写进库。
     *
     * 每次 tick 都写而不是攒着等退出：用户可能在任意时刻看趋势页或关掉程序，
     * 攒着就意味着「今天用了三小时，一关程序全没了」。
     */
    private suspend fun persistToday() {
        val date = todayProvider()
        val total = todayByApp.values.sum()
        metricStore.saveMetric(
            MetricRecord(
                source = SCREEN_TIME_REF,
                date = date,
                value = total,
            ),
        )
    }

    /**
     * 保证内存累计属于「今天」。
     *
     * 两件事：跨天清零、进程重启后从库回填。
     * 回填只在第一次调用时做一次——否则每次 tick 都会把库里刚写的值
     * 再加一遍到内存，时长会指数膨胀。
     */
    private suspend fun ensureTodayLoaded() {
        val date = todayProvider()
        if (date != today) {
            // 跨天：昨天的累计已经写过库了，内存清空重来
            today = date
            todayByApp.clear()
            lastPackage = null
            lastLabel = ""
            lastSampleAt = 0L
            hasSampled = false
            loadedFor = null
        }
        if (loadedFor == date) return

        val stored = metricStore.getMetric(
            sourceKey = SourceType.SCREEN_TIME.name,
            date = date,
            bucket = SCREEN_TIME_REF.bucket.name,
        )
        val storedTotal = stored?.value ?: 0.0
        if (storedTotal > 0.0) {
            // 只知道总量、不知道分摊到哪个应用，挂在一个明确的汇总键上，
            // 而不是伪造一个应用名。今天的分应用排行会少掉这一段，
            // 但「今日总时长」是对的——用户最关心的那个数字不能错
            todayByApp.merge(LEGACY_BUCKET, storedTotal, Double::plus)
        }
        loadedFor = date
    }

    /** 已经回填过的日期，避免重复回填 */
    private var loadedFor: String? = null

    private fun packageLabel(pkg: String): String = labels[pkg] ?: pkg

    /** 采样到的展示名，随 tick 一起更新 */
    private val labels = ConcurrentHashMap<String, String>()

    private companion object {
        /**
         * 屏幕时长这个源在两端的 key 必须一致，否则 Android 与桌面算出来的
         * 是两条互不相干的曲线。用 `SourceType.SCREEN_TIME.name` 自动对齐
         */
        val SCREEN_TIME_REF = builtinRef(SourceType.SCREEN_TIME)

        /**
         * 从库里回填的、没有应用归属的历史总量挂在这个键下。
         *
         * 不伪装成真实应用名（比如 `"unknown"`）是为了让分应用排行里
         * 这一行一看就知道是「汇总残留」，而不是某个真实程序。
         */
        const val LEGACY_BUCKET = "（历史汇总）"

        /**
         * 判定「人不在」的空闲阈值。
         *
         * 5 分钟：足够长到不会把「认真读代码没动鼠标」误判成离开，
         * 又足够短到午休 / 开会时不会把时长灌到某个窗口上。
         */
        const val IDLE_THRESHOLD_MILLIS = 5 * 60_000L
    }

    /**
     * 把 [from] 到 [to] 之间的每一天列出来，日历意义上的一天不漏。
     *
     * 曲线必须有连续的横轴：缺失的日期若直接跳过，
     * 图上会把「周一和周三之间」画成相邻的两根柱子，
     * 用户会以为周二没数据是因为程序出错了。补 0 更诚实。
     */
    private fun buildWeek(from: String, to: String): List<String> {
        val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return emptyList()
        if (end.isBefore(start)) return emptyList()
        val days = mutableListOf<String>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            days += cursor.toString()
            cursor = cursor.plusDays(1)
        }
        return days
    }
}

/**
 * 「当前前台是哪个应用」的探测器。
 *
 * 抽成接口是为了让 [DesktopScreenTimeBackend] 能被单元测试——
 * 真实实现要调 Win32，测试机上不一定有可交互的桌面会话。
 */
interface FrontWindowProbe {

    /** 当前环境能否读到前台窗口。读不到的平台上整个面板应该显示「不可用」 */
    val available: Boolean

    /**
     * 采样一次。返回 null 表示「这一刻没有可统计的前台应用」
     * （无前台窗口、锁屏、用户空闲）。
     */
    fun sample(): ForegroundSample?
}

/** 一次采样：哪个进程在前台，以及它的展示名 */
data class ForegroundSample(
    /** 用作 `app_usage.packageName` 的稳定键。桌面端用 exe 文件名 */
    val packageName: String,
    /** 展示名，取窗口标题或 exe 友好名 */
    val label: String,
)

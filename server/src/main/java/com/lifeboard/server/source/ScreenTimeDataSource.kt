package com.lifeboard.server.source

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.source.DataSource
import com.lifeboard.core.util.Dates
import java.time.LocalDate

/**
 * 屏幕使用时长采集器（M1）。
 *
 * 走 `UsageStatsManager.queryEvents` 公开 API，不需要任何 hook 注入，
 * 因此 [requiresInjection] 恒为 false，模块进程内即可运行。
 *
 * 算法：遍历当日事件流，遇到 [UsageEvents.Event.ACTIVITY_RESUMED] 记一个起点，
 * 遇到 [UsageEvents.Event.ACTIVITY_PAUSED] / [UsageEvents.Event.ACTIVITY_STOPPED] 结算一段。
 * 只有开头没有结尾的那一段（正在使用中）用"当日已过时长"封顶，
 * 否则今天的数据会永远比实际少最后这一个前台会话。
 *
 * 已知限制：不扣除熄屏时间。熄屏时系统会给前台 Activity 补发 PAUSED 事件，
 * 依据 AOSP 行为这是成立的，但澎湃OS 有厂商改动，需在实机上与"数字健康"对比校验。
 */
class ScreenTimeDataSource(private val context: Context) : DataSource {

    override val sourceType: SourceType = SourceType.SCREEN_TIME

    override val requiresInjection: Boolean = false

    private val usageStats: UsageStatsManager?
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * 检查"使用情况访问权"是否已授予。
     *
     * 注意：`AppOpsManager.OPSTR_GET_USAGE_STATS` 在部分 ROM 上即使未授权也会返回
     * MODE_DEFAULT，因此不能只看 AppOps，必须再判断查询结果是否为空才可靠。
     */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        @Suppress("DEPRECATION")
        val mode = runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        if (mode != AppOpsManager.MODE_ALLOWED) return false

        // 二次确认：真授权了才能查到数据。空列表说明是"假授权"。
        // 窗口必须覆盖全天而不是最近几分钟：聚合 Worker 在凌晨运行，
        // 若只探测最近 60 秒，深夜无使用事件会被误判为未授权，
        // 每天凌晨把健康度写成 false，历史记录失真。
        val now = System.currentTimeMillis()
        val probe = runCatching {
            usageStats?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MILLIS, now)
        }.getOrNull()
        return !probe.isNullOrEmpty()
    }

    override suspend fun collect(date: String): List<MetricRecord> {
        val day = runCatching { LocalDate.parse(date) }.getOrElse { Dates.today() }
        val totalMinutes = queryScreenMinutes(day)
        return listOf(
            MetricRecord(
                source = SourceType.SCREEN_TIME,
                date = date,
                bucket = MetricBucket.DURATION_MINUTES,
                value = totalMinutes,
            ),
        )
    }

    override suspend fun health(): SourceHealth {
        val granted = hasPermission()
        return SourceHealth(
            source = SourceType.SCREEN_TIME,
            healthy = granted,
            lastSuccessAt = if (granted) System.currentTimeMillis() else 0L,
            message = if (granted) "" else "未授予「使用情况访问权」，无法统计屏幕时长",
        )
    }

    /**
     * 计算某天的屏幕使用分钟数。
     *
     * @return 分钟数，保留一位小数；未授权或发生异常时返回 0
     */
    fun queryScreenMinutes(day: LocalDate): Double {
        val usm = usageStats ?: return 0.0
        val begin = Dates.startOfDayMillis(day)
        val end = Dates.endOfDayMillis(day)
        // 正在进行的会话最多算到"现在"，而不是算到明天 0 点
        val cap = minOf(end, System.currentTimeMillis())
        if (cap <= begin) return 0.0

        return runCatching {
            var foregroundSince = 0L
            var totalMillis = 0L
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // 连续两次 RESUMED（Activity 间跳转）时，先把上段结算掉，避免漏计
                        if (foregroundSince > 0L) {
                            totalMillis += (event.timeStamp - foregroundSince).coerceAtLeast(0L)
                        }
                        foregroundSince = event.timeStamp
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        if (foregroundSince > 0L) {
                            totalMillis += (event.timeStamp - foregroundSince).coerceAtLeast(0L)
                            foregroundSince = 0L
                        }
                    }
                }
            }
            // 收尾：仍在使用中的最后一段
            if (foregroundSince > 0L) {
                totalMillis += (cap - foregroundSince).coerceAtLeast(0L)
            }
            totalMillis / 60_000.0
        }.getOrElse {
            Log.w(TAG, "queryScreenMinutes failed for $day", it)
            0.0
        }
    }

    /**
     * 计算某天各应用的前台时长（分钟），按用量降序。
     * M1 面板暂未使用，留作后续"分应用明细"与误差校验时的排查手段。
     */
    fun queryPerAppMinutes(day: LocalDate): List<Pair<String, Double>> {
        val usm = usageStats ?: return emptyList()
        val begin = Dates.startOfDayMillis(day)
        val end = Dates.endOfDayMillis(day)
        val cap = minOf(end, System.currentTimeMillis())
        if (cap <= begin) return emptyList()

        return runCatching {
            val openSince = mutableMapOf<String, Long>()
            val acc = mutableMapOf<String, Long>()
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        openSince[pkg]?.let { acc[pkg] = (acc[pkg] ?: 0L) + (event.timeStamp - it) }
                        openSince[pkg] = event.timeStamp
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        openSince.remove(pkg)?.let {
                            acc[pkg] = (acc[pkg] ?: 0L) + (event.timeStamp - it)
                        }
                    }
                }
            }
            openSince.forEach { (pkg, since) ->
                acc[pkg] = (acc[pkg] ?: 0L) + (cap - since).coerceAtLeast(0L)
            }
            acc.map { (pkg, ms) -> pkg to ms / 60_000.0 }
                .sortedByDescending { it.second }
        }.getOrElse {
            Log.w(TAG, "queryPerAppMinutes failed for $day", it)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "LifeBoardScreen"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

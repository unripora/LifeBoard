package com.lifeboard.server.source

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.source.ScreenTimeSource
import com.lifeboard.core.util.Dates
import java.time.LocalDate

/**
 * 屏幕使用时长采集器（M1）。
 *
 * 走 `UsageStatsManager` 公开 API，不需要任何 hook 注入，
 * 因此 [requiresInjection] 恒为 false，模块进程内即可运行。
 *
 * **数据来源是系统汇总，不自己算**：系统本身一直在记账，
 * 小米「数字健康」读的也是这份数据。这里只用 `queryUsageStats`
 * 取它算好的结果，不再自己遍历事件流配对 RESUME/PAUSE——
 * 那样等于把系统已经算完的账重算一遍，边界情况（跨零点、Activity
 * 连续跳转、进程异常退出）全靠自己兜，很容易和系统数字对不上。
 *
 * 已知限制：汇总数据有延迟，当天正在使用的那一段可能还没计入，
 * 「今天」的数字会偏小，隔天之后才完全准确。
 */
class ScreenTimeDataSource(private val context: Context) : ScreenTimeSource {

    override val source: SourceRef = builtinRef(SourceType.SCREEN_TIME)

    override val requiresInjection: Boolean = false

    private val usageStats: UsageStatsManager?
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * 检查"使用情况访问权"是否已授予。
     *
     * 注意：`AppOpsManager.OPSTR_GET_USAGE_STATS` 在部分 ROM 上即使未授权也会返回
     * MODE_DEFAULT，因此不能只看 AppOps，必须再判断查询结果是否为空才可靠。
     */
    override fun hasPermission(): Boolean {
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
                source = builtinRef(SourceType.SCREEN_TIME),
                date = date,
                value = totalMinutes,
            ),
        )
    }

    override suspend fun health(): SourceHealth {
        val granted = hasPermission()
        return SourceHealth(
            source = builtinRef(SourceType.SCREEN_TIME),
            healthy = granted,
            lastSuccessAt = if (granted) System.currentTimeMillis() else 0L,
            message = if (granted) "" else "未授予「使用情况访问权」，无法统计屏幕时长",
        )
    }

    /**
     * 计算某天的屏幕使用分钟数（读系统汇总，不自己累加事件流）。
     *
     * 为什么改成读汇总：系统（`UsageStatsManager`）本身就在持续记账，
     * 小米「数字健康」读的也是这份数据。之前用 `queryEvents` 自己遍历
     * RESUME/PAUSE 配对累加，等于把系统已经算好的账重算一遍——
     * 代码多、边界情况（跨零点、Activity 连续跳转、异常退出）全靠自己兜，
     * 很容易和系统数字对不上。现在直接取汇总，系统怎么算就怎么用。
     *
     * **已知代价**：汇总数据有延迟，当天正在使用的这一段可能还没计入，
     * 所以「今天」的数字会偏小，过一天后才完全准确。对「每日屏幕时长」
     * 这种统计用途可以接受，实时性要求高的场景才需要回到事件流方案。
     *
     * @return 分钟数；未授权或发生异常时返回 0
     */
    fun queryScreenMinutes(day: LocalDate): Double {
        val usm = usageStats ?: return 0.0
        val begin = Dates.startOfDayMillis(day)
        val end = Dates.endOfDayMillis(day)

        return runCatching {
            // 用 INTERVAL_DAILY 分桶取汇总，再按「最后一次使用时间」筛出目标日期。
            // 不直接取第一个桶：queryUsageStats 返回的区间可能横跨多天，
            // 桶的顺序和边界各 ROM 不完全一致，按 lastTimeUsed 过滤更可靠。
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                ?: return@runCatching 0.0

            stats.asSequence()
                .filter { it.lastTimeUsed in begin until end }
                .sumOf { it.totalTimeInForeground }
                .let { it / 60_000.0 }
        }.getOrElse {
            Log.w(TAG, "queryScreenMinutes failed for $day", it)
            0.0
        }
    }

    /**
     * 各应用某天的前台时长（分钟），按用量降序。
     *
     * 同样读系统汇总：`queryUsageStats` 的每一项本来就是「一个应用的汇总」，
     * 直接取包名与前台时长即可，不需要自己配对了。
     * 面板暂未展示，留作「分应用明细」与误差校验用。
     */
    override fun queryPerAppMinutes(day: LocalDate): List<Pair<String, Double>> {
        val usm = usageStats ?: return emptyList()
        val begin = Dates.startOfDayMillis(day)
        val end = Dates.endOfDayMillis(day)

        return runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                ?: return@runCatching emptyList()
            stats.asSequence()
                .filter { it.lastTimeUsed in begin until end }
                .filter { it.totalTimeInForeground > 0L }
                .map { it.packageName to it.totalTimeInForeground / 60_000.0 }
                .sortedByDescending { it.second }
                .toList()
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

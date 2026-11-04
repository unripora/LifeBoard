package com.lifeboard.server.collect

import android.content.Context
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 屏幕时长读取器（M1.4 面板用）。
 *
 * 面板与 Provider 同进程，因此直接读 Room，免去 Cursor 解析；
 * 通过 suspend 函数在 IO 线程读取，避免阻塞 UI。
 */
class ScreenTimeReader(private val context: Context) {

    private val db get() = LifeBoardDatabase.get(context)

    /**
     * 读取指定区间的屏幕时长（分钟）。
     *
     * @return 日期 → 分钟数，缺失日期不出现在结果里（面板按 0 处理）
     */
    suspend fun minutesBetween(from: LocalDate, to: LocalDate): Map<String, Double> =
        withContext(Dispatchers.IO) {
            db.metricDao()
                .getRange(from.toString(), to.toString())
                .filter {
                    it.sourceType == SourceType.SCREEN_TIME.name &&
                        it.bucket == MetricBucket.DURATION_MINUTES.name
                }
                .associate { it.date to it.value }
        }

    /** 今日屏幕时长（分钟） */
    suspend fun todayMinutes(): Double = minutesBetween(Dates.today(), Dates.today())
        .values.firstOrNull() ?: 0.0

    /** 本周屏幕时长（分钟），周一为起点 */
    suspend fun thisWeekMinutes(): Double {
        val today = Dates.today()
        return minutesBetween(Dates.weekStart(today), today).values.sum()
    }

    /** 本周每天的屏幕时长，缺失日期补 0，保证面板能画出连续的柱状图 */
    suspend fun thisWeekDaily(): List<Pair<LocalDate, Double>> {
        val today = Dates.today()
        val start = Dates.weekStart(today)
        val data = minutesBetween(start, today)
        return (0..7).map { start.plusDays(it.toLong()) }
            .filter { !it.isAfter(today) }
            .map { it to (data[it.toString()] ?: 0.0) }
    }

    /** 最近 N 天每天的屏幕时长，缺失补 0 */
    suspend fun recentDaily(days: Int): List<Pair<LocalDate, Double>> {
        val today = Dates.today()
        val start = today.minusDays(days - 1L)
        val data = minutesBetween(start, today)
        return (0 until days).map { start.plusDays(it.toLong()) }
            .map { it to (data[it.toString()] ?: 0.0) }
    }

    /** 原始记录，排查误差时用 */
    suspend fun rawToday(): List<MetricEntity> = withContext(Dispatchers.IO) {
        db.metricDao().getByDate(Dates.todayString())
    }
}

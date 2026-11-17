package com.lifeboard.shared.platform

import com.lifeboard.shared.repository.model.AppUsageRecord

/**
 * 屏幕时长面板的宿主能力（M1-1.3）。
 *
 * Android 侧的数据来自「使用情况访问权」（`UsageStatsManager`），
 * 桌面端没有这个权限模型，改由窗口焦点事件自己记账。
 * 但两边的面板是同一个：今日时长、本周每日曲线、分应用排行、立即采集。
 * 所以收成这个接口，面板只认数字不认来源。
 */
interface ScreenTimeBackend {

    /** 周曲线上的一个点 */
    data class DailyPoint(val date: String, val minutes: Double)

    /** 是否已获得统计权限 */
    suspend fun hasPermission(): Boolean

    /** 立即采集一次并入库，返回是否成功 */
    suspend fun collectNow(): Boolean

    suspend fun todayMinutes(): Double

    suspend fun thisWeekMinutes(): Double

    suspend fun thisWeekDaily(): List<DailyPoint>

    /** 今日各应用用量，已按分钟降序 */
    suspend fun todayAppUsage(): List<AppUsageRecord>
}

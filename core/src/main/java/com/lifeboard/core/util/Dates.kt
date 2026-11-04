package com.lifeboard.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 统一日期工具。
 *
 * 全项目日期一律使用 `yyyy-MM-dd` 字符串（`LocalDate.toString()` 的格式），
 * 时区一律用设备本地时区，避免采集侧与面板侧对"今天"的理解不一致。
 */
object Dates {

    /** 本地时区 */
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** 今天 */
    fun today(): LocalDate = LocalDate.now(zone)

    /** 今天的 yyyy-MM-dd */
    fun todayString(): String = today().toString()

    /** 把毫秒时间戳转成本地日期字符串 */
    fun ofEpochMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /** 本周一 */
    fun weekStart(date: LocalDate = today()): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 本周日 */
    fun weekEnd(date: LocalDate = today()): LocalDate =
        date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    /** 某天 0 点的毫秒时间戳 */
    fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 某天结束（次日 0 点）的毫秒时间戳，用于开区间查询 */
    fun endOfDayMillis(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

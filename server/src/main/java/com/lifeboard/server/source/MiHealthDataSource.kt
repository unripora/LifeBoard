package com.lifeboard.server.source

import android.content.Context
import android.util.Log
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.source.DataSource
import com.lifeboard.core.util.Dates
import com.lifeboard.server.mihealth.MiHealthClient
import com.lifeboard.server.mihealth.MiHealthCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 小米运动健康数据源。
 *
 * 走云端接口（`hlth.io.mi.com`）而不是读本地数据库：
 * 本地库需要 root，而 App 私有目录普通应用读不到。
 * 云端这条路只要登录一次，之后 passToken 自动续期，**不需要 root**。
 *
 * 目前支持两个量纲：
 * - [MetricBucket.STEPS]：当日步数
 * - [MetricBucket.SLEEP_MINUTES]：当日睡眠分钟数
 */
class MiHealthDataSource(private val context: Context) : DataSource {

    override val source: SourceRef = builtinRef(SourceType.MI_HEALTH)

    /** 云端接口，不走注入 */
    override val requiresInjection: Boolean = false

    private val client by lazy {
        MiHealthClient(
            credentialsProvider = { MiHealthCredentialStore.load(context) },
            onCredentialsExpired = {
                // passToken 续期：成功后写回，失败返回 null 让调用方感知已掉登录
                val old = MiHealthCredentialStore.load(context)
                val fresh = old?.let {
                    com.lifeboard.server.mihealth.MiHealthAuth().refreshWithPassToken(
                        passToken = it.passToken,
                        cUserId = it.cUserId,
                        account = it.account,
                    )
                }
                if (fresh != null) MiHealthCredentialStore.save(context, fresh)
                fresh
            },
        )
    }

    override suspend fun collect(date: String): List<MetricRecord> = withContext(Dispatchers.IO) {
        if (!MiHealthCredentialStore.isLoggedIn(context)) {
            Log.i(TAG, "collect skipped: not logged in")
            return@withContext emptyList()
        }

        val day = runCatching { LocalDate.parse(date) }.getOrNull()
            ?: return@withContext emptyList()
        val (start, end) = dayRange(day)

        val out = mutableListOf<MetricRecord>()

        runCatching {
            val steps = collectSteps(start, end)
            if (steps != null) {
                out += MetricRecord(stepsRef, date, steps)
            }
        }.onFailure { Log.w(TAG, "collect steps failed", it) }

        runCatching {
            val sleep = collectSleepMinutes(start, end)
            if (sleep != null) {
                out += MetricRecord(sleepRef, date, sleep)
            }
        }.onFailure { Log.w(TAG, "collect sleep failed", it) }

        Log.i(TAG, "collect($date) -> ${out.joinToString { "${it.bucket}=${it.value}" }}")
        out
    }

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (!MiHealthCredentialStore.isLoggedIn(context)) {
            return@withContext SourceHealth(
                source = healthRef,
                healthy = false,
                message = "未登录小米账号",
            )
        }
        // 用今天的数据探一次，能拿到就算健康
        val now = System.currentTimeMillis()
        val points = runCatching {
            client.getFitnessDataByTime("steps", now - DAY_MILLIS, now, maxPages = 1)
        }.getOrNull()

        if (points == null) {
            SourceHealth(healthRef, false, message = "云端接口不可用，可能需要重新登录")
        } else {
            SourceHealth(healthRef, true, lastSuccessAt = now, message = "正常")
        }
    }

    /**
     * 本源的指标规格：步数与睡眠。
     *
     * 一个源两个量纲，落库时靠量纲区分（`metric` 主键含 bucket），
     * 所以每个量纲各自持有一个 [SourceRef]，量纲由源自己带着走。
     * metricKey 复用 [MetricBucket] 枚举名，与历史 `metric.bucket` 列取值一致，老数据不用迁移。
     */
    private val stepsRef: SourceRef =
        builtinRef(SourceType.MI_HEALTH, MetricBucket.STEPS)

    private val sleepRef: SourceRef =
        builtinRef(SourceType.MI_HEALTH, MetricBucket.SLEEP_MINUTES)

    /** 健康度用的源引用：小米健康是内置源，取默认量纲（步数）即可 */
    private val healthRef: SourceRef = builtinRef(SourceType.MI_HEALTH)

    /**
     * 当日步数。
     *
     * 步数接口返回的是**采样点**（通常是按小时间隔），需要累加。
     * 优先用 `step` / `value` 字段；`count` 是同一个意思的另一种命名。
     */
    private suspend fun collectSteps(start: Long, end: Long): Double? {
        val points = client.getFitnessDataByTime("steps", start, end)
        if (points.isEmpty()) return null
        val sum = points.sumOf { pointValue(it) }
        return if (sum > 0) sum else null
    }

    /**
     * 当日睡眠分钟数。
     *
     * 睡眠数据结构与步数不同：每个点是 `{startTime, endTime, ...}` 的一段，
     * 时长要按 end - start 算，而不是取某个 value 字段。
     */
    private suspend fun collectSleepMinutes(start: Long, end: Long): Double? {
        val points = client.getFitnessDataByTime("sleep", start, end)
        if (points.isEmpty()) return null

        var totalMillis = 0L
        points.forEach { p ->
            val s = p.optLong("startTime", 0L)
            val e = p.optLong("endTime", 0L)
            if (s > 0 && e > s) {
                // 只统计与当日窗口重叠的部分，避免跨天睡眠把前后两天都算满
                val overlapStart = maxOf(s, start)
                val overlapEnd = minOf(e, end)
                if (overlapEnd > overlapStart) totalMillis += overlapEnd - overlapStart
            }
        }
        return if (totalMillis > 0) totalMillis / 60_000.0 else null
    }

    /** 采样点的数值：不同 key 的字段名不一样，按优先级取第一个存在的 */
    private fun pointValue(p: JSONObject): Double {
        for (name in VALUE_FIELDS) {
            if (p.has(name)) {
                val v = p.optDouble(name, Double.NaN)
                if (!v.isNaN()) return v
            }
        }
        return 0.0
    }

    private fun dayRange(day: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        // 当天还没结束时，截止到现在，避免向服务端要未来时间段
        return start to minOf(end, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "LifeBoardMiHealth"
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        /** 不同数据键下代表「数值」的字段名候选 */
        private val VALUE_FIELDS = listOf("value", "step", "count", "data")
    }
}

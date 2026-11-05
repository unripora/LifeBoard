package com.lifeboard.server.mihealth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 小米健康数据概览（全量）。
 *
 * 覆盖面按 `external/mihealth-api/API.md` 的端点目录来，能取到的族都取：
 *
 * | 族 | 端点 | 本类对应字段 |
 * |---|---|---|
 * | fitness 原始记录 | `data/get_fitness_data_by_time` | 步数/热量/心率/睡眠/血氧/压力/体重/血压/血糖/PAI/能量/强度/站立/VO2Max/耳机噪声 |
 * | 聚合日报 | `data/get_aggregated_fitness_data_by_time` | 三环目标（步数/热量/活动/站立） |
 * | 运动记录 | `data/get_sport_records_by_time` | 每次运动的时长/距离/配速/心率区间 |
 * | 饮食记录 | `data/get_diet_records_by_time` | 摄入热量与餐次 |
 * | 医疗数据 | `data/get_medical_data_by_time` | ECG 等（多数账号为空） |
 * | 项目数据 | `data/get_project_data_by_time` | 睡眠节律环等 |
 *
 * 拿不到的项一律留 null，由界面决定是否显示——不能因为某项空就让整页变白。
 * 只做**展示用**汇总，不落库；参与任务判定的量纲走 [MiHealthDataSource]。
 */
data class MiHealthOverview(
    // ---- 今日活动 ----
    val stepsToday: Int = 0,
    val distanceToday: Int = 0,
    val caloriesToday: Double = 0.0,
    val activeMinutesToday: Int = 0,
    val standHoursToday: Int = 0,
    val intensityMinutes: Int = 0,

    // ---- 今日目标（三环） ----
    val goal: DailyGoal? = null,

    // ---- 心率 ----
    val heartRateLast: Int? = null,
    val heartRateMin24h: Int? = null,
    val heartRateMax24h: Int? = null,
    val heartRateAvg24h: Int? = null,
    val heartRateResting: Int? = null,
    /** 24 小时内有效心率采样点数，用来判断手环是否真的在戴 */
    val heartRateSamples: Int = 0,

    // ---- 睡眠 ----
    val sleepLast: SleepSummary? = null,
    /** 近 7 天睡眠，用于算平均时长 */
    val sleepRecent: List<SleepSummary> = emptyList(),

    // ---- 身体指标 ----
    val spo2Last: Int? = null,
    val stressLast: Int? = null,
    val weightLast: Double? = null,
    val vo2MaxLast: Double? = null,
    val bloodPressureLast: String? = null,
    val bloodSugarLast: Double? = null,
    val paiLast: Double? = null,
    val energyLast: Double? = null,
    val menstruationLast: String? = null,

    // ---- 环境（耳机噪声） ----
    val headsetHighMinutes: Int = 0,
    val headsetTotalMinutes: Int = 0,

    // ---- 运动记录 ----
    val sports: List<SportRecord> = emptyList(),
    val sportDays30: Int = 0,

    // ---- 饮食 ----
    val dietCaloriesToday: Int = 0,
    val dietCountToday: Int = 0,

    // ---- 医疗 / 项目 ----
    val medicalCount: Int = 0,
    val projectCount: Int = 0,

    /**
     * 各族原始记录的条数，按 `族:key` 归类。
     *
     * 用户要看「全部数据」，而很多 key 本账号就是空的——把条数摆出来，
     * 一眼能看出「接口通了但这台设备没采集这项」，不用猜。
     */
    val counts: Map<String, Int> = emptyMap(),

    /** 数据来源设备/App 的 sid 列表 */
    val sources: List<String> = emptyList(),

    val error: String = "",
) {
    /** 是否至少有一项有数据，决定要不要显示「设备未同步」提示 */
    val hasAny: Boolean
        get() = stepsToday > 0 || heartRateLast != null || sleepLast != null ||
            weightLast != null || spo2Last != null || sports.isNotEmpty()

    /** 近 7 天平均睡眠分钟数 */
    val sleepAvgMinutes: Int
        get() = if (sleepRecent.isEmpty()) 0
        else sleepRecent.sumOf { it.minutes } / sleepRecent.size
}

/** 一次睡眠的概要 */
data class SleepSummary(
    val startAt: Long,
    val endAt: Long,
    val minutes: Int,
    /** 深/浅/REM/清醒 明细，单位分钟；接口没给就是 0 */
    val deepMinutes: Int = 0,
    val lightMinutes: Int = 0,
    val remMinutes: Int = 0,
    val awakeMinutes: Int = 0,
    val awakeCount: Int = 0,
    val avgHeartRate: Int = 0,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val avgBreath: Int = 0,
    val avgSpo2: Int = 0,
    /** 是否中途醒来（数据不完整） */
    val incomplete: Boolean = false,
) {
    /** 睡眠效率：实际睡着时间 / 卧床时间 */
    val efficiency: Int
        get() = if (minutes <= 0) 0 else ((minutes - awakeMinutes) * 100 / minutes)
}

/**
 * 每日三环目标。
 *
 * `goal_items[].field` 实测映射：1=步数 2=热量 3=站立小时 4=活动分钟。
 */
data class DailyGoal(
    val stepsAchieved: Int = 0,
    val stepsTarget: Int = 0,
    val caloriesAchieved: Int = 0,
    val caloriesTarget: Int = 0,
    val activeAchieved: Int = 0,
    val activeTarget: Int = 0,
    val standAchieved: Int = 0,
    val standTarget: Int = 0,
) {
    val stepsPercent: Int get() = percent(stepsAchieved, stepsTarget)
    val caloriesPercent: Int get() = percent(caloriesAchieved, caloriesTarget)
    val activePercent: Int get() = percent(activeAchieved, activeTarget)
    val standPercent: Int get() = percent(standAchieved, standTarget)

    private fun percent(a: Int, t: Int): Int =
        if (t <= 0) 0 else (a * 100 / t).coerceAtMost(100)
}

/** 一次运动记录 */
data class SportRecord(
    val key: String,
    val category: String,
    val startAt: Long,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val calories: Int,
    val avgHeartRate: Int,
    val maxHeartRate: Int,
    val steps: Int,
    val trainEffect: Double,
) {
    /** 平均配速（秒/公里），跑步才用得上 */
    val avgPaceSecPerKm: Int
        get() = if (distanceMeters <= 0) 0 else (durationSeconds * 1000 / distanceMeters)
}

/**
 * 读取并汇总小米健康数据。
 *
 * 每项独立 try-catch：某一项接口失败（设备不支持、该功能未开通）
 * 不该让整页变成空白，用户至少能看到其它能拿到的数据。
 */
class MiHealthOverviewReader(private val context: Context) {

    private val client by lazy {
        MiHealthClient(
            credentialsProvider = { MiHealthCredentialStore.load(context) },
            onCredentialsExpired = {
                val old = MiHealthCredentialStore.load(context)
                val fresh = old?.let {
                    MiHealthAuth().refreshWithPassToken(
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

    suspend fun load(): MiHealthOverview = withContext(Dispatchers.IO) {
        val cred = MiHealthCredentialStore.load(context)
        if (cred == null || !cred.isValid()) {
            return@withContext MiHealthOverview(error = "未登录小米账号")
        }

        val now = System.currentTimeMillis()
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val dayAgo = now - 24 * 60 * 60 * 1000L
        val weekAgo = now - 7 * 24 * 60 * 60 * 1000L
        val monthAgo = now - 30 * 24 * 60 * 60 * 1000L
        val counts = mutableMapOf<String, Int>()
        val sids = mutableSetOf<String>()

        /** 拉一族的原始点，顺带记录条数与来源 sid */
        suspend fun points(
            key: String,
            from: Long,
            to: Long,
            maxPages: Int = 3,
        ): List<JSONObject> {
            val result = runCatching {
                client.getFitnessDataByTime(key, from, to, maxPages)
            }.onFailure { Log.w(TAG, "fetch $key failed", it) }.getOrDefault(emptyList())
            counts["fitness:$key"] = result.size
            result.forEach { it.optString("sid").takeIf { s -> s.isNotBlank() }?.let(sids::add) }
            return result
        }

        // ---- fitness 原始记录 ----
        // 步数点同时带距离与卡路里，不必单独再拉 calories；
        // 但 calories 的采样密度更高（步数不动时也有），单独拉用于今日热量。
        val steps = points("steps", dayStart, now)
        val cal = points("calories", dayStart, now, maxPages = 2)
        val hr = points("heart_rate", dayAgo, now, maxPages = 5)
        val sleep = points("sleep", weekAgo, now, maxPages = 3)
        val spo2 = points("spo2", weekAgo, now, maxPages = 2)
        val stress = points("stress", weekAgo, now, maxPages = 2)
        val intensity = points("intensity", dayStart, now)
        val stand = points("valid_stand", dayStart, now)
        val pai = points("pai", monthAgo, now, maxPages = 1)
        val energy = points("energy", weekAgo, now, maxPages = 1)
        val headset = points("headset", dayStart, now, maxPages = 2)
        // 体重/血压/血糖/最大摄氧量/经期变化慢，放宽到 30 天才能取到值
        val weight = points("weight", monthAgo, now, maxPages = 1)
        val bp = points("blood_pressure", monthAgo, now, maxPages = 1)
        val sugar = points("blood_sugar", monthAgo, now, maxPages = 1)
        val vo2 = points("vo2_max", monthAgo, now, maxPages = 1)
        val menstruation = points("menstruation", monthAgo, now, maxPages = 1)
        val goalKey = points("goal", weekAgo, now, maxPages = 1)

        // ---- 聚合日报：三环目标 ----
        val goal = runCatching {
            client.getPaged(
                path = "/app/v1/data/get_aggregated_fitness_data_by_time",
                params = mapOf("tag" to "daily_fitness", "key" to "goal"),
                listField = "data_list",
                startTimeMillis = dayAgo,
                endTimeMillis = now,
                maxPages = 1,
            )
        }.onFailure { Log.w(TAG, "fetch aggregated goal failed", it) }.getOrDefault(emptyList())
            .lastOrNull()?.optString("value")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { parseGoal(JSONObject(it)) }.getOrNull() }
        counts["aggregated:goal"] = if (goal != null) 1 else 0

        // ---- 运动记录（近 30 天） ----
        val sports = runCatching {
            client.getPaged(
                path = "/app/v1/data/get_sport_records_by_time",
                params = emptyMap(),
                listField = "sport_records",
                startTimeMillis = monthAgo,
                endTimeMillis = now,
                maxPages = 3,
                pageSize = 50,
            )
        }.onFailure { Log.w(TAG, "fetch sport records failed", it) }.getOrDefault(emptyList())
            .mapNotNull { parseSport(it.also { s ->
                s.optString("sid").takeIf { x -> x.isNotBlank() }?.let(sids::add)
            }) }
            .sortedByDescending { it.startAt }
        counts["sport:records"] = sports.size

        // ---- 饮食记录（近 30 天，参数与时间戳都是秒） ----
        val diet = runCatching {
            client.getPaged(
                path = "/app/v1/data/get_diet_records_by_time",
                params = mapOf("dining" to 0),
                listField = "diet_records",
                startTimeMillis = monthAgo,
                endTimeMillis = now,
                maxPages = 2,
                timeInSeconds = true,
                pageSize = 50,
            )
        }.onFailure { Log.w(TAG, "fetch diet failed", it) }.getOrDefault(emptyList())
        counts["diet:records"] = diet.size
        val dietToday = diet.filter { it.optLong("time", it.optLong("dining_time", 0L)) * 1000 >= dayStart }

        // ---- 医疗 / 项目数据 ----
        val medical = runCatching {
            client.getPaged(
                path = "/app/v1/data/get_medical_data_by_time",
                params = mapOf("key" to "ecg"),
                listField = "data_list",
                startTimeMillis = monthAgo,
                endTimeMillis = now,
                maxPages = 1,
            )
        }.onFailure { Log.w(TAG, "fetch medical failed", it) }.getOrDefault(emptyList())
        counts["medical:ecg"] = medical.size

        val project = runCatching {
            client.getPaged(
                path = "/app/v1/data/get_project_data_by_time",
                params = mapOf("key" to "sleep_wake_circle"),
                listField = "data_list",
                startTimeMillis = monthAgo,
                endTimeMillis = now,
                maxPages = 1,
            )
        }.onFailure { Log.w(TAG, "fetch project failed", it) }.getOrDefault(emptyList())
        counts["project:sleep_wake_circle"] = project.size

        val hrValues = hr.mapNotNull { bpm(it) }
        val sleeps = sleep.mapNotNull { summarizeSleep(it) }.sortedByDescending { it.endAt }
        val headsetMinutes = headset.sumOf { p ->
            val v = valueOf(p)
            val s = v.optLong("start_time", 0L)
            val e = v.optLong("end_time", 0L)
            if (s > 0 && e > s) ((e - s) / 60).toInt() else 0
        }
        val headsetHigh = headset.count { valueOf(it).optBoolean("high", false) }

        MiHealthOverview(
            stepsToday = steps.sumOf { num(it, "steps", "step", "value", "count").toInt() },
            distanceToday = steps.sumOf { num(it, "distance").toInt() },
            caloriesToday = if (cal.isNotEmpty()) cal.sumOf { num(it, "calories") }
            else steps.sumOf { num(it, "calories") },
            activeMinutesToday = intensity.distinctBy { it.optLong("time", 0L) }.size,
            // 站立点带 start_time/end_time（一个有效站立小时一段），按段去重计数
            standHoursToday = stand.map { p ->
                valueOf(p).optLong("start_time", 0L).takeIf { it > 0 } ?: p.optLong("time", 0L)
            }.distinct().size,
            intensityMinutes = intensity.sumOf { num(it, "intensity", "value").toInt() },

            goal = goal,

            heartRateLast = hrValues.lastOrNull(),
            heartRateMin24h = hrValues.minOrNull(),
            heartRateMax24h = hrValues.maxOrNull(),
            heartRateAvg24h = hrValues.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            // 静息心率：接口没有专门字段，用当天最低值近似（这是常见做法）
            heartRateResting = hrValues.minOrNull(),
            heartRateSamples = hrValues.size,

            sleepLast = sleeps.firstOrNull(),
            sleepRecent = sleeps.take(7),

            spo2Last = spo2.lastOrNull { num(it, "spo2") > 0 }?.let { num(it, "spo2").toInt() },
            stressLast = stress.lastOrNull { num(it, "stress_value", "stress") > 0 }
                ?.let { num(it, "stress_value", "stress").toInt() },
            weightLast = weight.lastOrNull { num(it, "weight") > 0 }?.let { num(it, "weight") },
            vo2MaxLast = vo2.lastOrNull { num(it, "vo2_max", "vo2max") > 0 }
                ?.let { num(it, "vo2_max", "vo2max") },
            bloodPressureLast = bp.lastOrNull { p ->
                num(p, "systolic") > 0 && num(p, "diastolic") > 0
            }?.let { "${num(it, "systolic").toInt()}/${num(it, "diastolic").toInt()}" },
            bloodSugarLast = sugar.lastOrNull { num(it, "value", "blood_sugar") > 0 }
                ?.let { num(it, "value", "blood_sugar") },
            paiLast = pai.lastOrNull { num(it, "daily_pai", "pai", "value") > 0 }
                ?.let { num(it, "daily_pai", "pai", "value") },
            energyLast = energy.lastOrNull { num(it, "energy") != 0.0 }?.let { num(it, "energy") },
            menstruationLast = menstruation.lastOrNull()?.let { p ->
                valueOf(p).optString("start_date", "").takeIf { it.isNotBlank() }
                    ?: p.optLong("time", 0L).takeIf { it > 0 }?.let { fmtDate(it) }
            },

            headsetHighMinutes = headsetHigh,
            headsetTotalMinutes = headsetMinutes,

            sports = sports,
            sportDays30 = sports.map { fmtDate(it.startAt * 1000) }.distinct().size,

            dietCaloriesToday = dietToday.sumOf { num(it, "calories", "calorie") }.toInt(),
            dietCountToday = dietToday.size,

            medicalCount = medical.size,
            projectCount = project.size,

            counts = counts,
            sources = sids.toList().sorted(),
        )
    }

    /** 内层字段是 JSON 字符串，解析失败就当空对象 */
    private fun valueOf(p: JSONObject): JSONObject {
        val raw = p.optString("value")
        if (raw.isBlank()) return p
        return runCatching { JSONObject(raw) }.getOrDefault(p)
    }

    private fun bpm(p: JSONObject): Int? =
        num(valueOf(p), "bpm", "heart_rate").takeIf { it > 0 }?.toInt()

    private fun parseGoal(v: JSONObject): DailyGoal {
        var sA = 0; var sT = 0
        var cA = 0; var cT = 0
        var aA = 0; var aT = 0
        var stA = 0; var stT = 0

        v.optJSONArray("goal_items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optInt("field", 0)) {
                    1 -> { sA = o.optInt("achieved_value", 0); sT = o.optInt("target_value", 0) }
                    2 -> { cA = o.optInt("achieved_value", 0); cT = o.optInt("target_value", 0) }
                    4 -> { aA = o.optInt("achieved_value", 0); aT = o.optInt("target_value", 0) }
                }
            }
        }
        // 站立目标是独立对象，不在 goal_items 里
        v.optJSONObject("stand_goal")?.let {
            stA = it.optInt("achieved_value", 0)
            stT = it.optInt("target_value", 0)
        }
        if (sT == 0 && cT == 0 && aT == 0 && stT == 0) return DailyGoal()
        return DailyGoal(sA, sT, cA, cT, aA, aT, stA, stT)
    }

    private fun parseSport(p: JSONObject): SportRecord? {
        val v = valueOf(p)
        val start = v.optLong("start_time", p.optLong("time", 0L))
        if (start <= 0) return null
        return SportRecord(
            key = p.optString("key", v.optString("sport_key", "")),
            category = p.optString("category", v.optString("category", "")),
            startAt = start,
            durationSeconds = v.optInt("duration", 0),
            distanceMeters = v.optInt("distance", 0),
            calories = v.optInt("calories", 0),
            avgHeartRate = v.optInt("avg_hrm", 0),
            maxHeartRate = v.optInt("max_hrm", 0),
            steps = v.optInt("steps", 0),
            trainEffect = v.optDouble("train_effect", 0.0),
        )
    }

    private fun summarizeSleep(p: JSONObject): SleepSummary? {
        val v = valueOf(p)
        val start = v.optLong("bedtime", 0L).takeIf { it > 0 }
            ?: v.optLong("device_bedtime", 0L).takeIf { it > 0 }
            ?: v.optLong("start_time", 0L)
        val end = v.optLong("device_wake_up_time", 0L).takeIf { it > 0 }
            ?: v.optLong("wake_up_time", 0L).takeIf { it > 0 }
            ?: v.optLong("end_time", 0L)
        if (start <= 0 || end <= start) return null
        val minutes = v.optInt("duration", 0).takeIf { it > 0 }
            ?: ((end - start) / 60_000).toInt()
        return SleepSummary(
            startAt = start,
            endAt = end,
            minutes = minutes,
            deepMinutes = v.optInt("sleep_deep_duration", 0),
            lightMinutes = v.optInt("sleep_light_duration", 0),
            remMinutes = v.optInt("sleep_rem_duration", 0),
            awakeMinutes = v.optInt("sleep_awake_duration", 0),
            awakeCount = v.optInt("awake_count", 0),
            avgHeartRate = v.optInt("avg_hr", 0),
            minHeartRate = v.optInt("min_hr", 0),
            maxHeartRate = v.optInt("max_hr", 0),
            avgBreath = v.optInt("avg_breath", 0),
            avgSpo2 = v.optInt("avg_spo2", 0),
            incomplete = v.optBoolean("is_uncomplete", false),
        )
    }

    /** 采样点数值：不同 key 的字段名不一样，按候选顺序取第一个存在的数值 */
    private fun num(p: JSONObject, vararg names: String): Double {
        names.forEach { n ->
            if (p.has(n)) {
                val v = p.optDouble(n, Double.NaN)
                if (!v.isNaN()) return v
            }
        }
        return 0.0
    }

    private fun fmtDate(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    companion object {
        private const val TAG = "LifeBoardMiHealth"
    }
}

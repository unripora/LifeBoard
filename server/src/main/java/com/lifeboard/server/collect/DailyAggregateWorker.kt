package com.lifeboard.server.collect

import android.content.ContentValues
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.lifeboard.core.ipc.LifeBoardContract
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.AppUsageEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.health.SourceHealthChecker
import com.lifeboard.server.ipc.MetricWriter
import com.lifeboard.server.mihealth.MiHealthCredentialStore
import com.lifeboard.server.source.MiHealthDataSource
import com.lifeboard.server.source.ScreenTimeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 每日聚合 Worker（M1.3）。
 *
 * 每天 00:05 跑一次，写两件事：
 * 1. 把**昨天**的屏幕时长定稿写入 `metric` 表（昨天的数据在 0 点后就不再变化，可安全定稿）
 * 2. 顺带刷新**今天**的数据，让凌晨打开面板时也有值
 *
 * 同时刷新 `source_health` 里全部数据源的健康度，未授权时面板能直接看到断源原因，
 * 而不是傻等一个 0。
 */
class DailyAggregateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = ScreenTimeDataSource(applicationContext)
        val today = Dates.today()
        val yesterday = today.minusDays(1)

        // 每次聚合都刷新全部数据源的健康度，而不只是屏幕时长：
        // 通知使用权可能在两次聚合之间被系统回收，早发现早提示。
        runCatching { SourceHealthChecker.persist(applicationContext) }
            .onFailure { Log.e(TAG, "persist source health failed", it) }

        val granted = source.hasPermission()
        if (!granted) {
            Log.w(TAG, "usage access not granted, skip aggregate")
            return Result.success()
        }

        var ok = true
        ok = collectInto(source, yesterday) && ok
        ok = collectInto(source, today) && ok

        // 小米健康走云端接口，与屏幕时长互不影响：失败只影响自己，不拖累整体
        collectMiHealth(today, yesterday)
        return if (ok) Result.success() else Result.retry()
    }

    /**
     * 采集小米健康的步数与睡眠。
     *
     * 登录态缺失时直接跳过（用户还没配过账号，不是错误），
     * 其它异常只记日志——它属于可选数据源，不该让整个聚合任务失败重试。
     */
    private suspend fun collectMiHealth(today: LocalDate, yesterday: LocalDate) {
        if (!MiHealthCredentialStore.isLoggedIn(applicationContext)) {
            Log.i(TAG, "mi health not logged in, skip")
            return
        }
        val source = MiHealthDataSource(applicationContext)
        // 昨天的数据已定稿，今天的数据会随白天推进持续更新，两者都刷
        listOf(yesterday, today).forEach { day ->
            runCatching {
                source.collect(day.toString()).forEach { writeMetric(it) }
            }.onFailure { Log.w(TAG, "collect mi health failed for $day", it) }
        }
    }

    private suspend fun collectInto(source: ScreenTimeDataSource, day: LocalDate): Boolean {
        val date = day.toString()
        val records = runCatching { source.collect(date) }.getOrElse {
            Log.e(TAG, "collect failed for $date", it)
            return false
        }
        records.forEach { writeMetric(it) }
        writeAppUsage(source, day)
        Log.i(TAG, "aggregated $date: ${records.joinToString { "${it.bucket}=${it.value}" }}")
        return true
    }

    /**
     * 落库当天各应用的使用时长。
     *
     * 与总时长同一时刻采集：两者来自同一份系统汇总，
     * 分开采会出现「总分和明细加起来对不上」的困惑。
     * 用 (date, packageName) 做主键覆盖写，重复采集天然幂等。
     */
    private suspend fun writeAppUsage(source: ScreenTimeDataSource, day: LocalDate) {
        runCatching {
            val context = applicationContext
            val perApp = source.queryPerAppMinutes(day)
            if (perApp.isEmpty()) return@runCatching
            val dao = LifeBoardDatabase.get(context).appUsageDao()
            val date = day.toString()
            // 先清当天再整批写：系统汇总里被合并掉的旧包名不会残留
            dao.deleteByDate(date)
            dao.upsertAll(
                perApp.map { (pkg, minutes) ->
                    AppUsageEntity(
                        date = date,
                        packageName = pkg,
                        minutes = minutes,
                        label = appLabel(context, pkg),
                    )
                },
            )
            // 只留最近 14 天，分应用数据每天几十行，不清会一直涨
            dao.trimBefore(day.minusDays(APP_USAGE_KEEP_DAYS).toString())
        }.onFailure { Log.w(TAG, "write app usage failed for $day", it) }
    }

    /** 应用展示名，取不到就退回包名，不让一条查名失败拖垮整批写入 */
    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun writeMetric(record: MetricRecord) {
        MetricWriter.write(
            applicationContext,
            LifeBoardContract.PATH_METRIC,
            ContentValues().apply {
                put(LifeBoardContract.COL_SOURCE_TYPE, record.source.name)
                put(LifeBoardContract.COL_DATE, record.date)
                put(LifeBoardContract.COL_BUCKET, record.bucket.name)
                put(LifeBoardContract.COL_VALUE, record.value)
            },
        )
    }

    companion object {
        private const val TAG = "LifeBoardAggregate"
        private const val WORK_NAME = "lifeboard_daily_aggregate"

        /** 分应用用量保留天数：够用户回看最近两周，又不至于无限增长 */
        private const val APP_USAGE_KEEP_DAYS = 14L

        /**
         * 注册每日聚合任务。
         *
         * 用 [ExistingPeriodicWorkPolicy.UPDATE] 而不是 KEEP：改了这个 Worker 的实现后
         * 重新注册能直接生效，不会被旧的已存在任务挡住。
         *
         * 首次注册时初始化延迟设为"距下一个 00:05 的时长"，让第一次在预期时刻跑，
         * 而不是装上应用 0 分钟后就跑一次。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyAggregateWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMinutes(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "daily aggregate scheduled, first run in ${initialDelayMinutes()} min")
        }

        /** 距离下一个 00:05 还有多少分钟 */
        private fun initialDelayMinutes(): Long {
            val now = java.time.LocalDateTime.now()
            val next = now.toLocalDate().plusDays(1).atTime(0, 5)
            val minutes = java.time.Duration.between(now, next).toMinutes()
            return minutes.coerceIn(1, 24 * 60)
        }

        /** 立即跑一次，用于面板手动刷新或首次授权后立即出数 */
        fun runNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<DailyAggregateWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * 立即采集并**等它写完**再返回。
         *
         * [runNow] 只是入队，Worker 何时被调度、写库何时完成都不确定，
         * 调用方紧接着读库必然拿到上一次的旧值——界面表现为「点一次没反应、
         * 点第二次才有数据」。手动采集这类需要立刻出数的场景必须走这里。
         */
        suspend fun collectNow(context: Context): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val request = androidx.work.OneTimeWorkRequestBuilder<DailyAggregateWorker>().build()
                val wm = WorkManager.getInstance(context)
                wm.enqueue(request)
                // 等到 Worker 进入终态：写库发生在 doWork 内，终态才代表数据已落库
                wm.getWorkInfoByIdFlow(request.id)
                    .filterNotNull()
                    .first { it.state.isFinished }
                    .state == WorkInfo.State.SUCCEEDED
            }.getOrDefault(false)
        }
    }
}

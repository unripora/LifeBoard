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
import androidx.work.WorkerParameters
import com.lifeboard.core.ipc.LifeBoardContract
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.util.Dates
import com.lifeboard.server.health.SourceHealthChecker
import com.lifeboard.server.ipc.MetricWriter
import com.lifeboard.server.source.ScreenTimeDataSource
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
        return if (ok) Result.success() else Result.retry()
    }

    private suspend fun collectInto(source: ScreenTimeDataSource, day: LocalDate): Boolean {
        val date = day.toString()
        val records = runCatching { source.collect(date) }.getOrElse {
            Log.e(TAG, "collect failed for $date", it)
            return false
        }
        records.forEach { writeMetric(it) }
        Log.i(TAG, "aggregated $date: ${records.joinToString { "${it.bucket}=${it.value}" }}")
        return true
    }

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
    }
}

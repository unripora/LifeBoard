package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.collect.ScreenTimeReader
import com.lifeboard.server.source.ScreenTimeDataSource
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.repository.model.AppUsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [ScreenTimeBackend] 的 Android 实现，委托既有的读取器与采集任务。 */
class AndroidScreenTimeBackend(context: Context) : ScreenTimeBackend {

    private val appContext = context.applicationContext

    private val reader = ScreenTimeReader(appContext)

    private val source = ScreenTimeDataSource(appContext)

    override suspend fun hasPermission(): Boolean = withContext(Dispatchers.IO) {
        source.hasPermission()
    }

    override suspend fun collectNow(): Boolean = withContext(Dispatchers.IO) {
        DailyAggregateWorker.collectNow(appContext)
    }

    override suspend fun todayMinutes(): Double = withContext(Dispatchers.IO) {
        reader.todayMinutes()
    }

    override suspend fun thisWeekMinutes(): Double = withContext(Dispatchers.IO) {
        reader.thisWeekMinutes()
    }

    override suspend fun thisWeekDaily(): List<ScreenTimeBackend.DailyPoint> =
        withContext(Dispatchers.IO) {
            reader.thisWeekDaily().map {
                ScreenTimeBackend.DailyPoint(
                    date = it.first.toString(),
                    minutes = it.second,
                )
            }
        }

    override suspend fun todayAppUsage(): List<AppUsageRecord> = withContext(Dispatchers.IO) {
        reader.todayAppUsage()
    }
}

package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.util.Dates
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.server.notice.NoticeListenerService
import com.lifeboard.server.notice.NoticeSourceStatus
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.source.SourceRegistry
import com.lifeboard.shared.platform.RulesBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [RulesBackend] 的 Android 实现，全部委托既有实现。 */
class AndroidRulesBackend(context: Context) : RulesBackend {

    private val appContext = context.applicationContext

    override suspend fun isNotificationListenerEnabled(): Boolean =
        NoticeListenerService.isEnabled(appContext)

    override suspend fun isCaptureServiceEnabled(): Boolean =
        ScreenCaptureAccessibilityService.isEnabled(appContext)

    override suspend fun todayCount(sourceKey: String, bucket: String): Double =
        withContext(Dispatchers.IO) {
            NoticeSourceStatus.todayCount(appContext, sourceKey, bucket)
        }

    override suspend fun clearTodayCount(sourceKey: String, bucket: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val ref = SourceRegistry.resolve(appContext, sourceKey)
                val date = Dates.todayString()
                DatabaseProviders.metricStore(appContext)
                    .saveMetric(
                        MetricRecord(
                            source = SourceRef.Builtin(
                                key = ref.key,
                                displayName = ref.displayName,
                                bucket = MetricSpec.bucketOf(bucket) ?: MetricBucket.COUNT,
                            ),
                            date = date,
                            value = 0.0,
                        ),
                    )
            }
        }
    }

    override suspend fun notifyRulesChanged() {
        ScreenCaptureAccessibilityService.notifyRulesChanged()
    }

    override suspend fun rescheduleReminders() {
        withContext(Dispatchers.IO) {
            runCatching { RemindScheduler.rescheduleAll(appContext) }
        }
    }

    override suspend fun selectableSources(): List<SourceRef> = withContext(Dispatchers.IO) {
        SourceRegistry.selectable(appContext)
    }

    override suspend fun referenceCount(sourceKey: String): Int = withContext(Dispatchers.IO) {
        SourceRegistry.referenceCount(appContext, sourceKey)
    }
}

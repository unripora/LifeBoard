package com.lifeboard.server.notice

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.db.RawNotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 通知监听服务（M4.1）。
 *
 * 只在"应用正在推送新通知"时累加计数，不读历史通知栏——
 * [onNotificationPosted] 拿到的才是本次新发生的，[onListenerConnected] 里
 * 若去遍历 activeNotifications 会把今天已读过的全算一遍，导致重复计数。
 *
 * 计数直接落 `metric` 表，键为 (sourceType, date, bucket)，
 * 累加而不是覆盖：同一天多次命中要叠加，这是"次数"语义。
 */
class NoticeListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = buildString {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it) }
            // 部分应用的正文放在 bigText 里，EXTRA_TEXT 只有一句摘要
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }

        scope.launch {
            runCatching { handleNotification(pkg, title, text) }
                .onFailure { Log.w(TAG, "handle notification failed", it) }
        }
    }

    /**
     * 用用户配置的规则判定这条通知。
     *
     * 无论命中与否都记原始流水：关键词写错时这页是唯一证据，
     * 只记命中的话规则错时永远是空的，等于没有排查手段。
     */
    private suspend fun handleNotification(pkg: String, title: String?, text: String) {
        val rules = LifeBoardDatabase.get(this).captureRuleDao().getEnabledByPackage(pkg)
        val content = buildString {
            if (!title.isNullOrBlank()) append(title)
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(text)
            }
        }
        val hits = if (rules.isEmpty()) {
            emptyList()
        } else {
            CaptureRuleMatcher.match(rules, content, LocalDateTime.now())
        }

        runCatching { logRaw(pkg, title, text, hits.map { it.rule.sourceName }) }
            .onFailure { Log.w(TAG, "log raw notification failed", it) }

        if (hits.isEmpty()) return

        val date = Dates.todayString()
        val dao = LifeBoardDatabase.get(this).metricDao()
        hits.forEach { hit ->
            val rule = hit.rule
            runCatching {
                val existing = dao.getOne(rule.sourceName, date, rule.bucketName)
                val current = existing?.value ?: 0.0
                if (rule.dailyOnce && current >= 1.0) return@forEach
                val next = if (rule.dailyOnce) 1.0 else current + 1.0
                dao.upsert(
                    MetricEntity(
                        sourceType = rule.sourceName,
                        date = date,
                        bucket = rule.bucketName,
                        value = next,
                    ),
                )
                Log.i(TAG, "matched ${rule.sourceName} += -> $next")
            }.onFailure { Log.w(TAG, "accumulate failed for ${rule.sourceName}", it) }
        }
    }

    private suspend fun logRaw(
        pkg: String,
        title: String?,
        text: String,
        matchedSources: List<String>,
    ) {
        LifeBoardDatabase.get(this).rawNotificationDao().insert(
            RawNotificationEntity(
                packageName = pkg,
                title = title ?: "",
                text = text,
                matchedSources = matchedSources.joinToString(","),
            ),
        )
        // 顺手裁剪，只留最近 200 条，防止长期使用无限膨胀
        LifeBoardDatabase.get(this).rawNotificationDao().trim(200)
    }

    companion object {
        private const val TAG = "LifeBoardNotice"

        /** 供权限自检查询服务是否已连接 */
        @Volatile
        var instance: NoticeListenerService? = null
            private set

        /**
         * 检查通知使用权是否已授予。
         *
         * 比 `NotificationManagerCompat` 更可靠：直接查"通知使用权"列表里有没有本应用。
         * 注意这里的 enabled_listeners 是扁平的 "pkg/cls" 字符串集合，
         * 旧版本用 ':' 分隔，所以用 contains 而不是 equals 断言。
         */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val component = ComponentName(context, NoticeListenerService::class.java)
            return flat.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }
    }
}

/**
 * 通知类数据源的健康度检查（M4.3）。
 *
 * 这里不实现 [com.lifeboard.core.source.DataSource]：通知是实时累加的，
 * 没有"按日期回查"的能力，硬套接口会写出一个永远返回 0 的 collect。
 */
object NoticeSourceStatus {
    fun isHealthy(context: Context): Boolean = NoticeListenerService.isEnabled(context)

    fun message(context: Context): String =
        if (isHealthy(context)) "" else "未授予「通知使用权」，无法从通知中识别打卡"

    /** 某源当日已累计的计数（按桶名字符串，规则表里存的就是字符串） */
    suspend fun todayCount(context: Context, sourceType: String, bucketName: String): Double {
        val dao = LifeBoardDatabase.get(context).metricDao()
        return dao.getOne(sourceType, Dates.todayString(), bucketName)?.value ?: 0.0
    }

    /** 某源当日已累计的计数（按枚举，供既有调用方使用） */
    suspend fun todayCount(context: Context, sourceType: String, bucket: MetricBucket): Double =
        todayCount(context, sourceType, bucket.name)
}

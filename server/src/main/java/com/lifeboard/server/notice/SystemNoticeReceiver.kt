package com.lifeboard.server.notice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
 * system_server 通知 hook 的接收端。
 *
 * [com.lifeboard.app.xposed.LifeBoardEntry] 拦截 `enqueueNotificationInternal` /
 * `notifyCallNotificationEventListenerOnPosted` 后发显式广播到这里，本接收器
 * 用与通知监听、读屏采集**同一套**用户规则做判定并落库。
 *
 * 与监听服务双通道并存：hook 通道能抓到 app 前台时的静默通知（监听器收不到），
 * 监听通道在 hook 未生效（模块未启用/重启后 LSPosed 未加载）时兜底。
 * 两通道各自去重：hook 侧有 10 秒内容窗口，落库前这里再按
 * (pkg, title, text, 5 秒) 挡一次跨通道重复。
 */
class SystemNoticeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTICE_REPORTED) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        if (title.isBlank() && text.isBlank()) return

        val content = buildString {
            if (title.isNotBlank()) append(title)
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(text)
            }
        }
        val now = System.currentTimeMillis()
        if (isDuplicate(pkg, title, text, now)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(appContext, pkg, title, text, content)
            } catch (t: Throwable) {
                Log.w(TAG, "handle hook notification failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        pkg: String,
        title: String,
        text: String,
        content: String,
    ) {
        val db = LifeBoardDatabase.get(context)
        val rules = db.captureRuleDao().getEnabledByPackage(pkg)
        val hits = if (rules.isEmpty()) {
            emptyList()
        } else {
            CaptureRuleMatcher.match(rules, content, LocalDateTime.now())
        }

        runCatching {
            db.rawNotificationDao().insert(
                RawNotificationEntity(
                    packageName = pkg,
                    title = title,
                    text = text,
                    matchedSources = hits.joinToString(",") { it.rule.sourceName },
                ),
            )
            db.rawNotificationDao().trim(200)
        }.onFailure { Log.w(TAG, "log raw failed", it) }

        if (hits.isEmpty()) return

        val date = Dates.todayString()
        val dao = db.metricDao()
        hits.forEach { hit ->
            val rule = hit.rule
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
            Log.i(TAG, "matched ${rule.sourceName} -> $next")
        }
    }

    /** 跨通道去重：hook 与监听器可能同时报同一条通知 */
    private fun isDuplicate(pkg: String, title: String, text: String, now: Long): Boolean =
        synchronized(lastSeen) {
            lastSeen.entries.removeIf { now - it.value > CROSS_CHANNEL_WINDOW_MS }
            val key = "$pkg|$title|$text"
            val last = lastSeen[key]
            if (last != null && now - last <= CROSS_CHANNEL_WINDOW_MS) {
                true
            } else {
                lastSeen[key] = now
                while (lastSeen.size > 100) {
                    lastSeen.remove(lastSeen.keys.first())
                }
                false
            }
        }

    companion object {
        private const val TAG = "LifeBoardNotice"
        const val ACTION_NOTICE_REPORTED = "com.lifeboard.action.SYSTEM_NOTICE_REPORTED"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val CROSS_CHANNEL_WINDOW_MS = 5_000L
        private val lastSeen = LinkedHashMap<String, Long>()
    }
}

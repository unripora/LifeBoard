package com.lifeboard.server.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.lifeboard.shared.platform.Scheduler

/**
 * [Scheduler] 的 Android 实现，基于 `AlarmManager`。
 *
 * **与 `RemindScheduler` 的关系**：那个类是「按任务算触发时刻并排闹钟」的
 * 业务实现，算窗口、算偏移、处理多时段，逻辑都在它那里。
 * 本类只提供通用的 `tag → 定时` 能力，供不属于任务提醒的场景使用
 * （如每日聚合、插件刷新）。两者不互相替代，也不要把本类塞进 `RemindScheduler`。
 *
 * 用 `setAndAllowWhileIdle` 而非 `setExact`：后者在低电量下会被直接丢弃，
 * 而提醒类应用宁可晚几分钟也不该漏。需要精确触发的场景自行调 `canScheduleExact`。
 */
class AndroidScheduler(private val context: Context) : Scheduler {

    private val appContext = context.applicationContext

    /**
     * 本进程排过的 tag 集合。
     *
     * `AlarmManager` 没有「列出本应用全部闹钟」的接口，所以 `cancelAll` 只能靠
     * 自己记账。这里用 `SharedPreferences` 而不是内存 Set：进程被系统杀掉后
     * 闹钟仍然存在，仅存在内存里的账本会丢，`cancelAll` 就清不掉了。
     */
    private val tagsStore = AndroidSettingsStore(appContext, TAGS_FILE)

    private fun alarmManager(): AlarmManager? =
        appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun rememberTag(tag: String) {
        val all = tagsStore.getString(KEY_TAGS).split(SEP).filter { it.isNotBlank() }.toMutableSet()
        if (all.add(tag)) tagsStore.putString(KEY_TAGS, all.joinToString(SEP))
    }

    private fun forgetTag(tag: String) {
        val all = tagsStore.getString(KEY_TAGS).split(SEP).filter { it.isNotBlank() }.toMutableSet()
        if (all.remove(tag)) tagsStore.putString(KEY_TAGS, all.joinToString(SEP))
    }

    /**
     * 每个 tag 一个稳定的 requestCode。
     *
     * 用 tag 的 hash 而不是自增序号：进程重启后 hash 仍一致，
     * 才能靠同一个 requestCode 找到并取消上次排的那次。
     * 冲突概率可接受（同 hash 会相互覆盖），比「重启后取消不掉」的后果轻得多。
     */
    private fun requestCode(tag: String): Int = tag.hashCode()

    private fun pendingIntent(tag: String, payload: String?, create: Boolean): PendingIntent? {
        val intent = Intent(appContext, SchedulerReceiver::class.java).apply {
            // action 带上 tag，让同一组件能区分不同来源
            action = ACTION_FIRE
            data = Uri.parse("lifeboard://scheduler/$tag")
            putExtra(EXTRA_TAG, tag)
            putExtra(EXTRA_PAYLOAD, payload)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(appContext, requestCode(tag), intent, flags)
    }

    override suspend fun scheduleAt(tag: String, triggerAtMillis: Long, payload: String?) {
        val am = alarmManager() ?: return
        val pi = pendingIntent(tag, payload, create = true) ?: return
        rememberTag(tag)
        // 到期就用免打扰友好的 setAndAllowWhileIdle；
        // 不具备精确闹钟权限时它会退化成普通 alarm，语义仍是「尽量准时」
        if (canScheduleExact()) {
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    override suspend fun scheduleRepeating(tag: String, intervalMinutes: Long, payload: String?) {
        val am = alarmManager() ?: return
        val pi = pendingIntent(tag, payload, create = true) ?: return
        rememberTag(tag)
        // Android 对 repeating 的最小间隔没有硬限制，但低于 15 分钟会明显耗电，
        // 系统在省电模式下会把更密的重复闹钟合并
        val intervalMillis = (intervalMinutes * 60_000L).coerceAtLeast(15 * 60_000L)
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMillis,
            intervalMillis,
            pi,
        )
    }

    override suspend fun cancel(tag: String) {
        val am = alarmManager() ?: return
        // create = false：这个 tag 本来就没排过时不要顺手造一个 PendingIntent 出来，
        // 那样 cancel 掉的是刚造出来的空壳，真实那条还在
        val pi = pendingIntent(tag, payload = null, create = false)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
        forgetTag(tag)
    }

    override suspend fun cancelAll() {
        tagsStore.getString(KEY_TAGS).split(SEP)
            .filter { it.isNotBlank() }
            .forEach { cancel(it) }
        tagsStore.remove(KEY_TAGS)
    }

    override suspend fun isScheduled(tag: String): Boolean =
        pendingIntent(tag, payload = null, create = false) != null

    private fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = alarmManager() ?: return false
        return am.canScheduleExactAlarms()
    }

    companion object {
        const val ACTION_FIRE = "com.lifeboard.server.platform.SCHEDULER_FIRE"
        const val EXTRA_TAG = "tag"
        const val EXTRA_PAYLOAD = "payload"

        private const val TAGS_FILE = "scheduler_tags"
        private const val KEY_TAGS = "tags"
        private const val SEP = "\n"

        /** tag → 到点回调 */
        private val listeners = java.util.concurrent.ConcurrentHashMap<
            String, (payload: String?) -> Unit>()

        /** 挂一个到点回调，同 tag 覆盖 */
        fun register(tag: String, listener: (payload: String?) -> Unit) {
            listeners[tag] = listener
        }

        fun unregister(tag: String) {
            listeners.remove(tag)
        }

        internal fun dispatch(tag: String, payload: String?) {
            listeners[tag]?.invoke(payload)
        }
    }
}

/**
 * 到点接收器。
 *
 * 把广播转成一次对进程内回调的分发。契约层没有「注册监听」的方法是因为
 * 两端机制不同（Android 广播 / 桌面定时器回调），但触发事件总得有人接住——
 * 这里用一张 tag → 回调的表，业务侧调 [AndroidScheduler.register] 挂上即可。
 *
 * 表是进程内的：进程已被回收时闹钟到了也只会唤醒一次空表，
 * 所以调用方仍需在启动时重新注册一遍（契约里已声明「不保证进程存活」）。
 */
class SchedulerReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidScheduler.ACTION_FIRE) return
        val tag = intent.getStringExtra(AndroidScheduler.EXTRA_TAG) ?: return
        val payload = intent.getStringExtra(AndroidScheduler.EXTRA_PAYLOAD)
        AndroidScheduler.dispatch(tag, payload)
    }
}

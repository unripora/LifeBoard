package com.lifeboard.desktop.backend

import com.lifeboard.core.util.Dates
import com.lifeboard.shared.db.DatabaseStores
import com.lifeboard.shared.panel.ConditionEvaluator
import com.lifeboard.shared.platform.CollectionBackend
import com.lifeboard.shared.platform.RefreshInterval
import com.lifeboard.shared.platform.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [CollectionBackend] 的桌面实现（M4-4.5c）：把自动刷新真正接到定时器上。
 *
 * ## 之前为什么是 `Unsupported`
 *
 * M3 阶段桌面端还没有可信的调度（`Scheduler` 是占位实现），
 * 所以 `supportsAutoRefresh = false`，设置页把整块选项换成说明文字。
 * 那时给用户一组「选了没用」的按钮，比不给更糟。
 *
 * 现在有了 `DesktopScheduler`（进程内协程定时器），这条通路可以真接上了。
 *
 * ## 「自动刷新」在桌面端到底做什么
 *
 * 桌面端**没有**「从系统读健康数据」这回事——数据来自插件与手动录入。
 * 所以周期任务做的事是：**重新判定当日所有任务的达成状态**。
 * 这与 Android 的 `DailyAggregateWorker` 在「聚合 + 判定」这个意义上等价，
 * 但不需要拉系统数据源。插件的同步由插件自己在被调用时处理。
 *
 * ## 一个必须承认的限制
 *
 * 定时器活在进程内，**应用关掉就不再刷新**。这与 `Scheduler` 契约一致，
 * 也符合桌面应用的用户预期（关掉程序就不该在后台干活）。
 * 所以设置页的说明文案要写清这一点，而不是让用户以为「设了每天就每天都会跑」。
 *
 * @param refreshTag 周期任务的 tag。用固定值而不是按任务 id 分开：
 *   一次刷新要判定所有任务，本来就是一个整体动作
 */
class DesktopCollectionBackend(
    private val stores: DatabaseStores,
    private val scheduler: com.lifeboard.desktop.schedule.DesktopScheduler,
    private val settings: SettingsStore,
    private val appScope: CoroutineScope,
) : CollectionBackend {

    /**
     * 当前周期。
     *
     * **同步读**：首帧渲染前就要用来画选中态，不能是 suspend。
     * `java.util.prefs` 的读取本身是内存操作（首次会读注册表），够快。
     */
    override fun currentInterval(): RefreshInterval =
        // 空字符串会被 fromName 当成未知值回退默认；
        // 这样写是因为 Preferences.getString 的默认值参数必须非空
        RefreshInterval.fromName(settings.getString(KEY_INTERVAL, "").ifBlank { null })

    /**
     * 写入新周期并按新周期重排。
     *
     * 存与排必须成对完成——只存不排会出现「设置改了但频率没变」，
     * 而这种漏调不会报错。契约把它们收成一个动作正是为了这个。
     */
    override suspend fun setInterval(interval: RefreshInterval) {
        settings.putString(KEY_INTERVAL, interval.name)

        // 先撤掉旧的，再按新周期挂上。OFF 就只撤不挂
        scheduler.cancel(TAG)

        if (interval.enabled) {
            scheduler.register(TAG) {
                appScope.launch { runRefresh() }
            }
            scheduler.scheduleRepeating(TAG, interval.minutes)
        }
    }

    /** 桌面端已有可信调度，支持自动刷新 */
    override val supportsAutoRefresh: Boolean = true

    /**
     * 启动时把已保存的周期接上。
     *
     * 与 `RemindBackend.rescheduleAll()` 同理：定时器活在进程内，
     * 不重启登记就等于「上次设的自动刷新在重启后静默失效」。
     */
    suspend fun restoreOnStartup() {
        val interval = currentInterval()
        if (!interval.enabled) return
        scheduler.register(TAG) {
            appScope.launch { runRefresh() }
        }
        scheduler.scheduleRepeating(TAG, interval.minutes)
    }

    /** 一次刷新：重新判定当日所有任务 */
    private suspend fun runRefresh() {
        withContext(Dispatchers.IO) {
            runCatching {
                ConditionEvaluator(stores.taskStore, stores.metricStore)
                    .evaluateAll(Dates.todayString(), persist = true)
            }
        }
    }

    private companion object {
        const val TAG = "collect:refresh"

        /**
         * 偏好键。
         *
         * 与 Android 的 `CollectionPrefs` **用同一个键名**是刻意的：
         * 两端读写各自的偏好存储，但键名一致让「哪些配置项存在」这件事
         * 可以对照着查，排查时不用记两套名字。
         */
        const val KEY_INTERVAL = "collect_interval"
    }
}

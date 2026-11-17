package com.lifeboard.desktop.schedule

import com.lifeboard.shared.platform.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [Scheduler] 的桌面实现：进程内协程定时器（M4-4.5）。
 *
 * ## 与 Android 的语义差异，以及为什么这个差异是对的
 *
 * Android 侧用 `AlarmManager`：闹钟由**系统**持有，进程被杀了到点也能拉起进程。
 * 桌面端没有等价物——它没有「系统级闹钟 + 唤醒进程」这层机制
 * （Windows 任务计划确实能做，但那意味着要为每个提醒注册一个计划任务，
 * 用户会在任务计划程序里看到几十条陌生条目，且卸载残留清理不干净）。
 *
 * 所以这里的选择是：**定时器活在进程内，进程关掉就不再触发**。
 * 这与 `Scheduler` 契约里已经写明的「不承诺进程活没活」完全一致，
 * 而且对桌面应用来说是符合直觉的：用户关掉程序，就不该再被它打扰。
 *
 * 代价只有一个：**重启后要重新登记**。这条由业务层在启动时
 * 调一次 `rescheduleAll()` 解决，而不是让 `Scheduler` 自己持久化——
 * 后者会让「改了任务时间但只同步了一半」变成两个源各说各话。
 *
 * ## 覆盖语义
 *
 * 契约要求同 tag 重复注册时**覆盖**而不是叠加（否则任务改时间后用户被新旧两个闹钟各打扰一次）。
 * 这里每个 tag 只保留一个 [Job]，注册新的时候先 `cancel()` 旧的那条。
 *
 * ## 线程模型
 *
 * 所有操作在 [Dispatchers.Default] 上跑。`delay` 不占线程，
 * 几十个 tag 同时挂着也只是几十个挂起的协程，没有线程开销。
 */
class DesktopScheduler : Scheduler {

    /**
     * 定时器所在的 scope。
     *
     * `SupervisorJob` 是必要的：某个 tag 的回调抛了异常，
     * 不该让**其它所有**提醒一起停掉。用普通 `Job` 的话，
     * 一个插件的 bug 会让全天的提醒静默消失。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** tag → 挂着的定时任务 */
    private val timers = ConcurrentHashMap<String, Job>()

    /** tag → 到点回调 */
    private val listeners = ConcurrentHashMap<String, (payload: String?) -> Unit>()

    /**
     * 挂一个到点回调，同 tag 覆盖。
     *
     * 与 Android 侧 `AndroidScheduler.register` 对应。业务侧（`DesktopRemindBackend`）
     * 在启动时把每个 tag 该做什么注册进来，之后 `scheduleAt` 只负责计时。
     *
     * 回调在 [Dispatchers.Default] 上被调用，**不在 UI 线程**：
     * 需要更新界面的回调必须自己切线程。
     */
    fun register(tag: String, listener: (payload: String?) -> Unit) {
        listeners[tag] = listener
    }

    fun unregister(tag: String) = listeners.remove(tag)

    override suspend fun scheduleAt(tag: String, triggerAtMillis: Long, payload: String?) {
        // 覆盖：先撤掉旧的那次
        timers.remove(tag)?.cancel()

        val wait = triggerAtMillis - System.currentTimeMillis()
        if (wait <= 0) {
            // 已经过点了。立即触发而不是丢弃：
            // 业务层算出「应该现在提醒」时，晚几百毫秒总比整天不提醒好
            // （这也是 Android 侧 `setAndAllowWhileIdle` 传过去时刻时的实际行为）
            fireNow(tag, payload)
            return
        }

        timers[tag] = scope.launch {
            delay(wait)
            // 到自己把自己从表里摘掉：否则 isScheduled 会对一个已经触发过的
            // 一次性任务一直返回 true
            timers.remove(tag)
            listeners[tag]?.invoke(payload)
        }
    }

    override suspend fun scheduleRepeating(tag: String, intervalMinutes: Long, payload: String?) {
        timers.remove(tag)?.cancel()

        // 桌面端不需要 Android 那个 15 分钟下限（那里是省电策略）。
        // 但 0 或负数会让 delay 变成忙循环，必须挡住
        val intervalMillis = (intervalMinutes * 60_000L).coerceAtLeast(1_000L)

        timers[tag] = scope.launch {
            // 第一次等一个周期再触发，与 Android `setInexactRepeating` 的
            // 「now + interval 起算」一致
            while (isActive) {
                delay(intervalMillis)
                listeners[tag]?.invoke(payload)
            }
        }
    }

    override suspend fun cancel(tag: String) {
        timers.remove(tag)?.cancel()
    }

    override suspend fun cancelAll() {
        // 必须先把 Job 取出来再清表：
        // ConcurrentHashMap.clear() 只是断开引用，协程还在 scope 里继续 delay，
        // 漏掉 cancel 就会出现「界面显示已清空、实际到点仍然弹提醒」
        val pending = timers.values.toList()
        timers.clear()
        pending.forEach { it.cancel() }
    }

    /**
     * 停掉全部定时器并让 scope 失效。
     *
     * 退出应用时调用。与 [cancelAll] 的区别：那个是「清空排程，之后还能继续用」，
     * 这个是「这个实例不再用了」。`scope.cancel()` 会把所有挂起的 `delay` 一起结束，
     * 不会留下还在计时的协程让 JVM 无法退出。
     */
    fun shutdown() {
        timers.clear()
        listeners.clear()
        scope.cancel()
    }

    override suspend fun isScheduled(tag: String): Boolean = timers[tag]?.isActive == true

    /**
     * 立即触发一次，用于「已过点」。
     *
     * 用 `launch` 而不是直接调：调用方（`scheduleAt`）是 `suspend`，
     * 若在这里同步调回调，回调里再调 `scheduleAt` 就会同步递归。
     */
    private fun fireNow(tag: String, payload: String?) {
        scope.launch { listeners[tag]?.invoke(payload) }
    }
}

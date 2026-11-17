package com.lifeboard.shared.platform

/**
 * 定时唤醒。
 *
 * 契约只承诺「到点了会叫你」，**不承诺精度、不承诺进程活没活**：
 * Android 的 `AlarmManager` 在低电量下会被推迟，桌面端进程被关掉就直接不触发。
 * 需要「重启/重开后仍然生效」的，必须由业务层在启动时重新注册一遍，
 * 不能指望这个接口替你记住。
 */
interface Scheduler {

    /**
     * 在 [triggerAtMillis] 触发一次 [tag]。
     *
     * 同一 [tag] 重复注册时**覆盖**旧的那次，而不是叠加。
     * 覆盖语义是必要的：任务改时间后若叠加，用户会被新旧两个闹钟各打扰一次。
     */
    suspend fun scheduleAt(tag: String, triggerAtMillis: Long, payload: String? = null)

    /**
     * 每 [intervalMinutes] 分钟重复一次 [tag]。
     *
     * 最小间隔由实现方保证（Android 侧低于 15 分钟会被系统拉回 15 分钟），
     * 调用方传更小的值时不要假设真的按那个频率走。
     */
    suspend fun scheduleRepeating(tag: String, intervalMinutes: Long, payload: String? = null)

    /** 取消一个 [tag]，不存在时静默返回 */
    suspend fun cancel(tag: String)

    /** 取消全部，用于「重置所有提醒」这类操作 */
    suspend fun cancelAll()

    /** 某个 [tag] 是否还挂着，用于界面显示「已排程」 */
    suspend fun isScheduled(tag: String): Boolean
}

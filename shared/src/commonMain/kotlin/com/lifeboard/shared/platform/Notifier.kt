package com.lifeboard.shared.platform

/**
 * 提醒的送达程度。
 *
 * 与 `:core` 的 `RemindLevel`（用户配置的「想怎么被提醒」）是两件事：
 * 这里是**实际送达成了什么**——用户配了 STRONG，但通知权限被关、
 * 声音通道被静音，实际可能只送达了 WEAK。日志与提醒验证页要记的是后者。
 */
enum class DeliveryLevel {
    NONE,
    WEAK,
    STRONG,
}

/**
 * 一条待发送的通知。
 *
 * @param channelId 通道标识。两端的通道模型不同，实现方负责在自己的系统里建通道；
 *   契约只保证「同一个 channelId 落的通知会归到同一个开关下面」。
 * @param important 是否走「重要」通道。三档提醒中强提醒靠它区分，
 *   不在这里传而让实现方自己猜，会出现「配置改了但提醒强度没变」。
 * @param payload 点击通知时回传给业务层的字符串，形如 `task:42`
 */
data class NotificationRequest(
    val id: Int,
    val title: String,
    val body: String,
    val channelId: String,
    val important: Boolean,
    val payload: String? = null,
)

/**
 * 通知发送。
 *
 * 返回 [DeliveryLevel] 而不是 `Unit`：能不能真的响，取决于运行时的权限、
 * 勿扰模式、通道被静音等一堆宿主不可能预先知道的状态。
 * 让实现方在发送后如实回报，日志里才留得下「配了强提醒但只送达弱提醒」这种证据。
 *
 * ## 点击回调为什么在这里而不是在 notify 的返回值里
 *
 * 用户点通知是**异步且可能很晚**的事件：气泡发出后十分钟他才点，那时 `notify`
 * 早就返回了。所以只能反过来，由实现方持有回调、在点击发生时调用它。
 *
 * 两端的落地方式不同但语义相同：
 *
 * | 环节 | Android | 桌面 |
 * |---|---|---|
 * | 点击如何送达 | `PendingIntent` + `Intent` extra | `TrayIcon` 的 `ActionListener` |
 * | 由谁唤起界面 | `MainActivity` 从 `getIntent()` 取 | 主窗口 `toFront()` |
 *
 * 回调统一在**主线程**触发，实现方负责切线程。
 */
interface Notifier {

    /** 发一条通知，返回实际送达程度 */
    suspend fun notify(request: NotificationRequest): DeliveryLevel

    /** 撤销一条已发出的通知（任务已完成时用） */
    suspend fun cancel(id: Int)

    /** 当前是否具备发通知的权限（Android 是 POST_NOTIFICATIONS，桌面恒为 true） */
    suspend fun isPermitted(): Boolean

    /** 跳系统设置页让用户授权；桌面端无此概念，实现为空 */
    suspend fun openPermissionSettings()

    /**
     * 注册「用户点了某条通知」的回调。
     *
     * 入参是该通知发出时携带的 [NotificationRequest.payload]——点击的人可能
     * 点的是十分钟前那条，所以必须由通知自己带着身份回来，不能靠「当前提醒的是哪个」。
     *
     * 默认空实现：让只想发通知、不关心点击的实现（如测试桩）不必写一个空 lambda。
     */
    fun setOnNotificationClick(handler: (payload: String?) -> Unit) = Unit
}

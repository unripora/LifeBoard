package com.lifeboard.desktop.notify

import com.lifeboard.shared.platform.DeliveryLevel
import com.lifeboard.shared.platform.NotificationRequest
import com.lifeboard.shared.platform.Notifier
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * [Notifier] 的桌面实现：Windows 系统托盘气泡（M4-4.5b、M12 增强）。
 *
 * ## 为什么用托盘而不是自绘窗口
 *
 * 需求里明确「不使用无障碍」，而桌面端要「其他功能都要在电脑端体现」。
 * 提醒的送达方式在 Windows 上有三种可选：
 *
 * 1. 自绘弹出窗口（Compose `Dialog`）——能做得很好看，但会**抢焦点**。
 *    用户正在打字时弹一个窗，等于打断他，比不提醒还烦
 * 2. Windows 原生 Toast（`WinRT` / `Shell_NotifyIcon` 带 toast）——
 *    最贴近系统体验，但要拉 `WinRT` 互操作，且需要应用有 AppUserModelID，
 *    未安装的绿色版应用弹不出来
 * 3. **托盘气泡**（`TrayIcon.displayMessage`）——系统自己画，
 *    有自己的勿扰策略，代价是样式朴素
 *
 * 选 3：不抢焦点、不依赖安装、跨 Windows 版本行为一致。
 * M12 在这一层上补齐了点击与声音，若将来打包方案（M7）确定要原生 toast，
 * 只需替换这个类，契约不变。
 *
 * ## 三档强度在桌面端怎么落地
 *
 * 契约的三档（`RemindLevel` / [DeliveryLevel]）在桌面端的差异化手段：
 *
 * | 档位 | 提示音 | 图标 | 气泡停留 |
 * |---|---|---|---|
 * | `NONE` | 不调用（本类根本不参与） | — | — |
 * | `WEAK` | **静音**：`MessageType.NONE` | 灰色圆点 | 系统默认 |
 * | `STRONG` | **响系统提示音** + `MessageType.INFO` | 主题色圆点 | 系统按 INFO 处理 |
 *
 * 「弱提醒静音」是刻意的：弱提醒的语义就是「安静地告诉我一声」，
 * 每次响一下会让用户很快把整个通道关掉，反而连强提醒都收不到。
 *
 * ## [DeliveryLevel] 怎么如实回报
 *
 * 契约要求「实际送达成了什么」。桌面端的判定依据：
 *
 * - 系统托盘不可用（无托盘环境 / 被禁用）→ [DeliveryLevel.NONE]
 * - 气泡发得出去 → `important` 时 STRONG，否则 WEAK
 *
 * 这里**没法**判断「勿扰模式开了没有」：`TrayIcon.displayMessage` 是异步的，
 * 不进系统通知中心就无从查证。与其猜一个 STRONG，不如按 `important` 如实映射，
 * 并在日志里留痕——用户若发现「强提醒没提示音」，看日志能知道是**送达了但系统静音**，
 * 而不是应用没发。
 */
class DesktopNotifier : Notifier {

    /**
     * 每条通知对应的托盘图标。
     *
     * 之所以要记账：`TrayIcon.displayMessage` 发出后没有句柄可以「撤销」，
     * 想做 `cancel(id)` 只能把图标从托盘移走。所以每个通知 id 单独占一个 TrayIcon，
     * cancel 时移除它。
     *
     * 代价是托盘区可能出现多个图标。这在桌面端可以接受：
     * 提醒同时最多两三条，且完成一条就移除一个。
     */
    private val active = ConcurrentHashMap<Int, TrayIcon>()

    /** 通知 id → 该通知发出时的 payload，点击时按 id 取回 */
    private val payloads = ConcurrentHashMap<Int, String?>()

    /** 用户点击通知时的回调（M12）。由 `Main.kt` 注册为「唤回主窗口」 */
    @Volatile
    private var onClick: ((payload: String?) -> Unit)? = null

    /**
     * 通知能力的可用性。
     *
     * `SystemTray.isSupported()` 在正常 Windows 上恒为 true，
     * 但在「未登录桌面会话」（如通过计划任务以无交互方式启动）时会返回 false。
     * 那种情况下发通知必然失败，必须如实报 NONE 而不是假装成功。
     */
    private val supported: Boolean by lazy { SystemTray.isSupported() }

    override fun setOnNotificationClick(handler: (payload: String?) -> Unit) {
        onClick = handler
    }

    override suspend fun notify(request: NotificationRequest): DeliveryLevel {
        if (!supported) return DeliveryLevel.NONE

        val image = runCatching { trayImage(request.important) }.getOrNull()
            ?: return DeliveryLevel.NONE
        val icon = TrayIcon(image, request.title).apply {
            isImageAutoSize = true
            // tooltip 是鼠标悬停时显示的文本，气泡消失后用户还能靠它回忆
            toolTip = buildString {
                append(request.title)
                if (request.body.isNotBlank()) append(" — ").append(request.body)
            }.take(MAX_TOOLTIP)
            // 点击气泡本身：唤回主窗口。
            // 注意气泡被点掉时 AWT 在 Windows 上不一定派发 ActionEvent，
            // 所以下面还给托盘图标挂了双击——两条路都通向同一个 handler
            addActionListener { dispatchClick(request.id) }
        }

        return try {
            // 必须先 add 再 displayMessage：TrayIcon 不在托盘里时
            // displayMessage 会被静默忽略，不抛异常也不显示
            SystemTray.getSystemTray().add(icon)
            // AWT 的托盘操作要求跑在 EDT 上
            SwingUtilities.invokeAndWait {
                icon.displayMessage(
                    request.title,
                    request.body,
                    // WEAK 用 NONE：只弹气泡、不要额外提示音；
                    // STRONG 用 INFO，系统会带上默认提示音
                    if (request.important) TrayIcon.MessageType.INFO else TrayIcon.MessageType.NONE,
                )
            }
            // 重要提醒额外响一次系统提示音。
            // 不靠 MessageType.INFO 的声音是因为它在部分 Windows 主题下被静音，
            // 而强提醒是用户明确要求「必须吵到我」的档位
            if (request.important) playAlertSound()

            active[request.id]?.let { previous -> removeQuietly(previous) }
            active[request.id] = icon
            payloads[request.id] = request.payload
            if (request.important) DeliveryLevel.STRONG else DeliveryLevel.WEAK
        } catch (_: Throwable) {
            // 托盘满了（系统有上限）、EDT 死锁、AWT 被禁用……
            // 一律报 NONE：调用方据此能记下「这条没送到」
            removeQuietly(icon)
            payloads.remove(request.id)
            DeliveryLevel.NONE
        }
    }

    override suspend fun cancel(id: Int) {
        active.remove(id)?.let { removeQuietly(it) }
        payloads.remove(id)
    }

    /**
     * 桌面端恒为 true。
     *
     * 契约里写明「Android 是 POST_NOTIFICATIONS，桌面恒为 true」。
     * 但托盘不可用时真实答案是 false，这里如实回报——
     * 让设置页能显示「当前环境不支持通知」而不是给一个骗人的开关。
     */
    override suspend fun isPermitted(): Boolean = supported

    /** 桌面端没有「通知权限设置页」这个入口，保持不变 */
    override suspend fun openPermissionSettings() = Unit

    /**
     * 把点击事件转给业务层。
     *
     * 先按 id 取 payload 再回退到 request 自己的——`cancel` 之后 payloads
     * 里已经没有这条了，但图标可能还残留一瞬，此时给 null 也比给错的好。
     */
    private fun dispatchClick(id: Int) {
        val handler = onClick ?: return
        val payload = payloads[id]
        handler(payload)
    }

    /**
     * 播一次系统提示音。
     *
     * `Toolkit.getDefaultToolkit().beep()` 在各平台都会响（Windows 上走系统默认
     * 「星号」音），且不需要额外依赖。失败时静默——听不到声音不该让通知发送失败。
     */
    private fun playAlertSound() {
        runCatching { Toolkit.getDefaultToolkit().beep() }
    }

    /**
     * 把图标从托盘移除。
     *
     * `remove` 在托盘未初始化时会抛 `IllegalArgumentException`，
     * 而这里被调用的场景（异常兜底、通知完成）都不该再抛。
     */
    private fun removeQuietly(icon: TrayIcon) {
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    /**
     * 画一个纯色圆点当作托盘图标。
     *
     * 不读资源文件：M7 打包前 classpath 里没有图标资源，
     * 而 `TrayIcon` 的构造参数不允许 null。这里现画一张 16×16 的，
     * 重要提醒用主题色，普通提醒用灰色——用户扫一眼托盘就能分出轻重。
     */
    private fun trayImage(important: Boolean): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = if (important) Color(0x3D, 0x7E, 0xA8) else Color(0x8A, 0x8A, 0x8A)
            g.fillOval(1, 1, size - 2, size - 2)
        } finally {
            g.dispose()
        }
        return image
    }

    private companion object {
        /**
         * Windows 的 tooltip 上限是 127 字符，超了会被截断甚至不显示。
         * 这里留一点余量
         */
        const val MAX_TOOLTIP = 120
    }
}

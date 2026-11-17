package com.lifeboard.desktop.tray

import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * 常驻托盘图标（M11）。
 *
 * ## 解决的是什么
 *
 * 早先点窗口的关闭按钮等于 `exitApplication()`：进程一退，`DesktopScheduler` 里
 * 所有待触发的提醒跟着消失。对提醒类应用来说这是最伤的行为——
 * 用户把窗口关掉是「先收起来」，不是「我不要再被提醒了」。
 *
 * 现在关窗口只是隐藏，进程留在托盘里继续跑调度；真正退出要点托盘菜单的「退出」。
 *
 * ## 和 [com.lifeboard.desktop.notify.DesktopNotifier] 的关系
 *
 * 两者各自 `SystemTray.add` 自己的 `TrayIcon` 实例，互不干扰：
 * `remove(icon)` 只移除传入的那个实例，不会误删常驻图标。
 * （代价是托盘折叠区可能出现多个 LifeBoard 图标，这是 `DesktopNotifier` 里
 * 已经记录过的既知取舍。）
 *
 * ## 建不起来怎么办
 *
 * `SystemTray.isSupported()` 为 false、或 `add` 抛 `AWTException`（某些
 * Linux 桌面环境、远程会话、Windows 通知区域被策略禁用）时，[available] 保持 false，
 * **此时调用方必须回退到「关窗即退出」**。
 * 否则窗口一隐藏就再也叫不回来——任务管理器里杀进程才能恢复，
 * 比原来「关掉就没了」更糟。
 */
class DesktopTray(
    private val tooltip: String,
    private val onShow: () -> Unit,
    private val onExit: () -> Unit,
) {

    /** 常驻图标是否成功挂上了。false 表示调用方必须走「关窗即退出」的旧路径 */
    var available: Boolean = false
        private set

    private var icon: TrayIcon? = null

    /**
     * 挂上托盘图标。重复调用是安全的（第二次直接返回）。
     *
     * 必须在 EDT 上调用：AWT 的托盘 API 有线程亲和性，
     * 在别的线程上 `add` 在部分平台上会静默失败（图标不出现，也不报错）。
     */
    fun install() {
        if (icon != null) return
        if (!SystemTray.isSupported()) return

        SwingUtilities.invokeLater {
            runCatching {
                val trayIcon = TrayIcon(
                    trayImage(),
                    tooltip,
                    buildMenu(),
                ).apply {
                    // 双击托盘图标恢复主窗口：Windows 用户对托盘的直觉操作
                    addActionListener { onShow() }
                    isImageAutoSize = true
                }
                SystemTray.getSystemTray().add(trayIcon)
                icon = trayIcon
                available = true
            }.onFailure {
                // 建不起来不是致命错误，但必须让上层知道以便回退。
                // 这里不抛：抛出去会打断 `application { }` 的初始化，
                // 结果是「因为托盘不可用，应用整个打不开」
                available = false
            }
        }
    }

    /** 卸载。只在真正退出时调用 */
    fun uninstall() {
        val current = icon ?: return
        icon = null
        available = false
        SwingUtilities.invokeLater {
            runCatching { SystemTray.getSystemTray().remove(current) }
        }
    }

    private fun buildMenu(): PopupMenu = PopupMenu().apply {
        add(MenuItem("显示 LifeBoard").apply { addActionListener { onShow() } })
        addSeparator()
        add(MenuItem("退出").apply { addActionListener { onExit() } })
    }

    /**
     * 托盘图标。
     *
     * 与 [com.lifeboard.desktop.notify.DesktopNotifier] 的圆点不同，
     * 这里画的是带边框的方块，一眼能分出「常驻的应用图标」与「一条通知」。
     * 尺寸 16×16 是 Windows 通知区域的标准尺寸。
     */
    private fun trayImage(): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x3D, 0x7E, 0xA8)
            g.fillRoundRect(1, 1, size - 2, size - 2, 4, 4)
            g.color = Color.WHITE
            // 中间画一条横线，形似「看板」的一行
            g.fillRect(4, 7, size - 8, 2)
        } finally {
            g.dispose()
        }
        return image
    }
}

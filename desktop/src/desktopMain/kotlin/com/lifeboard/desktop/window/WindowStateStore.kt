package com.lifeboard.desktop.window

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.lifeboard.shared.platform.SettingsStore

/**
 * 窗口尺寸与位置的持久化（M11）。
 *
 * ## 为什么值得做
 *
 * 早先 `rememberWindowState(size = DpSize(1280.dp, 800.dp))` 是写死的初值：
 * 用户拖成 1920×1080、挪到副屏，关掉重开又回到 1280×800 的主屏中央。
 * 这是每次启动都会遇到的摩擦，也是「桌面端当 Android 用」的典型症状——
 * 桌面用户对窗口几何是有预期的，程序不该每次把它忘掉。
 *
 * ## 存什么、不存什么
 *
 * - 存：宽、高、x、y、是否最大化
 * - 不存：最小化状态。以最小化状态恢复等于「双击图标后什么都没出现」，
 *   用户会以为程序没启动
 *
 * ## 恢复到屏幕外怎么办
 *
 * 存下的坐标可能已经不在当前屏幕里了（拔掉副屏、改分辨率、多显示器重排）。
 * 这种情况下把位置退回「屏幕中央」，尺寸保留——保留尺寸是用户的心智预期，
 * 位置则是环境决定的，环境变了就该由程序兜底。
 */
class WindowStateStore(private val settings: SettingsStore) {

    /** 读回上次的窗口状态；没有记录时用 [DEFAULT_SIZE] */
    fun restore(): WindowState {
        val width = settings.getInt(KEY_WIDTH, 0)
        val height = settings.getInt(KEY_HEIGHT, 0)
        val x = settings.getInt(KEY_X, UNKNOWN_POSITION)
        val y = settings.getInt(KEY_Y, UNKNOWN_POSITION)
        val maximized = settings.getBoolean(KEY_MAXIMIZED, false)

        val size = if (width > 0 && height > 0) DpSize(width.dp, height.dp) else DEFAULT_SIZE

        val hasPosition = x != UNKNOWN_POSITION && y != UNKNOWN_POSITION
        // 位置存过、且确实落在某个屏幕内才用；否则交给 Compose 居中
        val position = if (hasPosition && isOnAnyScreen(x, y, width, height)) {
            WindowPosition(x.dp, y.dp)
        } else {
            WindowPosition(Alignment.Center)
        }

        return WindowState(
            placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = position,
            size = size,
        )
    }

    /**
     * 保存。
     *
     * 只在「非最大化」时记录尺寸与位置：最大化状态下 AWT 报的是屏幕尺寸，
     * 存下去之后用户下次取消最大化，窗口就变成全屏那么大，还挪不动。
     * 最大化这个事实单独用一个布尔记住即可。
     */
    fun save(state: WindowState) {
        val maximized = state.placement == WindowPlacement.Maximized
        settings.putBoolean(KEY_MAXIMIZED, maximized)
        if (maximized) return

        val widthDp = state.size.width.value.toInt()
        val heightDp = state.size.height.value.toInt()
        if (widthDp > 0 && heightDp > 0) {
            settings.putInt(KEY_WIDTH, widthDp)
            settings.putInt(KEY_HEIGHT, heightDp)
        }

        val position = state.position
        // `WindowPosition` 可能是「居中」这种非像素表达，此时没有坐标可存，
        // 但也不能把旧坐标留着——用户上次在副屏，这次居中，旧坐标已经是错的
        if (position is WindowPosition.Absolute) {
            settings.putInt(KEY_X, position.x.value.toInt())
            settings.putInt(KEY_Y, position.y.value.toInt())
        } else {
            settings.remove(KEY_X)
            settings.remove(KEY_Y)
        }
    }

    /**
     * 判断一组像素坐标是否落在任一屏幕的可用区域内。
     *
     * 用「窗口左上角 + 一个可见尺寸」去撞屏幕矩形，而不是要求整个窗口都在屏幕内：
     * 窗口比屏幕大时整窗判断永远为 false，会把合理的尺寸误判成「跑到屏幕外」。
     */
    private fun isOnAnyScreen(x: Int, y: Int, width: Int, height: Int): Boolean {
        val visible = MIN_VISIBLE_PIXELS
        val w = if (width > 0) width else visible
        val h = if (height > 0) height else visible
        return runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices
                .any { device ->
                    val bounds = device.defaultConfiguration.bounds
                    // 只要求「左上角往里一点」这一块仍可见，够用户拖回来就行
                    x + visible <= bounds.x + bounds.width &&
                        x + w - visible >= bounds.x &&
                        y + visible <= bounds.y + bounds.height &&
                        y + h - visible >= bounds.y
                }
        }.getOrDefault(false)
    }

    companion object {
        /** 首次启动的默认尺寸。1280×800 在 1080p 上是「不挤也不空」的一档 */
        val DEFAULT_SIZE: DpSize = DpSize(1280.dp, 800.dp)

        /** 坐标未记录时的哨兵值。用极值而不是 0——0 是合法坐标 */
        private const val UNKNOWN_POSITION = Int.MIN_VALUE

        /** 至少要有这么多像素在屏幕内，才算「用户还找得到」 */
        private const val MIN_VISIBLE_PIXELS = 80

        private const val KEY_WIDTH = "window.width"
        private const val KEY_HEIGHT = "window.height"
        private const val KEY_X = "window.x"
        private const val KEY_Y = "window.y"
        private const val KEY_MAXIMIZED = "window.maximized"
    }
}

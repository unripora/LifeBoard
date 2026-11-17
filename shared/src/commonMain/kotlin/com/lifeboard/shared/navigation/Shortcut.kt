package com.lifeboard.shared.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * 与页签相关的快捷操作。
 *
 * 只声明「要做什么」，不声明「按哪个键」：键位是各端输入设备的差异
 * （桌面用 Ctrl+Tab，将来平板可能用手势），而「切到下一个页签」这件事
 * 是共通的。各端把按键翻译成这里的值，`DashboardHost` 负责执行。
 *
 * 用 sealed class 而不是 enum：[JumpTo] 要带「第几个」，enum 的常量
 * 没法带不同参数。
 */
sealed class Shortcut {
    data object NextTab : Shortcut()
    data object PreviousTab : Shortcut()

    /** 切到第 [index] 个可见页签（从 1 开始）。越界时什么都不做 */
    data class JumpTo(val index: Int) : Shortcut()

    /** 刷新当前页。页签壳只负责把请求转给当前页 */
    data object Refresh : Shortcut()
}

/**
 * 桌面端键位映射：把一次按键翻译成 [Shortcut]。
 *
 * 先拆出一层不依赖 Compose 的原始参数版本 [shortcutFor]，再由
 * [shortcutOf] 从 `KeyEvent` 里取字段转发过去。这样拆的原因是：
 * Compose 的 `KeyEvent` 在不同平台上内部持有的是 skiko 的
 * `InternalKeyEvent` 或 AWT 事件，**没法在测试里直接构造**——想让
 * 键位映射有测试，就只能让它不碰 `KeyEvent`。
 *
 * @return 未接管的按键返回 null，调用方据此决定是否继续向下传递
 */
fun shortcutOf(event: KeyEvent): Shortcut? = shortcutFor(
    key = event.key,
    ctrl = event.isCtrlPressed,
    shift = event.isShiftPressed,
    // 只认按下。抬起时再执行一次的话，按一下 Ctrl+Tab 会走两格——
    // Compose 对同一物理按键会先后发 KeyDown 与 KeyUp 两个事件
    isKeyDown = event.type == KeyEventType.KeyDown,
)

/**
 * 键位映射的纯函数内核。
 *
 * 当前接管：
 * - `Ctrl+Tab` / `Ctrl+Shift+Tab`：下一个 / 上一个页签
 * - `Ctrl+1` ~ `Ctrl+9`：直达第 N 个可见页签
 * - `Ctrl+R`：刷新
 *
 * **不接管** `Ctrl+W`（关窗口）和 `Ctrl+Q`（退出）：那是窗口管理器的职责，
 * 应用抢过来会让「以为关了结果没关」成为可能，比多一次手动点击更糟。
 *
 * 也不接管任何不带 Ctrl 的组合：`Tab` 是输入框里的焦点切换、`R` 是打字，
 * 抢过来会让编辑规则时打不了字。
 */
fun shortcutFor(key: Key, ctrl: Boolean, shift: Boolean, isKeyDown: Boolean): Shortcut? {
    if (!isKeyDown || !ctrl) return null

    return when (key) {
        Key.Tab -> if (shift) Shortcut.PreviousTab else Shortcut.NextTab
        Key.R -> Shortcut.Refresh
        Key.One -> Shortcut.JumpTo(1)
        Key.Two -> Shortcut.JumpTo(2)
        Key.Three -> Shortcut.JumpTo(3)
        Key.Four -> Shortcut.JumpTo(4)
        Key.Five -> Shortcut.JumpTo(5)
        Key.Six -> Shortcut.JumpTo(6)
        Key.Seven -> Shortcut.JumpTo(7)
        Key.Eight -> Shortcut.JumpTo(8)
        Key.Nine -> Shortcut.JumpTo(9)
        else -> null
    }
}

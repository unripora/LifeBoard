package com.lifeboard.shared.navigation

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 键位映射的测试。
 *
 * 验的是 [shortcutFor] 而不是 [shortcutOf]：后者要拿一个 Compose `KeyEvent`，
 * 而它在桌面端的内部持有 skiko 的 `InternalKeyEvent`，测试里造不出来
 * （试过用 `java.awt.event.KeyEvent` 包，会 `ClassCastException`）。
 * 所以映射逻辑被拆成了不碰事件的纯函数——这既是为了可测，也顺带让
 * 将来 Android 接物理键盘时能直接复用。
 *
 * 这类函数有个讨厌的特性：键位写错了编译照样通过，按下去没反应也不报错，
 * 只能靠人记得去按一次。所以这里逐个钉死。
 */
class ShortcutTest {

    private fun press(key: Key, shift: Boolean = false): Shortcut? =
        shortcutFor(key = key, ctrl = true, shift = shift, isKeyDown = true)

    @Test
    fun `Ctrl+Tab 是下一个页签`() {
        assertEquals(Shortcut.NextTab, press(Key.Tab))
    }

    @Test
    fun `Ctrl+Shift+Tab 是上一个页签`() {
        assertEquals(Shortcut.PreviousTab, press(Key.Tab, shift = true))
    }

    @Test
    fun `Ctrl+R 是刷新`() {
        assertEquals(Shortcut.Refresh, press(Key.R))
    }

    @Test
    fun `Ctrl+数字 直达对应页签`() {
        // 逐个验 1~9：只测其中一个的话，把 Key.Seven 写成 Key.Six 也发现不了
        val digits = listOf(
            Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
            Key.Six, Key.Seven, Key.Eight, Key.Nine,
        )
        digits.forEachIndexed { index, key ->
            assertEquals(
                Shortcut.JumpTo(index + 1),
                press(key),
                "Ctrl+${index + 1} 应直达第 ${index + 1} 个页签",
            )
        }
    }

    @Test
    fun `没有按 Ctrl 时一律不接管`() {
        // 不按修饰键的话，Tab 是输入框里的焦点切换、R 是打字，
        // 抢过来会让编辑规则时打不了字
        assertNull(shortcutFor(Key.Tab, ctrl = false, shift = false, isKeyDown = true))
        assertNull(shortcutFor(Key.R, ctrl = false, shift = false, isKeyDown = true))
        assertNull(shortcutFor(Key.Three, ctrl = false, shift = false, isKeyDown = true))
    }

    @Test
    fun `按键抬起不触发`() {
        // Compose 对同一次按键会先后发 KeyDown 与 KeyUp。两个都处理的话，
        // 按一下 Ctrl+Tab 会跳两个页签——用户会以为「跳格了」
        assertNull(shortcutFor(Key.Tab, ctrl = true, shift = false, isKeyDown = false))
        assertNull(shortcutFor(Key.R, ctrl = true, shift = false, isKeyDown = false))
    }

    @Test
    fun `只按 Ctrl 本身不触发`() {
        assertNull(press(Key.CtrlLeft))
        assertNull(press(Key.CtrlRight))
    }

    @Test
    fun `未接管的普通键返回 null`() {
        // 交给系统继续处理：输入框要能打字，快捷键不该把它们吞掉
        assertNull(press(Key.A))
        assertNull(press(Key.Zero))
        assertNull(press(Key.F5))
    }

    @Test
    fun `不接管 Ctrl+W 与 Ctrl+Q`() {
        // 这两个是窗口管理器的职责（关窗口 / 退出）。应用抢过来会让
        // 「以为关了结果没关」成为可能，比多一次手动点击更糟
        assertNull(press(Key.W))
        assertNull(press(Key.Q))
    }
}

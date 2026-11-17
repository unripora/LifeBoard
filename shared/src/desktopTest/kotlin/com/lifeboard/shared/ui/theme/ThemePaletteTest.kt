package com.lifeboard.shared.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3-3.1 验收：主题层的配色契约。
 *
 * 主题从 `:app` 搬进 `commonMain` 之后，双端用的是同一份色值。搬迁本身是纯移动，
 * 但正因为「搬完看起来没变」，一旦有人顺手改了某个令牌，只会表现成
 * 「某个页面的字看不清」，不会报错也不会被别的测试发现。这里把几条
 * 设计约束固化成断言。
 *
 * 不渲染界面、只查色值：Compose 的 UI 测试要起 scene 与渲染循环，
 * 为了验色值引入那套依赖不划算。
 */
class ThemePaletteTest {

    private fun Color.luminance(): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
    }

    @Test
    fun 三套色系都有深浅两份配色() {
        ColorPalette.entries.forEach { palette ->
            val colors = palette.colors()
            assertTrue(
                colors.light.background.luminance() > colors.dark.background.luminance(),
                "${palette.label}：浅色背景必须比深色背景亮",
            )
        }
    }

    @Test
    fun 深色下表面比背景亮_浅色下反而更白() {
        // 这条是 GlassCard 的分层前提：卡片靠与背景的明度差浮起来。
        // 深色下卡片亮一阶、浅色下卡片更白，两个方向都不能写反，
        // 写反的表现是「卡片与背景糊成一片」，肉眼很容易漏看
        ColorPalette.entries.forEach { palette ->
            val colors = palette.colors()
            assertTrue(
                colors.light.surface.luminance() >= colors.light.background.luminance(),
                "${palette.label}：浅色下 surface 不该比 background 暗",
            )
            assertTrue(
                colors.dark.surface.luminance() >= colors.dark.background.luminance(),
                "${palette.label}：深色下 surface 必须比 background 亮，否则卡片没有层级",
            )
        }
    }

    @Test
    fun 正文与背景的对比度满足AA() {
        // WCAG 2.1 AA：正文对比度 >= 4.5:1。
        // 深色下用小字号配浅色背景最容易踩线，这里对两份配色都验
        fun contrast(a: Color, b: Color): Float {
            val la = a.luminance()
            val lb = b.luminance()
            val hi = maxOf(la, lb)
            val lo = minOf(la, lb)
            return (hi + 0.05f) / (lo + 0.05f)
        }
        ColorPalette.entries.forEach { palette ->
            val colors = palette.colors()
            assertTrue(
                contrast(colors.light.onBackground, colors.light.background) >= 4.5f,
                "${palette.label} 浅色：正文对比度不足 4.5:1",
            )
            assertTrue(
                contrast(colors.dark.onBackground, colors.dark.background) >= 4.5f,
                "${palette.label} 深色：正文对比度不足 4.5:1",
            )
            assertTrue(
                contrast(colors.light.onSurface, colors.light.surface) >= 4.5f,
                "${palette.label} 浅色：卡片正文对比度不足 4.5:1",
            )
            assertTrue(
                contrast(colors.dark.onSurface, colors.dark.surface) >= 4.5f,
                "${palette.label} 深色：卡片正文对比度不足 4.5:1",
            )
        }
    }

    @Test
    fun 未知名称回落到默认值而不是崩溃() {
        // 偏好文件里存的是枚举名。用户降级、或历史版本存过已删掉的色系时，
        // 这里必须给出默认值——否则一升级就白屏
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("ALREADY_REMOVED"))
        assertEquals(ColorPalette.WATER_LILIES, ColorPalette.fromName(null))
        assertEquals(ColorPalette.WATER_LILIES, ColorPalette.fromName("ALREADY_REMOVED"))
    }

    @Test
    fun 枚举名是偏好存储的契约() {
        // SharedPreferences 里存的是 name，Android 侧升级时若改了枚举名，
        // 老用户的配色偏好会静默回落到默认。这条锁住现有名字
        assertEquals(
            listOf("STARRY_NIGHT", "WATER_LILIES", "SUNRISE"),
            ColorPalette.entries.map { it.name },
        )
        assertEquals(
            listOf("SYSTEM", "LIGHT", "DARK"),
            ThemeMode.entries.map { it.name },
        )
    }
}

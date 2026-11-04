package com.lifeboard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 圆润形状：卡片用 large 24dp，比 Material 默认更贴合层叠的观感 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * 全局主题。
 *
 * @param palette 色系，决定主色/背景/卡片整套配色
 * @param mode 明暗模式。**不直接读系统开关**：系统深色下本应用的半透明层叠
 *   会出现文字对比度不足（系统强制反色与小厂 ROM 差异都会放大这个问题），
 *   所以必须支持应用自己的深色配色
 */
@Composable
fun LifeBoardTheme(
    palette: ColorPalette = ColorPalette.WATER_LILIES,
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = palette.colors()

    MaterialTheme(
        colorScheme = if (dark) colors.dark else colors.light,
        shapes = AppShapes,
        content = content,
    )
}

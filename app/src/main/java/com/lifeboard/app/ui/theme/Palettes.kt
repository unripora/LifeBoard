package com.lifeboard.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 明暗模式偏好。
 *
 * 不直接依赖系统开关：系统深色下本应用的层叠面板会出现对比度不足，
 * 必须提供应用自带的深色配色让用户强制指定。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天"),
    DARK("黑夜"),
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 色系。
 *
 * 三套均取印象派原作色调，再按 Material 3 语义化令牌体系重新映射：
 * 组件只认 `background` / `surface` / `onSurface` 这些**角色**，
 * 明暗切换只是换令牌取值，页面代码一行都不用改。
 *
 * 三条硬约束（来自 MD3 + iOS HIG + WCAG 2.1 AA）：
 * 1. **层级靠明度差**：浅色下背景偏灰、越上浮越白；深色下背景最暗、上浮元素逐级变亮
 * 2. **不纯黑不纯白**：深色底近黑（非 #000）、正文米白（非 #FFF），减轻 OLED 眩光
 * 3. **深色主色降饱和提明度**：浅色下的高饱和主色在暗背景上会刺眼
 */
enum class ColorPalette(val label: String, val description: String) {
    STARRY_NIGHT("星月夜", "梵高 · 深邃夜空，蓝调"),
    WATER_LILIES("睡莲", "莫奈 · 柔雾水影，静谧"),
    SUNRISE("日出印象", "莫奈 · 雾色晨光，冷暖交融"),
    ;

    companion object {
        fun fromName(name: String?): ColorPalette =
            entries.firstOrNull { it.name == name } ?: WATER_LILIES
    }
}

/** 一套色系的深浅两份配色 */
data class PaletteColors(val light: ColorScheme, val dark: ColorScheme)

fun ColorPalette.colors(): PaletteColors = when (this) {
    ColorPalette.STARRY_NIGHT -> PaletteColors(StarryLight, StarryDark)
    ColorPalette.WATER_LILIES -> PaletteColors(LilyLight, LilyDark)
    ColorPalette.SUNRISE -> PaletteColors(SunriseLight, SunriseDark)
}

// ============================================================
// 一、《星月夜》 —— 浅色：蓝调清新 / 深色：深邃夜空
// ============================================================

private val StarryLight = lightColorScheme(
    // 品牌强调色
    primary = Color(0xFF2D5DA1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = Color(0xFF0F2C59),
    secondary = Color(0xFF489FB5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3EAF0),
    onSecondaryContainer = Color(0xFF0F2C59),
    tertiary = Color(0xFF7A4E2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4E3D4),
    onTertiaryContainer = Color(0xFF3A2312),
    // 表面与背景：背景偏灰、卡片纯白，靠明度差分层
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF0F2C59),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F2C59),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF4A6585),
    // 边框与分割
    outline = Color(0xFFE2E8F0),
    outlineVariant = Color(0xFFEDF1F7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val StarryDark = darkColorScheme(
    // 主色降饱和提明度：深底上用湖蓝绿而非钴蓝
    primary = Color(0xFF489FB5),
    onPrimary = Color(0xFF0F1B33),
    primaryContainer = Color(0xFF1F3A52),
    onPrimaryContainer = Color(0xFFC8E6EE),
    secondary = Color(0xFF8FB8C9),
    onSecondary = Color(0xFF0F1B33),
    secondaryContainer = Color(0xFF24384A),
    onSecondaryContainer = Color(0xFFD6E8EF),
    tertiary = Color(0xFFE0B589),
    onTertiary = Color(0xFF2A1C10),
    tertiaryContainer = Color(0xFF4A3524),
    onTertiaryContainer = Color(0xFFF5E2CE),
    // 表面与背景：背景最深、卡片亮一阶
    background = Color(0xFF0F1B33),
    onBackground = Color(0xFFE0E8F5),
    surface = Color(0xFF1A2B4A),
    onSurface = Color(0xFFE0E8F5),
    surfaceVariant = Color(0xFF243550),
    onSurfaceVariant = Color(0xFF94A7C2),
    // 深色下边框是主要的分层手段（阴影辨识度低）
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF243550),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// ============================================================
// 二、《睡莲》 —— 浅色：柔雾水影 / 深色：静谧荷塘
// ============================================================

private val LilyLight = lightColorScheme(
    primary = Color(0xFF70824A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6EBD9),
    onPrimaryContainer = Color(0xFF2E4A22),
    secondary = Color(0xFF9A8B7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE8E0),
    onSecondaryContainer = Color(0xFF3A3128),
    tertiary = Color(0xFF9A6B7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DDE5),
    onTertiaryContainer = Color(0xFF3E2029),
    background = Color(0xFFF0F4F2),
    onBackground = Color(0xFF2E4A22),
    surface = Color(0xFFFAF7F0),
    onSurface = Color(0xFF2E4A22),
    surfaceVariant = Color(0xFFEAEFEA),
    onSurfaceVariant = Color(0xFF6B7A65),
    outline = Color(0xFFDDE5DC),
    outlineVariant = Color(0xFFEAEFEA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val LilyDark = darkColorScheme(
    primary = Color(0xFFB4C8B0),
    onPrimary = Color(0xFF1A201C),
    primaryContainer = Color(0xFF3A4A35),
    onPrimaryContainer = Color(0xFFDCE8D8),
    secondary = Color(0xFFC2B8A8),
    onSecondary = Color(0xFF1A201C),
    secondaryContainer = Color(0xFF3E3A32),
    onSecondaryContainer = Color(0xFFE8E0D4),
    tertiary = Color(0xFFD9CCE8),
    onTertiary = Color(0xFF1A201C),
    tertiaryContainer = Color(0xFF443B4E),
    onTertiaryContainer = Color(0xFFEDE4F5),
    background = Color(0xFF1A201C),
    onBackground = Color(0xFFE8E4DA),
    surface = Color(0xFF242C27),
    onSurface = Color(0xFFE8E4DA),
    surfaceVariant = Color(0xFF2F3833),
    onSurfaceVariant = Color(0xFF9AA396),
    outline = Color(0xFF3C463F),
    outlineVariant = Color(0xFF2F3833),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// ============================================================
// 三、《日出·印象》 —— 浅色：雾色晨光 / 深色：晨昏薄雾
// ============================================================

private val SunriseLight = lightColorScheme(
    primary = Color(0xFFE26D5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE0DB),
    onPrimaryContainer = Color(0xFF3D231E),
    secondary = Color(0xFF5C6D7B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8ED),
    onSecondaryContainer = Color(0xFF232B33),
    tertiary = Color(0xFF82917D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8EDE4),
    onTertiaryContainer = Color(0xFF2C332A),
    background = Color(0xFFF2EEE9),
    onBackground = Color(0xFF3D4A5A),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF3D4A5A),
    surfaceVariant = Color(0xFFEDE7E0),
    onSurfaceVariant = Color(0xFF7A8694),
    outline = Color(0xFFE3DDD5),
    outlineVariant = Color(0xFFEDE7E0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val SunriseDark = darkColorScheme(
    primary = Color(0xFFF0C27B),
    onPrimary = Color(0xFF1E252D),
    primaryContainer = Color(0xFF5E4A2A),
    onPrimaryContainer = Color(0xFFF8E4C4),
    secondary = Color(0xFF9BA8B4),
    onSecondary = Color(0xFF1E252D),
    secondaryContainer = Color(0xFF3A424B),
    onSecondaryContainer = Color(0xFFD8DFE6),
    tertiary = Color(0xFFA8BAA2),
    onTertiary = Color(0xFF1E252D),
    tertiaryContainer = Color(0xFF3E4A3A),
    onTertiaryContainer = Color(0xFFDCE8D6),
    background = Color(0xFF1E252D),
    onBackground = Color(0xFFE0D8CC),
    surface = Color(0xFF2A3139),
    onSurface = Color(0xFFE0D8CC),
    surfaceVariant = Color(0xFF353D46),
    onSurfaceVariant = Color(0xFF949AA2),
    outline = Color(0xFF424B54),
    outlineVariant = Color(0xFF353D46),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

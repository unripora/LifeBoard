package com.lifeboard.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片容器。
 *
 * 严格按 Material 3 + iOS HIG 的分层规则实现：
 *
 * **浅色模式**：背景 `background` 偏灰，卡片 `surface` 纯白，靠「明度差 + 软阴影」浮起来。
 * **深色模式**：背景最暗，卡片 `surface` 亮一阶；阴影在暗背景上几乎不可见，
 *   所以改用 1dp `outline` 边框来划定边界——只靠阴影会让卡片层级糊在一起。
 *
 * 三个刻意的取舍：
 * 1. **不用半透明 surface**：半透明会把 surface 与 background 的明度差稀释掉，
 *    深色下卡片就跟背景糊成一片。分层必须靠实色明度差。
 * 2. **不用白色高光渐变**：那是"玻璃拟态"的装饰手法，与本体系冲突——
 *    高光会让卡片顶部偏亮、底部偏暗，破坏「层级靠均匀明度差」的原则。
 * 3. **前景色显式下发**：`LocalContentColor` 兜住所有未指定颜色的 Text，
 *    避免深色背景上出现默认深色文字（黑底黑字）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isDarkColorScheme()
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (dark) 0.dp else 2.dp,
        // 深色下边框是主要分层手段；浅色下边框很淡，只做边界强化
        border = BorderStroke(
            width = 1.dp,
            color = if (dark) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** 判断当前生效的是不是深色配色（看背景明度，不看系统开关） */
@Composable
fun isDarkColorScheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * 页面底色。
 *
 * 只铺纯 `background`，不加装饰性渐变：
 * 渐变会让同一屏幕内的背景明度不一致，卡片落在不同位置就呈现不同对比度，
 * 与「层级靠明度差」的原则冲突。
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                content()
            }
        }
    }
}

/**
 * 小号状态标签。
 *
 * 用 `primaryContainer` 而不是「主色 + 低透明度」：
 * 容器色是 MD3 里专门为"主色的弱化底"设计的令牌，
 * 手动调 alpha 会在不同背景上得到不一致的对比度。
 */
@Composable
fun GlassPill(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = color,
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/**
 * 进度条。
 *
 * 按规范拆三层语义，全部复用既有令牌，不新增色值：
 * - 轨道：`surfaceVariant`（与输入框背景同色，低存在感）
 * - 进度：`primary`
 * - 圆角：轨道与进度保持一致
 */
@Composable
fun GlassProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

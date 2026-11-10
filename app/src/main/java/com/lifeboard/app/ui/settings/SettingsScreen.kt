package com.lifeboard.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.app.ui.theme.ColorPalette
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.ThemeMode
import com.lifeboard.app.ui.theme.colors
import com.lifeboard.server.collect.CollectionPrefs
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.collect.RefreshInterval
import com.lifeboard.server.notice.CapturePrefs
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService

/**
 * 设置页。
 *
 * 只放「外观」这一类设置：明暗模式与色系。
 * 采集授权、规则等在各自的页面里，不放这里——设置页一旦什么都装，
 * 用户找任何东西都要先翻一遍。
 */
@Composable
fun SettingsScreen(
    appearance: AppearanceState,
    onOpenLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("运行日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "插件没加载、数据没刷新时，先看这里。日志存在本机，可以按级别筛、搜索、导出成文件，" +
                        "不需要连电脑。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionRow(title = "查看运行日志", selected = false, onClick = onOpenLogs)
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("明暗模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "不跟随系统也没问题：应用自带完整的深色配色，" +
                        "不会出现系统强制深色导致字看不清的情况。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThemeMode.entries.forEach { mode ->
                    OptionRow(
                        title = mode.label,
                        selected = appearance.mode == mode,
                        onClick = { appearance.applyMode(mode) },
                    )
                }
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("色系", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "全局生效：主色、背景、卡片与图表一起变化。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ColorPalette.entries.forEach { palette ->
                    PaletteRow(
                        palette = palette,
                        selected = appearance.palette == palette,
                        onClick = { appearance.applyPalette(palette) },
                    )
                }
            }
        }

        item {
            val context = LocalContext.current
            var interval by remember { mutableStateOf(CollectionPrefs.interval(context)) }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("数据源自动刷新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "统一刷新内置数据源、自建源与已安装的插件，没有单独给某个插件配周期的入口。" +
                        "系统会为省电合并后台任务，实际时间可能晚于设定；" +
                        "关闭后仍可在插件详情页手动同步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RefreshInterval.entries.forEach { option ->
                    OptionRow(
                        title = option.label,
                        selected = interval == option,
                        onClick = {
                            interval = option
                            CollectionPrefs.setInterval(context, option)
                            // 立即生效：改完不必等下次启动，也不必重启应用
                            DailyAggregateWorker.schedule(context)
                        },
                    )
                }
            }
        }

        item {
            val context = LocalContext.current
            var pauseOnScreenOff by remember {
                mutableStateOf(CapturePrefs.isPauseOnScreenOff(context))
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("灭屏时暂停采集", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "灭屏后界面不再对用户可见，继续读屏只耗电不产出。" +
                        "如果你在投屏、息屏显示等场景下灭屏后仍需要抓取，可以关闭。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("灭屏时暂停读屏", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = pauseOnScreenOff,
                        onCheckedChange = { checked ->
                            pauseOnScreenOff = checked
                            CapturePrefs.setPauseOnScreenOff(context, checked)
                            ScreenCaptureAccessibilityService.notifyPrefsChanged()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 色系行：左侧四个小色点直接预览该色系的主色，比纯文字直观 */
@Composable
private fun PaletteRow(palette: ColorPalette, selected: Boolean, onClick: () -> Unit) {
    val colors = palette.colors().light
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(colors.primary, colors.secondary, colors.tertiary, colors.primaryContainer)
                .forEach { Swatch(it) }
        }
        Column(Modifier.weight(1f)) {
            Text(palette.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                palette.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape),
    )
}

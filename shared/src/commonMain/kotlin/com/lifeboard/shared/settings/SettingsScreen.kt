package com.lifeboard.shared.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.log.LogScreen
import com.lifeboard.shared.log.LogViewModel
import com.lifeboard.shared.platform.RefreshInterval
import com.lifeboard.shared.platform.platform
import com.lifeboard.shared.ui.theme.ColorPalette
import com.lifeboard.shared.ui.theme.GlassCard
import com.lifeboard.shared.ui.theme.ThemeMode
import com.lifeboard.shared.ui.theme.colors
import kotlinx.coroutines.launch

/**
 * 设置页（M3-3.2d 迁入 commonMain）。
 *
 * 只放「外观」这类设置：明暗模式与色系。采集授权、规则等在各自的页面里，
 * 不放这里——设置页一旦什么都装，用户找任何东西都要先翻一遍。
 *
 * 末尾的「关于」「运行日志」是唯一例外：它们是只读的应用信息与排障入口，
 * 不是设置项，但用户被问「你装的是哪一版」「为什么没数据」时最先会来这里找。
 * 两者点进去是**页内子页**而不是新页签——它们不属于用户会长期停留的地方，
 * 往顶部页签行里塞会让页签越用越长。
 *
 * ## 三块平台能力的显示规则
 *
 * 数据源自动刷新、灭屏暂停采集、应用图标都依赖平台实现。
 * 共享界面**不判断平台**，只看接口自报的能力：
 * - [CollectionBackend.supportsAutoRefresh] 为 false → 整块换成说明文字
 * - [CaptureBackend.available] 为 false → 整块不画
 *
 * 这两处都不做「灰掉」处理：灰掉会让人以为差一步就能开，
 * 然后去找一个根本不存在的开关。
 *
 * @param logViewModel 日志子页的状态；传 null 表示这一端没有日志页
 */
@Composable
fun SettingsScreen(
    appearance: AppearanceState,
    modifier: Modifier = Modifier,
    logViewModel: LogViewModel? = null,
) {
    var subPage by remember { mutableStateOf(SettingsSubPage.ROOT) }

    when (subPage) {
        SettingsSubPage.ROOT -> RootSettings(
            appearance = appearance,
            hasLogs = logViewModel != null,
            modifier = modifier,
            onOpenLogs = { subPage = SettingsSubPage.LOGS },
            onOpenAbout = { subPage = SettingsSubPage.ABOUT },
        )

        SettingsSubPage.LOGS -> SubPageScaffold("运行日志", onBack = { subPage = SettingsSubPage.ROOT }) {
            val vm = logViewModel
            if (vm != null) {
                LogScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    "当前平台没有日志页。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSubPage.ABOUT -> SubPageScaffold("关于", onBack = { subPage = SettingsSubPage.ROOT }) {
            AboutSection(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 设置页的三个状态：根页，以及两个只读子页 */
private enum class SettingsSubPage { ROOT, LOGS, ABOUT }

/** 子页外壳：一行「‹ 返回 + 标题」，下面是内容 */
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("‹ 返回")
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun RootSettings(
    appearance: AppearanceState,
    hasLogs: Boolean,
    modifier: Modifier,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val deps = platform

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "应用图标、版本号与运行环境。反馈问题时先说版本号，能省掉一轮来回确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionRow(title = "查看关于信息", selected = false, onClick = onOpenAbout)
            }
        }

        if (hasLogs) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "运行日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "插件没加载、数据没刷新时，先看这里。日志存在本机，可以按级别筛、搜索、导出成文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OptionRow(title = "查看运行日志", selected = false, onClick = onOpenLogs)
                }
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

        item { RefreshIntervalSection() }

        if (deps.capture.available) {
            item { PauseOnScreenOffSection() }
        }
    }
}

/**
 * 数据源自动刷新周期。
 *
 * 周期本身由 [CollectionBackend] 持有，写下去时平台侧会一并重排调度——
 * 界面不该分两步调「存」和「重排」，漏了第二步不报错但设置不生效。
 */
@Composable
private fun RefreshIntervalSection() {
    val backend = platform.collection
    val scope = rememberCoroutineScope()
    var interval by remember { mutableStateOf(backend.currentInterval()) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("数据源自动刷新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        if (!backend.supportsAutoRefresh) {
            // 桌面端没有系统级后台调度，给一组选了也不生效的按钮比不给更糟
            Text(
                "当前平台不支持后台自动刷新：应用关闭后不会在后台采集。" +
                    "打开应用时会自动补齐最近一次数据，也可以在插件详情页手动同步。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }

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
                    // 先改本地再落库：界面立刻响应，重排失败也不至于卡住点击
                    interval = option
                    scope.launch { backend.setInterval(option) }
                },
            )
        }
    }
}

/** 灭屏暂停读屏。只有 [CaptureBackend.available] 为 true 时才会被画出来 */
@Composable
private fun PauseOnScreenOffSection() {
    val backend = platform.capture
    val scope = rememberCoroutineScope()
    var pause by remember { mutableStateOf(backend.isPauseOnScreenOff()) }

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
                checked = pause,
                onCheckedChange = { checked ->
                    pause = checked
                    scope.launch { backend.setPauseOnScreenOff(checked) }
                },
            )
        }
    }
}

@Composable
internal fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
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
        if (selected) CheckMark()
    }
}

/**
 * 选中标记。
 *
 * 用文字「✓」而不是 `Icons.Filled.Check`：`material-icons-core`
 * 只在 `:app` 有依赖，共享模块没有，加它等于为一个勾号多引一个坐标。
 */
@Composable
internal fun CheckMark() {
    Text(
        "✓",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
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
        if (selected) CheckMark()
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

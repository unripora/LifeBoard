package com.lifeboard.shared.screentime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.repository.model.AppUsageRecord
import com.lifeboard.shared.rules.AppIcon

/**
 * 屏幕时长面板（M1.4，M11 下沉到共享层）。
 *
 * 未获得统计条件时优先展示说明卡片，把操作按钮放在最显眼的位置；
 * 可用后才展示数据卡片，避免用户在无数据时误以为是程序坏了。
 *
 * @param onOpenPermission 点「去开启」时跳到系统的授权入口。
 *   传 null 表示当前平台没有这个入口（桌面端没有「使用情况访问」权限模型），
 *   此时只画「重新检测」——给一个点了没反应的按钮比不给更糟
 */
@Composable
fun ScreenTimeScreen(
    backend: ScreenTimeBackend,
    modifier: Modifier = Modifier,
    onOpenPermission: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val vm = remember(backend, coroutineScope) { ScreenTimeViewModel(backend, coroutineScope) }
    val state by vm.state.collectAsState()

    // 首次进入读一次。放在 remember 之外用 LaunchedEffect 更规范，
    // 但这里 VM 构造后 state 初值是 loading=true，refresh 一进来就得跑，
    // 否则界面永远停在「读取中」
    remember(vm) {
        vm.refresh()
        true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTimeContent(
            state = state,
            onOpenPermission = onOpenPermission,
            onRecheck = vm::refresh,
            onCollect = vm::collectNow,
        )
    }
}

/**
 * 屏幕时长内容区（不含滚动与外层 padding）。
 *
 * 拆出来是为了能被嵌进其它可滚动页面（如趋势页），避免嵌套滚动导致手势打架。
 */
@Composable
fun ScreenTimeContent(
    state: ScreenTimeUiState,
    onOpenPermission: (() -> Unit)?,
    onRecheck: () -> Unit,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasPermission) {
            PermissionCard(onOpenSettings = onOpenPermission, onRecheck = onRecheck)
        }

        TodayCard(
            minutes = state.todayMinutes,
            loading = state.loading,
            onCollect = onCollect,
        )

        AppUsageCard(rows = state.appUsage, totalMinutes = state.todayMinutes)

        WeekCard(minutes = state.weekMinutes, daily = state.weekDaily, loading = state.loading)

        if (state.lastMessage.isNotBlank()) {
            Text(
                state.lastMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(onOpenSettings: (() -> Unit)?, onRecheck: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "屏幕使用时长当前不可用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "时长来自系统记录的窗口使用情况，需系统侧允许统计。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 没有系统授权页时只给「重新检测」：
                // 渲染一个点了什么也不发生的「去开启」比没有更让人困惑
                if (onOpenSettings != null) {
                    Button(onClick = onOpenSettings) { Text("去开启") }
                }
                OutlinedButton(onClick = onRecheck) { Text("重新检测") }
            }
        }
    }
}

@Composable
private fun TodayCard(minutes: Double, loading: Boolean, onCollect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日屏幕使用", style = MaterialTheme.typography.titleSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    ScreenTimeViewModel.formatMinutes(minutes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }
            Text(
                ScreenTimeViewModel.todayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCollect, enabled = !loading) { Text("立即采集") }
        }
    }
}

/**
 * 各应用使用明细卡片。
 *
 * 按当天时长降序，每行带应用图标、名称、时长和一条占比条——
 * 占比以当天总时长为分母，用于看出哪些应用吃掉了大部分时间。
 */
@Composable
private fun AppUsageCard(rows: List<AppUsageRecord>, totalMinutes: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("各应用使用", style = MaterialTheme.typography.titleSmall)
                if (rows.isNotEmpty()) {
                    Text(
                        "${rows.size} 个应用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (rows.isEmpty()) {
                Text(
                    "还没有分应用数据。点上面的「立即采集」读一次使用记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // 分母用当天总时长；总时长为 0 时退回各行之和，避免除零且条长仍可比较
            val denominator = if (totalMinutes > 0.0) totalMinutes else rows.sumOf { it.minutes }
            rows.forEach { row ->
                AppUsageRow(row = row, denominator = denominator)
            }
        }
    }
}

@Composable
private fun AppUsageRow(row: AppUsageRecord, denominator: Double) {
    val ratio = if (denominator > 0.0) {
        (row.minutes / denominator).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(row.packageName, size = 32)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    row.label.ifBlank { row.packageName },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    ScreenTimeViewModel.formatMinutes(row.minutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 占比条：直观看出谁占大头，比纯数字快
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun WeekCard(
    minutes: Double,
    daily: List<ScreenTimeBackend.DailyPoint>,
    loading: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本周屏幕使用", style = MaterialTheme.typography.titleSmall)
            Text(
                ScreenTimeViewModel.formatMinutes(minutes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (daily.isEmpty()) {
                Text(
                    "本周暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 以本周最大值做满刻度，柱高按比例缩放，保证小数据也能看出差异
                val max = daily.maxOf { it.minutes }.coerceAtLeast(1.0)
                val today = Dates.todayString()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    daily.forEach { point ->
                        val value = point.minutes
                        // 日期串是 yyyy-MM-dd，末两位就是「日」。
                        // 不用 LocalDate.parse 是为了让这段代码留在 commonMain
                        val label = point.date.takeLast(2).trimStart('0')
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            val ratio = (value / max).toFloat().coerceIn(0.02f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((100 * ratio).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (point.date == today) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                    ),
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (loading) {
                Text(
                    "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

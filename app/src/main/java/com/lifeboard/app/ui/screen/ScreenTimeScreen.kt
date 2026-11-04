package com.lifeboard.app.ui.screen

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.server.collect.SettingsNavigator
import java.time.LocalDate

/**
 * 屏幕时长面板（M1.4）。
 *
 * 未授权时优先展示授权卡片，把「去开启」按钮放在最显眼的位置；
 * 授权后才展示数据卡片，避免用户在无数据时误以为是程序坏了。
 */
@Composable
fun ScreenTimeScreen(vm: ScreenTimeViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTimeContent(vm)
    }
}

/**
 * 屏幕时长内容区（不含滚动与外层 padding）。
 *
 * 拆出来是为了能被嵌进其它可滚动页面（如趋势页），避免嵌套滚动导致手势打架。
 */
@Composable
fun ScreenTimeContent(vm: ScreenTimeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (!state.hasPermission) {
        PermissionCard(
            onOpenSettings = { SettingsNavigator.openUsageAccess(context) },
            onRecheck = vm::refresh,
        )
    }

    TodayCard(
        minutes = state.todayMinutes,
        loading = state.loading,
        onCollect = vm::collectNow,
    )

    WeekCard(minutes = state.weekMinutes, daily = state.weekDaily, loading = state.loading)

    if (state.lastMessage.isNotBlank()) {
        Text(
            state.lastMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit, onRecheck: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "需要「使用情况访问权」",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "屏幕使用时长来自系统使用记录，需手动授权一次。授权后无需重复操作。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings) { Text("去开启") }
                OutlinedButton(onClick = onRecheck) { Text("已开启，重新检测") }
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

@Composable
private fun WeekCard(minutes: Double, daily: List<Pair<LocalDate, Double>>, loading: Boolean) {
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
                val max = daily.maxOf { it.second }.coerceAtLeast(1.0)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    daily.forEach { (date, value) ->
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
                                        if (date == LocalDate.now()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                    ),
                            )
                            Text(
                                WEEK_LABELS[date.dayOfWeek.value - 1],
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

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

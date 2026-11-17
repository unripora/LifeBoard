package com.lifeboard.shared.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeboard.shared.repository.model.AppLogRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * 运行日志页（M3-3.2d 迁入 commonMain）。
 *
 * 按级别筛选 + 关键字搜索 + 分页 + 导出 + 清空，两端共用一套界面。
 * 布局刻意接近纯文本：日志是用来扫的，加卡片和圆角只会让每一行占更多高度、
 * 一屏看到更少。
 *
 * 滚到底自动加载下一页——日志页没有「页码」的概念，
 * 用户想看的永远是「再往前」。
 */
@Composable
fun LogScreen(
    viewModel: LogViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exporterAvailable = com.lifeboard.shared.platform.platform.textExporter.available
    val listState = rememberLazyListState()

    // 滚到倒数第 5 条就预取下一页：等完全触底再加载，用户会看到一段空白
    LaunchedEffect(listState, state.records.size, state.hasMore) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (state.hasMore && last >= state.records.size - 5) viewModel.loadMore()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("运行日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "共 ${state.total} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (exporterAvailable) {
                    TextButton(onClick = viewModel::export, enabled = !state.exporting) {
                        Text(if (state.exporting) "导出中…" else "导出")
                    }
                }
                // 清空是不可逆的，但这里不做二次确认弹窗：
                // 它清的是排障痕迹而不是用户数据，多一层确认反而累赘
                TextButton(onClick = viewModel::clearAll) { Text("清空") }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text("搜索消息或来源") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogLevelFilter.entries.forEach { f ->
                FilterChip(selected = state.level == f, label = f.label) { viewModel.setLevel(f) }
            }
        }

        val message = state.message
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2000)
                viewModel.dismissMessage()
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.query.isBlank() && state.level == LogLevelFilter.ALL) {
                        "还没有日志。应用运行时的记录会出现在这里。"
                    } else {
                        "没有符合条件的日志。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(state.records, key = { it.id }) { record -> LogRow(record) }
                if (state.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条日志。
 *
 * 级别用颜色区分而不是图标：日志一行要显示时间、级别、来源、消息四段，
 * 图标会再挤掉十几个字符的宽度，而消息往往正好差这十几个字符。
 */
@Composable
private fun LogRow(record: AppLogRecord) {
    val levelColor = when (record.level.uppercase()) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> MaterialTheme.colorScheme.tertiary
        "DEBUG" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            remember(record.time) {
                LOG_TIME_FORMAT.format(Instant.ofEpochMilli(record.time).atZone(ZoneId.systemDefault()))
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            record.level.take(1),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
        )
        Column(Modifier.weight(1f)) {
            Text(
                record.message,
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
            )
            val meta = buildString {
                append(record.tag)
                if (record.location.isNotBlank()) append("  ·  ").append(record.location)
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** 轻量筛选片。不用 Material 的 FilterChip：它在两端的高度不一致，且默认内边距偏大 */
@Composable
private fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

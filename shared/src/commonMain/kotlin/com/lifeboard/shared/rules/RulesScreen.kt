package com.lifeboard.shared.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.repository.model.CaptureRuleRecord
import com.lifeboard.shared.ui.theme.GlassCard
import com.lifeboard.shared.ui.theme.GlassPill

/**
 * 规则列表页（M4-4.6，两端共用）。
 *
 * ## 界面为什么按应用分组
 *
 * 一条规则天然是「某个应用 + 某个内容条件」。平铺成一长串时，
 * 同一应用下的多条规则会散落各处，用户看不出「这个应用我到底配了几条」。
 * 分组后每组末尾放「为此应用新增规则」，也顺带把最常见的操作路径缩短了一步。
 *
 * ## 通道未开启时为什么置顶提示
 *
 * 规则配得再对，采集通道没开也一条数据都不会有——那种「什么都没发生」的失败
 * 最难自查。所以把授权状态摆在最上面，且不和规则列表混在一起。
 *
 * @param onEdit 打开编辑页（新增时给 [buildNewRuleState] 的结果）
 * @param onRefreshPermissions 请求重新检查授权（从系统设置回来后要能立刻看到结果）
 */
@Composable
fun RulesScreen(
    state: RulesUiState,
    onEdit: (CaptureRuleRecord) -> Unit,
    onToggle: (CaptureRuleRecord) -> Unit,
    onDelete: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onClearToday: () -> Unit,
    onRefreshPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<CaptureRuleRecord?>(null) }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条规则？") },
            text = { Text("删除后「${rule.packageName}」不再计数，已记录的历史数据不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(rule.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("规则", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "共 ${state.rules.size} 条，其中 ${state.enabledCount} 条已启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!state.channelReady) {
            item {
                ChannelHint(onRefreshPermissions = onRefreshPermissions)
            }
        }

        if (state.message.isNotBlank()) {
            item {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onCreate("") }) { Text("+ 新增规则") }
                OutlinedButton(onClick = onClearToday) { Text("清空今日计数") }
            }
        }

        if (state.rules.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("还没有任何规则", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "规则用来把「某个应用里出现的内容」变成计数。先新增一条试试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(state.groups, key = { it.first }) { (packageName, rules) ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppIcon(key = packageName, size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(
                            packageName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "今日合计 " + formatRuleCount(
                                rules.sumOf { state.counts[it.sourceName] ?: 0.0 },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GlassPill("${rules.size} 条")
                }

                rules.forEach { rule ->
                    RuleRow(
                        rule = rule,
                        count = state.counts[rule.sourceName],
                        onEdit = { onEdit(rule) },
                        onToggle = { onToggle(rule) },
                        onDelete = { pendingDelete = rule },
                    )
                }

                OutlinedButton(
                    onClick = { onCreate(packageName) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ 为此应用新增规则") }
            }
        }

        item { Column(Modifier.padding(bottom = 16.dp)) {} }
    }
}

/** 单条规则：数据源、关键词摘要、今日计数、启用开关 */
@Composable
private fun RuleRow(
    rule: CaptureRuleRecord,
    count: Double?,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.sourceName.ifBlank { "（数据源缺失）" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                summarizeConditions(rule),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "今日 ${count?.let { formatRuleCount(it) } ?: "—"} · " +
                    rule.schedule.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text("编辑") }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

/**
 * 采集通道未就绪时的提示。
 *
 * 刻意不提供「去授权」的跳转按钮：Android 跳系统设置、桌面端根本没有对应设置页，
 * 两端各写一套跳转逻辑收益不大。这里只说清「为什么没数据」，
 * 具体去哪里开由用户在各自的「权限」页处理。
 */
@Composable
private fun ChannelHint(onRefreshPermissions: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "采集通道未开启",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "规则需要采集通道才能拿到内容。通道没开时，下面所有规则都不会产生数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefreshPermissions) { Text("重新检查") }
    }
}

/** 条件摘要：把关键词、排除词、时间正则压成一行 */
private fun summarizeConditions(rule: CaptureRuleRecord): String {
    val parts = mutableListOf<String>()
    val include = splitRuleKeywords(rule.includeKeywords)
    if (include.isNotEmpty()) parts += "含 " + include.joinToString("、")
    val exclude = splitRuleKeywords(rule.excludeKeywords)
    if (exclude.isNotEmpty()) parts += "排除 " + exclude.joinToString("、")
    if (rule.timeRegex.isNotBlank()) parts += "时间格式 ${rule.timeRegex}"
    return if (parts.isEmpty()) "该应用的任何内容都计" else parts.joinToString(" · ")
}

/** 新增规则的初始草稿；[packageName] 非空时预填应用 */
fun buildNewRuleState(packageName: String = ""): RuleEditUiState =
    RuleEditUiState(packageName = packageName)

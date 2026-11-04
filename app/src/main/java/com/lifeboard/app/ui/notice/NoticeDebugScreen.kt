package com.lifeboard.app.ui.notice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.notice.CaptureRuleMatcher
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService

/**
 * 采集规则与流水的调试页。
 *
 * 设计意图：规则由用户配置，不在代码里写死任何应用的文案。
 * 页面结构分三层：
 * 1. 授权状态（通知使用权 / 无障碍，两条采集通道）
 * 2. 规则列表 → 点进去编辑单个应用的匹配规则
 * 3. 原始流水 + 匹配测试（规则写错时靠它定位）
 */
@Composable
fun NoticeDebugScreen(vm: NoticeDebugViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // null = 列表页；否则为正在编辑的规则（id=0 表示新建）
    var editing by remember { mutableStateOf<CaptureRuleEntity?>(null) }

    val current = editing
    if (current != null) {
        RuleEditScreen(
            rule = current,
            onSave = {
                vm.saveRule(it)
                editing = null
            },
            onCancel = { editing = null },
            onDelete = {
                vm.deleteRule(it)
                editing = null
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionCard(
            listenerEnabled = state.listenerEnabled,
            accessibilityEnabled = state.accessibilityEnabled,
            onOpenNotification = { SettingsNavigator.openNotificationListenerSettings(context) },
            onOpenAccessibility = { ScreenCaptureAccessibilityService.openSettings(context) },
            onRefresh = vm::refresh,
        )

        RulesCard(
            rules = state.rules,
            counts = state.counts,
            onAdd = { editing = CaptureRuleEntity(packageName = "", sourceName = "") },
            onEdit = { editing = it },
            onToggle = vm::toggleRule,
            onClearToday = vm::clearToday,
        )

        RawCard(raws = state.raws, onClear = vm::clearRaws)

        MatchCard(
            rules = state.rules,
            samplePackage = state.samplePackage,
            sampleContent = state.sampleContent,
            matchResult = state.matchResult,
            onSetPackage = vm::setSamplePackage,
            onSetContent = vm::setSampleContent,
            onTest = vm::testMatch,
        )

        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    listenerEnabled: Boolean,
    accessibilityEnabled: Boolean,
    onOpenNotification: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (listenerEnabled && accessibilityEnabled) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("采集通道", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            PermissionRow(
                title = "通知使用权",
                enabled = listenerEnabled,
                hint = "抓应用推送到通知栏的内容",
                onClick = onOpenNotification,
            )
            PermissionRow(
                title = "无障碍（读屏）",
                enabled = accessibilityEnabled,
                hint = "抓只在应用界面内显示的提示，如打卡成功",
                onClick = onOpenAccessibility,
            )

            OutlinedButton(onClick = onRefresh) { Text("重新检测") }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    enabled: Boolean,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$title：${if (enabled) "已授权" else "未授权"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!enabled) {
            TextButton(onClick = onClick) { Text("去授权") }
        }
    }
}

@Composable
private fun RulesCard(
    rules: List<CaptureRuleEntity>,
    counts: Map<String, Double>,
    onAdd: () -> Unit,
    onEdit: (CaptureRuleEntity) -> Unit,
    onToggle: (CaptureRuleEntity) -> Unit,
    onClearToday: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("识别规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onAdd) { Text("+ 新增") }
            }

            if (rules.isEmpty()) {
                Text(
                    "还没有规则。新增一条，选择要监听的应用，再填匹配关键词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(rule) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.packageName.ifBlank { "（未选应用）" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val includes = CaptureRuleMatcher.splitKeywords(rule.includeKeywords)
                            Text(
                                buildString {
                                    append(rule.sourceName.ifBlank { "未指定数据源" })
                                    if (includes.isNotEmpty()) {
                                        append(" · 含：${includes.joinToString("/")}")
                                    }
                                    if (rule.dailyOnce) append(" · 每日一次")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            counts[rule.sourceName]?.let { v ->
                                Text(
                                    "今日：${formatCount(v)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = onClearToday) { Text("清空今日计数") }
            }
        }
    }
}

@Composable
private fun RawCard(raws: List<RawItem>, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原始流水", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "两条通道抓到的内容都会照实记在这里（含未命中的）。" +
                    "规则调不准时，把这里的原文复制到下方「匹配测试」逐条试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (raws.isEmpty()) {
                Text("暂无记录", style = MaterialTheme.typography.bodySmall)
            } else {
                raws.take(30).forEach { raw ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                raw.packageName.substringAfterLast('.'),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                formatTime(raw.postedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (raw.title.isNotBlank()) {
                            Text(raw.title, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (raw.text.isNotBlank()) {
                            Text(
                                raw.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            if (raw.matchedSources.isBlank()) "未命中" else "已命中：${raw.matchedSources}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (raw.matchedSources.isBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = onClear) { Text("清空流水") }
        }
    }
}

@Composable
private fun MatchCard(
    rules: List<CaptureRuleEntity>,
    samplePackage: String,
    sampleContent: String,
    matchResult: MatchResult?,
    onSetPackage: (String) -> Unit,
    onSetContent: (String) -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("匹配测试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "把流水里的原文粘贴到下面，验证规则会不会命中。不写库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val packages = rules.map { it.packageName }.filter { it.isNotBlank() }.distinct()
            if (packages.isEmpty()) {
                Text("先新增一条规则再测试", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            packages.forEach { pkg ->
                OutlinedButton(
                    onClick = { onSetPackage(pkg) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        pkg + if (pkg == samplePackage) " ✓" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            OutlinedTextField(
                value = sampleContent,
                onValueChange = onSetContent,
                label = { Text("要测试的文本") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onTest() }) { Text("测试匹配") }
            matchResult?.let { r ->
                Text(
                    if (r.matchedSources.isEmpty()) {
                        "未命中"
                    } else {
                        "命中：${r.matchedSources.joinToString("、")}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.matchedSources.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

private fun formatCount(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

private fun formatTime(epochMillis: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMillis))
}

package com.lifeboard.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassPill
import com.lifeboard.core.model.SourceRef
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「规则」页签。
 *
 * 与「通知源」页的分工：
 * - 本页是**正式功能**：看有哪些规则、是否生效、今天计了多少、增删改
 * - 通知源页是**调试工具**：授权状态、原始流水、匹配测试
 *
 * 把规则从调试页抽出来的直接原因：规则是这个应用的核心配置，
 * 藏在「自检」类的页面里，用户根本找不到。
 */
@Composable
fun RulesScreen(vm: RulesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // null = 列表；非 null = 正在编辑
    var editing by remember { mutableStateOf<RuleEditUiState?>(null) }
    // 编辑已有规则时需要异步解析它绑定的数据源
    var pendingRule by remember { mutableStateOf<CaptureRuleEntity?>(null) }

    LaunchedEffect(pendingRule, state.sources) {
        val rule = pendingRule ?: return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) { SourceRegistry.resolve(context, rule.sourceName) }
        editing = buildEditState(rule, resolved)
        pendingRule = null
    }

    val current = editing
    if (current != null) {
        RuleEditScreen(
            initial = current,
            sources = state.sources,
            onSave = {
                vm.saveRule(it.toEntity())
                editing = null
            },
            onCancel = { editing = null },
            onDelete = {
                vm.deleteRule(it)
                editing = null
            },
            onCreateSource = vm::createCustomSource,
        )
        return
    }

    // 按应用分组：同一个应用的多条规则合并成一张卡，
    // 平铺时用户既看不出「这个应用配了几条」，也很难对比规则之间是否重复
    val groups = remember(state.rules) {
        state.rules.groupBy { it.packageName }.toList()
            .sortedBy { (pkg, _) -> pkg }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ChannelCard(state.listenerEnabled, state.accessibilityEnabled, vm::refresh) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("识别规则", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "共 ${state.rules.size} 条，启用 ${state.rules.count { it.enabled }} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    editing = RuleEditUiState(source = state.sources.firstOrNull())
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("新增规则")
                }
            }
        }

        if (state.rules.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "还没有规则。新增一条：选应用 → 选数据源 → 填关键词 → 设时段。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(groups, key = { it.first }) { (pkg, rules) ->
                AppRuleGroup(
                    packageName = pkg,
                    rules = rules,
                    sources = state.sources,
                    counts = state.counts,
                    onEdit = { pendingRule = it },
                    onToggle = { vm.toggleRule(it) },
                    onAdd = {
                        editing = RuleEditUiState(
                            packageName = pkg,
                            source = state.sources.firstOrNull(),
                        )
                    },
                )
            }
            item {
                TextButton(onClick = vm::clearToday) { Text("清空今日计数") }
            }
        }

        if (state.sources.any { it.isCustom }) {
            item { CustomSourceCard(state.sources.filterIsInstance<SourceRef.Custom>(), vm) }
        }

        if (state.message.isNotBlank()) {
            item {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 采集通道状态：规则写了但通道没开，数据永远是 0，必须显式提示并给出直达入口 */
@Composable
private fun ChannelCard(
    listenerEnabled: Boolean,
    accessibilityEnabled: Boolean,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val ready = listenerEnabled || accessibilityEnabled
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("采集通道", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            GlassPill(
                if (ready) "已就绪" else "未授权",
                if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        ChannelRow(
            title = "通知使用权",
            hint = "抓应用推送到通知栏的内容",
            enabled = listenerEnabled,
            onOpen = { SettingsNavigator.openNotificationListenerSettings(context) },
        )
        ChannelRow(
            title = "无障碍读屏",
            hint = "抓只在应用界面内显示的提示，如打卡成功",
            enabled = accessibilityEnabled,
            onOpen = { ScreenCaptureAccessibilityService.openSettings(context) },
        )
        Text(
            if (ready) {
                "规则命中的事件会自动计数。要排查为什么没命中，去「通知源」页看原始流水。"
            } else {
                "两个通道都没开，规则不会产生任何数据。至少开启其中一个。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "无障碍只订阅规则里配置的应用，未配规则的应用不会唤醒本服务。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefresh) { Text("重新检测") }
    }
}

/** 一行通道：未授权时直接给「去开启」按钮，不用用户自己去翻系统设置 */
@Composable
private fun ChannelRow(
    title: String,
    hint: String,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            TextButton(onClick = onOpen) { Text("去开启") }
        }
    }
}

/**
 * 一个应用的规则分组。
 *
 * 折叠状态默认收起：应用多了以后全部展开会把页面拉得很长。
 * 分组头始终显示「几条规则 / 今日是否有命中」，展开后才看得到每条规则的时间等细节。
 */
@Composable
private fun AppRuleGroup(
    packageName: String,
    rules: List<CaptureRuleEntity>,
    sources: List<SourceRef>,
    counts: Map<String, Double>,
    onEdit: (CaptureRuleEntity) -> Unit,
    onToggle: (CaptureRuleEntity) -> Unit,
    onAdd: () -> Unit,
) {
    var expanded by rememberSaveable(packageName) { mutableStateOf(false) }
    val enabledCount = rules.count { it.enabled }
    // 该应用下所有规则命中过的源，用于摘要显示"今天有没有数据"
    val todayTotal = rules.mapNotNull { counts[it.sourceName] }.sum()

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (packageName.isNotBlank()) {
                AppIcon(packageName, size = 40)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (packageName.isBlank()) {
                        "（未选应用）"
                    } else {
                        InstalledApps.label(LocalContext.current, packageName)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    packageName.ifBlank { "未指定包名" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${rules.size} 条规则",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (todayTotal > 0) {
                    Text(
                        "今日 ${formatCount(todayTotal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (enabledCount == 0) {
            Text(
                "全部规则已停用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // 折叠时不渲染子项，长列表滚动更省
        if (expanded) {
            HorizontalDivider()
            rules.forEach { rule ->
                val source = sources.firstOrNull { it.key == rule.sourceName }
                RuleRow(
                    rule = rule,
                    sourceName = source?.displayName ?: rule.sourceName,
                    todayCount = counts[rule.sourceName],
                    onEdit = { onEdit(rule) },
                    onToggle = { onToggle(rule) },
                )
            }
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("为该应用新增规则")
            }
        }
    }
}

/** 分组内的一条规则：数据源、时段摘要、今日计数、开关 */
@Composable
private fun RuleRow(
    rule: CaptureRuleEntity,
    sourceName: String,
    todayCount: Double?,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill(sourceName.ifBlank { "未指定数据源" })
                todayCount?.let {
                    GlassPill("今日 ${formatCount(it)}", MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(
                rule.toRuleSchedule().summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val includes = splitRuleKeywords(rule.includeKeywords)
            if (includes.isNotEmpty()) {
                Text(
                    "含：${includes.joinToString(" / ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
    }
}

/** 自建源管理 */
@Composable
private fun CustomSourceCard(sources: List<SourceRef.Custom>, vm: RulesViewModel) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("自建数据源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "自建源没有自动采集，只由规则命中计数。删除前需先改掉引用它的规则。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sources.forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        source.key + " · " + source.bucket.unit.ifBlank { "布尔" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.deleteCustomSource(source) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

private fun formatCount(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

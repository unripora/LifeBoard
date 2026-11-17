package com.lifeboard.shared.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.platform.AppCatalog.AppEntry
import com.lifeboard.shared.ui.theme.GlassCard

/**
 * 规则编辑页（M4-4.6，Android 与桌面端共用）。
 *
 * 结构按用户的配置顺序排：选应用 → 选数据源 → 填匹配条件 → 配生效时间 → 计数方式。
 * 前两步没完成时后面的配置没有意义，所以在应用未选时把后半段折叠掉，
 * 避免用户以为「规则已经生效了」。
 *
 * 与原 Android 版的差别只有两处，都是为了两端共用：
 * 1. 应用清单不再由 `InstalledApps.query(context)` 直接查，改由 [AppCatalog] 注入——
 *    桌面端查的是已安装程序而不是 App 包
 * 2. 不再持有 `LocalContext`，所以没有任何 Android 依赖
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditScreen(
    initial: RuleEditUiState,
    sources: List<SourceRef>,
    apps: List<AppEntry>,
    onSave: (RuleEditUiState) -> Unit,
    onCancel: () -> Unit,
    onDelete: (Long) -> Unit,
    onCreateSource: (String, MetricBucket) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showNewSource by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var manualKey by remember { mutableStateOf("") }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            selectedKey = draft.packageName,
            onPick = { entry ->
                draft = draft.copy(packageName = entry.key, appLabel = entry.label, error = "")
                showAppPicker = false
            },
            onManual = { key ->
                draft = draft.copy(packageName = key, appLabel = "", error = "")
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
        )
    }

    if (showNewSource) {
        NewSourceDialog(
            onCreate = { name, bucket ->
                onCreateSource(name, bucket)
                showNewSource = false
            },
            onDismiss = { showNewSource = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这条规则？") },
            text = { Text("删除后该应用不再计数，已记录的历史数据不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(draft.id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (draft.id == 0L) "新增规则" else "编辑规则",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        // ---- 1. 选择应用 ----
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("1. 选择应用")
            SelectedAppSummary(key = draft.packageName, label = draft.appLabel)
            OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (draft.packageName.isBlank()) "从已安装应用中选择" else "重新选择")
            }
            Text(
                "找不到时可直接填写标识符：手机填包名，电脑填程序路径。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = manualKey,
                onValueChange = { manualKey = it },
                label = { Text("手动填写标识符") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    draft = draft.copy(packageName = manualKey.trim(), appLabel = "", error = "")
                },
                enabled = manualKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("使用这个标识符") }
        }

        // ---- 2. 落到哪个数据源 ----
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("2. 命中后记到哪个数据源")
            Text(
                "数据源决定面板把这条规则的数据归到哪里。没有合适的就新建一个。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { source ->
                    FilterChip(
                        selected = draft.source?.key == source.key,
                        onClick = {
                            draft = draft.copy(
                                source = source,
                                bucket = source.bucket,
                                error = "",
                            )
                        },
                        label = {
                            Text(
                                source.displayName + if (source.isCustom) " · 自建" else "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showNewSource = true },
                    label = { Text("+ 新建数据源", style = MaterialTheme.typography.labelMedium) },
                )
            }

            Text("计数方式", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MetricBucket.FLAG, MetricBucket.COUNT).forEach { bucket ->
                    FilterChip(
                        selected = draft.bucket == bucket,
                        onClick = { draft = draft.copy(bucket = bucket) },
                        label = {
                            Text(
                                when (bucket) {
                                    MetricBucket.FLAG -> "每天只算一次"
                                    else -> "命中几次算几次"
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        }

        // ---- 3. 匹配条件 ----
        if (draft.packageName.isNotBlank()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("3. 匹配条件")
                OutlinedTextField(
                    value = draft.includeKeywords,
                    onValueChange = { draft = draft.copy(includeKeywords = it) },
                    label = { Text("必须包含的词") },
                    supportingText = { Text("逗号分隔，全部出现才算命中。留空表示该应用的任何内容都算。") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.excludeKeywords,
                    onValueChange = { draft = draft.copy(excludeKeywords = it) },
                    label = { Text("排除词") },
                    supportingText = { Text("出现任意一个就丢弃，如「失败,异常,补卡」。") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.timeRegex,
                    onValueChange = { draft = draft.copy(timeRegex = it) },
                    label = { Text("时间格式（可选）") },
                    supportingText = { Text("正则，要求文本里出现时间，如 \\d{1,2}:\\d{2}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- 4. 生效时段 ----
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("4. 生效时段")
                Text(
                    "可以有多个时段。点时间块用滚轮选择，不需要手动输入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.windows.forEachIndexed { index, window ->
                    WindowEditorRow(
                        index = index,
                        window = window,
                        canDelete = draft.windows.size > 1,
                        onChange = { updated ->
                            draft = draft.copy(
                                windows = draft.windows.toMutableList().also { it[index] = updated },
                            )
                        },
                        onDelete = {
                            draft = draft.copy(
                                windows = draft.windows.filterIndexed { i, _ -> i != index },
                            )
                        },
                    )
                }
                val reachedLimit = draft.windows.size >= MAX_RULE_WINDOWS
                OutlinedButton(
                    onClick = {
                        draft = draft.copy(windows = draft.windows + TimeWindow(9 * 60, 10 * 60))
                    },
                    enabled = !reachedLimit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (reachedLimit) "最多 $MAX_RULE_WINDOWS 个时段" else "+ 添加时段")
                }
            }

            // ---- 5. 重复周期与日期范围 ----
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("5. 重复周期")
                RepeatSelector(
                    repeatType = draft.repeatType,
                    weekdays = draft.weekdays,
                    anchorDay = draft.anchorDay,
                    anchorMonth = draft.anchorMonth,
                    onRepeatType = { draft = draft.copy(repeatType = it) },
                    onWeekdays = { draft = draft.copy(weekdays = it) },
                    onAnchorDay = { draft = draft.copy(anchorDay = it) },
                    onAnchorMonth = { draft = draft.copy(anchorMonth = it) },
                )
                Text("生效日期范围（可不限）", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField(
                        label = "开始日期",
                        date = draft.startDate,
                        modifier = Modifier.weight(1f),
                    ) { draft = draft.copy(startDate = it) }
                    DateField(
                        label = "结束日期",
                        date = draft.endDate,
                        modifier = Modifier.weight(1f),
                    ) { draft = draft.copy(endDate = it) }
                }
            }

            // ---- 6. 汇总 ----
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("生效摘要")
                Text(
                    draft.schedule.summary() +
                        if (draft.startDate.isNotBlank() || draft.endDate.isNotBlank()) {
                            " · " + draft.startDate.ifBlank { "不限" } + " 至 " +
                                draft.endDate.ifBlank { "不限" }
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用这条规则", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }
            }

            if (draft.error.isNotBlank()) {
                Text(
                    draft.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val error = draft.validate()
                        if (error != null) draft = draft.copy(error = error) else onSave(draft)
                    },
                ) { Text("保存") }
                OutlinedButton(onClick = onCancel) { Text("取消") }
                if (draft.id != 0L) {
                    OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("删除") }
                }
            }
            Column(Modifier.height(24.dp)) {}
        } else {
            Text(
                "选好应用后才能配置匹配规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/**
 * 应用选择弹窗：带搜索的完整列表 + 手动输入。
 *
 * 手动输入是必需的而不是补充：Windows 上没有统一的「已安装应用」清单，
 * 只能扫开始菜单与安装目录，绿色版程序经常扫不到。
 */
@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>,
    selectedKey: String,
    onPick: (AppEntry) -> Unit,
    onManual: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim().lowercase()
            apps.filter {
                it.label.lowercase().contains(q) || it.key.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用（${apps.size}）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索名称或标识符") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppList(
                    entries = filtered,
                    selectedKey = selectedKey,
                    onPick = onPick,
                    modifier = Modifier.height(420.dp),
                )
                if (query.isNotBlank() && filtered.isEmpty()) {
                    OutlinedButton(onClick = { onManual(query.trim()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("直接用「${query.trim()}」作为标识符")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 新建自定义数据源 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewSourceDialog(
    onCreate: (String, MetricBucket) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf(MetricBucket.COUNT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建数据源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据源名称") },
                    placeholder = { Text("例如：每日背单词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("默认计数方式", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(MetricBucket.COUNT, MetricBucket.FLAG).forEach { b ->
                        FilterChip(
                            selected = bucket == b,
                            onClick = { bucket = b },
                            label = {
                                Text(
                                    if (b == MetricBucket.FLAG) "每天一次" else "按次数累加",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Text(
                    "自建数据源没有自动采集，只能由规则命中计数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, bucket) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 时段上限，与 RemindPlanner.MAX_WINDOWS 保持一致（任务提醒沿用同一上限） */
internal const val MAX_RULE_WINDOWS = 8

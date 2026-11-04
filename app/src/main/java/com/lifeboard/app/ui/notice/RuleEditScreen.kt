package com.lifeboard.app.ui.notice

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lifeboard.core.model.SourceType
import com.lifeboard.server.db.CaptureRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 应用列表项 */
private data class AppItem(val packageName: String, val label: String)

/**
 * 单条规则的编辑页。
 *
 * 流程刻意设计成「先选应用、再配规则」：不选应用时后面的匹配项没有意义，
 * 也容易让用户以为规则是全局生效的。
 */
@Composable
fun RuleEditScreen(
    rule: CaptureRuleEntity,
    onSave: (CaptureRuleEntity) -> Unit,
    onCancel: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(rule) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 查询全部已安装应用在主线程可能明显卡顿（几百个包），放到 IO 线程异步加载
    var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                // 不用 getLaunchIntentForPackage 过滤：大量需要监听的应用（系统组件、
                // 无图标的后台应用）没有 Launcher Activity，按它筛会漏掉一大片。
                // 也不做任何包名过滤，全部列出来让用户自己挑，靠搜索缩小范围。
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map {
                        AppItem(
                            packageName = it.packageName,
                            label = runCatching { pm.getApplicationLabel(it).toString() }
                                .getOrDefault(it.packageName),
                        )
                    }
                    .sortedBy { it.label }
            }.getOrDefault(emptyList())
        }
        loadingApps = false
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            loading = loadingApps,
            onPick = {
                draft = draft.copy(packageName = it.packageName)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false },
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
                    onDelete(rule.id)
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
            if (rule.id == 0L) "新增规则" else "编辑规则",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // ---- 第一步：选应用 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 选择应用", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (draft.packageName.isBlank()) {
                        "未选择。点击下方按钮从已安装应用里挑一个。"
                    } else {
                        draft.packageName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draft.packageName.isBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                OutlinedButton(onClick = { showAppPicker = true }) {
                    Text(if (draft.packageName.isBlank()) "选择应用" else "重新选择")
                }
            }
        }

        // 未选应用时，后面的配置项不展示：避免误以为规则已在生效
        if (draft.packageName.isBlank()) {
            Text(
                "选好应用后再配置匹配规则。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
            return@Column
        }

        // ---- 第二步：命中后归到哪个源 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. 命中后记到哪个数据源", style = MaterialTheme.typography.titleSmall)
                Text(
                    "面板按数据源聚合展示，选一个已有的源即可复用其统计与趋势。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SourceType.entries
                    .filter { it != SourceType.SCREEN_TIME && it != SourceType.MANUAL }
                    .forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    draft = draft.copy(
                                        sourceName = source.name,
                                        bucketName = defaultBucketFor(source),
                                    )
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${source.displayName}（${source.name}）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (draft.sourceName == source.name) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
            }
        }

        // ---- 第三步：匹配条件 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. 匹配条件", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = draft.includeKeywords,
                    onValueChange = { draft = draft.copy(includeKeywords = it) },
                    label = { Text("必须包含的词") },
                    supportingText = { Text("多个词用逗号分隔，全部出现才算命中。留空表示不限制。") },
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
                    supportingText = { Text("正则，要求文本里出现时间。如 09:02 这类写法用 \\d{1,2}:\\d{2}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 第四步：时段与幂等 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("4. 生效时段", style = MaterialTheme.typography.titleSmall)

                MinuteField(
                    label = "开始（HH:mm）",
                    minute = draft.windowStartMinute,
                    onChange = { draft = draft.copy(windowStartMinute = it) },
                )
                MinuteField(
                    label = "结束（HH:mm）",
                    minute = draft.windowEndMinute,
                    onChange = { draft = draft.copy(windowEndMinute = it) },
                )
                Text(
                    "只在此时段内采信。超出时段的命中会被忽略，" +
                        "避免晚上翻看记录页被误判。支持跨零点（如 22:00–02:00）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日只记一次", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "打开后当天首次命中即固定为 1，重复读屏不会累加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.dailyOnce,
                        onCheckedChange = { draft = draft.copy(dailyOnce = it) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSave(draft) },
                enabled = draft.packageName.isNotBlank() && draft.sourceName.isNotBlank(),
            ) { Text("保存") }
            OutlinedButton(onClick = onCancel) { Text("取消") }
            if (rule.id != 0L) {
                OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("删除") }
            }
        }

        if (draft.sourceName.isBlank()) {
            Text(
                "还需选择数据源才能保存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MinuteField(label: String, minute: Int, onChange: (Int) -> Unit) {
    val text = remember(minute) { formatMinute(minute) }
    var input by remember(minute) { mutableStateOf(text) }
    OutlinedTextField(
        value = input,
        onValueChange = { raw ->
            input = raw
            parseMinute(raw)?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppPickerDialog(
    apps: List<AppItem>,
    loading: Boolean,
    onPick: (AppItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim().lowercase()
            apps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
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
                    label = { Text("搜索名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading -> Text("正在加载应用列表…", style = MaterialTheme.typography.bodySmall)
                        filtered.isEmpty() -> Text(
                            if (apps.isEmpty()) "读不到已安装应用，检查是否被系统权限限制" else "没有匹配的应用",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> filtered.forEach { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 分钟数转 HH:mm */
private fun formatMinute(minute: Int): String {
    val m = minute.coerceIn(0, 24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}

/** HH:mm 转分钟数，非法输入返回 null */
private fun parseMinute(text: String): Int? {
    val parts = text.trim().split(':')
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (h !in 0..24 || m !in 0..59) return null
    return (h * 60 + m).coerceIn(0, 24 * 60)
}

/** 选定数据源时给一个合理的默认量纲 */
private fun defaultBucketFor(source: SourceType): String = when (source) {
    SourceType.DUOLINGO -> "COUNT"
    else -> "FLAG"
}

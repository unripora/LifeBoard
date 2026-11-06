package com.lifeboard.app.ui.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.rules.AppIcon
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TimeSource

/**
 * 任务编辑页（M3.3）。
 *
 * 一个表单页，新建与编辑共用。数据源、分桶、档位都用 FilterChip，
 * 比下拉菜单少一次点击，且所有选项一眼可见，便于比较。
 *
 * 版式分三段，不是把十来个区块平铺：
 * - 数据源、任务名这类「先定是什么任务」的内容常驻在外，随时可见；
 * - 时间 / 未完成提醒 / 完成提醒 三个维度各自成 Tab，
 *   提醒配置项变多以后不会把首屏挤出屏外。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    taskId: Long,
    onDone: () -> Unit,
    vm: TaskEditViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val groups by vm.ruleGroups.collectAsStateWithLifecycle()

    LaunchedEffect(taskId) {
        // 新建（taskId=0）也要显式清一次：ViewModel 是 Activity 级的，
        // 上一个任务的残留内容会直接出现在新建表单里。
        if (taskId == 0L) vm.reset()
        vm.load(taskId)
    }

    /**
     * 关闭页面。
     *
     * 统一走这里而不是裸 [onDone]：ViewModel 是 Activity 级的，
     * 保存后 `done` 会一直停在 true，下次进入时
     * `LaunchedEffect(state.done)` 立刻命中，页面闪一下就关掉——
     * 这就是「点编辑有时候进不去」的原因。
     */
    val close: () -> Unit = {
        vm.consumeDone()
        onDone()
    }

    LaunchedEffect(state.done) { if (state.done) close() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf<String?>(null) }

    // 有未保存改动时，返回键先问一句，不要静默丢掉用户刚写的内容
    val requestExit: () -> Unit = {
        if (state.dirty) confirmDiscard = true else { vm.reset(); close() }
    }
    BackHandler(onBack = requestExit)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (taskId == 0L) "新建任务" else "编辑任务") },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        // Scaffold 的 padding 已经把状态栏让出来了，顶栏不会再压住标题
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = { Text("任务名称") },
                placeholder = { Text("例如：每天阅读 30 分钟") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            DataSourceSection(
                state = state,
                groups = groups,
                onPickApp = { pickingApp = it },
                onClearRule = { vm.setRule(null) },
                onPickSource = vm::setSource,
            )

            Section("量纲") {
                if (state.ruleId != 0L) {
                    // 关联了规则：量纲只有规则一个事实来源，这里只读展示
                    Text(
                        "由关联的采集规则决定（${bucketName(state.metricBucket)}），无需配置。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ChipRow(
                        options = state.availableBuckets,
                        selected = state.metricBucket,
                        label = { bucketName(it) },
                        onSelect = vm::setBucket,
                    )
                    Text(
                        "「完成」= 一天做没做成（1 或 0），打卡签到类用它；" +
                            "「计数」= 一天做了几次，如喝 8 杯水。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.threshold,
                    onValueChange = vm::setThreshold,
                    label = { Text("达成阈值" + if (state.unit.isEmpty()) "" else "（${state.unit}）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.weight,
                    onValueChange = vm::setWeight,
                    label = { Text("权重") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "权重决定该任务在今日得分里占的比重，越大越重要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                EDIT_TABS.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            when (tab) {
                0 -> TimeTab(
                    state = state,
                    rules = rules,
                    onTimeSource = vm::setTimeSource,
                    onOffset = vm::setWindowOffset,
                    onShiftStart = { vm.shiftStart(it) },
                    onShiftEnd = { vm.shiftEnd(it) },
                )
                1 -> RemindTab(
                    level = state.remindLevel,
                    optionText = { level ->
                        when (level) {
                            RemindLevel.NONE -> "只记录是否完成，不做任何提示"
                            RemindLevel.WEAK -> "到点发一条通知"
                            RemindLevel.STRONG -> "到点响铃 + 震动，需手动停止"
                        }
                    },
                    onSelect = vm::setRemindLevel,
                )
                else -> DoneRemindTab(
                    level = state.doneRemindLevel,
                    optionText = { level ->
                        when (level) {
                            RemindLevel.NONE -> "完成时不发通知"
                            RemindLevel.WEAK -> "完成时弹出顶部横幅通知"
                            RemindLevel.STRONG -> "完成时弹横幅并响铃 + 震动"
                        }
                    },
                    onSelect = vm::setDoneRemindLevel,
                    toastEnabled = state.doneToastEnabled,
                    onToastChange = vm::setDoneToastEnabled,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("启用该任务", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.enabled,
                    onCheckedChange = vm::setEnabled,
                )
            }

            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::save, enabled = !state.saving) {
                    Text(if (state.saving) "保存中…" else "保存")
                }
                OutlinedButton(onClick = requestExit) { Text("取消") }
            }
        }
    }

    // 第二级：先选应用，再选该应用下的规则
    val pkg = pickingApp
    if (pkg != null) {
        val group = groups.firstOrNull { it.packageName == pkg }
        AlertDialog(
            onDismissRequest = { pickingApp = null },
            title = { Text("选择规则 · ${group?.appLabel ?: pkg}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group?.rules?.forEach { option ->
                        RulePickRow(
                            title = ruleTitle(option),
                            subtitle = option.summary,
                            selected = state.ruleId == option.id,
                            onClick = {
                                vm.setRule(option)
                                pickingApp = null
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingApp = null }) { Text("关闭") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次编辑？") },
            text = { Text("你有还没保存的改动，返回会丢掉它们。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.reset()
                    close()
                }) { Text("丢弃") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmDiscard = false
                        vm.save()
                    }) { Text("保存") }
                    TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
                }
            },
        )
    }
}

/** 三个 Tab 的标题 */
private val EDIT_TABS = listOf("时间", "未完成提醒", "完成提醒")

/**
 * 数据源 = 应用 → 规则。
 *
 * 只到应用粒度是不够的：飞书下可能同时有「上班打卡」和「下班打卡」两条规则，
 * 平铺成「飞书打卡」一个选项时，用户根本没法表达要的是哪一条。
 */
@Composable
private fun DataSourceSection(
    state: TaskEditUiState,
    groups: List<AppRuleGroup>,
    onPickApp: (String) -> Unit,
    onClearRule: () -> Unit,
    onPickSource: (SourceRef) -> Unit,
) {
    val chosen = groups.flatMap { it.rules }.firstOrNull { it.id == state.ruleId }

    Section("数据源") {
        if (chosen != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(chosen.packageName, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(
                        chosen.appLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ruleTitle(chosen),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onPickApp(chosen.packageName) }) { Text("更换") }
            }
        } else if (groups.isNotEmpty()) {
            Text(
                "选到具体规则：同一个应用下可能有多条规则（如上班打卡、下班打卡），" +
                    "选错规则会读成别的数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { group ->
                    AppPickRow(
                        group = group,
                        onPick = { onPickApp(group.packageName) },
                    )
                }
            }
        } else {
            Text(
                "还没有可用的规则。先去「规则」页建一条，再回来关联。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // 兜底：没有对应应用的内置源直接采集（如小米健康步数），不建规则也能用
        Text(
            if (chosen != null) "或改用以下内置数据源（会解除当前规则关联）" else "或改用内置数据源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 这里按「实际选中项」渲染，不能用占位值比较：否则没关联规则时
        // 列表里会伪装成「手动打卡已选」，用户会以为自己选的是手动打卡
        ChipRow(
            options = SourceType.entries.map { builtinRef(it) },
            selected = if (chosen == null) state.source else null,
            label = { it.displayName },
            onSelect = {
                onClearRule()
                onPickSource(it)
            },
        )
    }
}

/** 应用行：图标 + 名称 + 名下规则数，点进去选具体规则 */
@Composable
private fun AppPickRow(
    group: AppRuleGroup,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(group.packageName, size = 36)
        Column(Modifier.weight(1f)) {
            Text(
                group.appLabel.ifBlank { context.packageName },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                group.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${group.rules.size} 条规则",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 弹窗里的一条规则 */
@Composable
private fun RulePickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Tab1「时间」：时间来源、生效规则、时段、判定时刻 */
@Composable
private fun TimeTab(
    state: TaskEditUiState,
    rules: List<RuleOption>,
    onTimeSource: (TimeSource) -> Unit,
    onOffset: (Int) -> Unit,
    onShiftStart: (Int) -> Unit,
    onShiftEnd: (Int) -> Unit,
) {
    val chosen = rules.firstOrNull { it.id == state.ruleId }
    // 未关联规则时「跟随规则」没有意义，此时一律按自定义时间走
    val effectiveSource = if (chosen == null) TimeSource.INDEPENDENT else state.timeSource
    val followRule = effectiveSource == TimeSource.FOLLOW_RULE

    Section("时间来源") {
        ChipRow(
            options = TimeSource.entries,
            selected = effectiveSource,
            label = { it.displayName },
            onSelect = onTimeSource,
        )
        Text(
            effectiveSource.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chosen == null) {
            Text(
                "未关联规则，只能自定义时间。要跟随规则请先在上面选一条。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Section("生效时间") {
        if (followRule && chosen != null) {
            Text(
                "由关联规则决定：${chosen.appLabel} · ${ruleTitle(chosen)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                chosen.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "超过规则时段仍未达成才会提醒。切到「自定义时间」可以自己定提醒时刻。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TimeRow("开始", PanelViewModel.formatMinute(state.windowStart), onShiftStart)
            TimeRow("结束", PanelViewModel.formatMinute(state.windowEnd), onShiftEnd)
            Text(
                "时段结束${offsetText(state.windowOffsetMinutes)}若仍未达成才提醒。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 判定时刻相对「时段结束」偏移。负数提前、正数延后。
    // 默认 +5：规则时段同时是读屏窗口，准点判定会出现
    //「提醒响了，但已经读不到、没法补救」的尴尬。
    Section("判定时刻") {
        Text(
            "时段结束${offsetText(state.windowOffsetMinutes)}判定",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.windowOffsetMinutes.toFloat(),
            onValueChange = { onOffset(it.toInt()) },
            valueRange = -60f..60f,
            steps = 119,
        )
        Text(
            "负数表示提前判定，正数表示延后判定。默认 +5 分钟，" +
                "让读屏在时段结束后还能多计一会儿，避免提醒响了却没机会补救。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimeRow(label: String, value: String, onShift: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onShift(-30) }) { Text("-30") }
            TextButton(onClick = { onShift(30) }) { Text("+30") }
        }
    }
}

/** Tab2「未完成提醒」 */
@Composable
private fun RemindTab(
    level: RemindLevel,
    optionText: (RemindLevel) -> String,
    onSelect: (RemindLevel) -> Unit,
) {
    Section("未完成提醒") {
        ChipRow(
            options = RemindLevel.entries,
            selected = level,
            label = { PanelViewModel.levelName(it) },
            onSelect = onSelect,
        )
        Text(
            optionText(level),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tab3「完成提醒」 */
@Composable
private fun DoneRemindTab(
    level: RemindLevel,
    optionText: (RemindLevel) -> String,
    onSelect: (RemindLevel) -> Unit,
    toastEnabled: Boolean,
    onToastChange: (Boolean) -> Unit,
) {
    Section("完成提醒") {
        ChipRow(
            options = RemindLevel.entries,
            selected = level,
            label = { PanelViewModel.levelName(it) },
            onSelect = onSelect,
        )
        Text(
            optionText(level),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("完成时弹出小浮窗", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "小浮窗只在 LifeBoard 打开时显示。系统不允许应用在后台弹 Toast，" +
                        "那种情况请用上面的横幅通知。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = toastEnabled, onCheckedChange = onToastChange)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chunk.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(label(option), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

/** 规则的短名字：有识别关键词时用它，否则退回数据源名 */
private fun ruleTitle(option: RuleOption): String =
    option.keywords.trim().ifBlank { option.sourceName }

private fun bucketName(bucket: MetricBucket): String = when (bucket) {
    MetricBucket.DURATION_MINUTES -> "时长"
    MetricBucket.STEPS -> "步数"
    MetricBucket.SLEEP_MINUTES -> "睡眠"
    MetricBucket.COUNT -> "计数"
    MetricBucket.FLAG -> "完成"
}

/** 把分钟偏移显示成 "+5 分钟 / -5 分钟 / 准时" 这种好读的形式 */
private fun offsetText(minutes: Int): String = when {
    minutes > 0 -> "后 $minutes 分钟"
    minutes < 0 -> "前 ${-minutes} 分钟"
    else -> "准时"
}

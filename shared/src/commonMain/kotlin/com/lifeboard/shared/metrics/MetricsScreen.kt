package com.lifeboard.shared.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.panel.SOURCE_GROUP_CUSTOM
import com.lifeboard.shared.panel.SOURCE_GROUP_NATIVE

/**
 * 「数据指标」页（M13）。
 *
 * ## 为什么单独开一页
 *
 * 指标这个概念原本是**隐含**的：它只在「添加指标卡」的下拉里露一次面，
 * 用户看不到自己一共有哪些指标、各自有多少数据，也不知道自定义指标该在哪创建
 * （实际藏在「规则 → 新建数据源」里，跟指标毫无字面关联）。
 *
 * 单独成页之后，「有哪些指标、数据到哪了、怎么加一个」这三件事有了固定入口。
 *
 * ## 分组为什么和「添加指标卡」完全一致
 *
 * 两处共用 [`MetricQueryService.candidates`][com.lifeboard.shared.panel.candidates] 这一份产出，
 * 分组名也共用 [SOURCE_GROUP_NATIVE] / [SOURCE_GROUP_CUSTOM] 与插件的展示名。
 * 如果各写一套，用户迟早会发现「这里有小米健康，加卡片时却没有」——
 * 那种不一致查起来极费劲，因为两边的过滤条件各自演进过。
 *
 * 三组：
 * 1. **原生**：软件自身能力（屏幕使用、手动打卡）
 * 2. **自定义**：用户自己创建的指标
 * 3. **插件**：插件声明的指标，一个插件一组（「小米健康」下面挂步数、睡眠）
 *
 * ## 为什么展开就显示当前值
 *
 * 只列名字等于让用户挨个点进图表去看数字。展开即出值，才回答得了
 * 「这个指标到底有没有在记」。读数用 `metric` 表当天那一行，
 * 没有数据时显示「暂无数据」而不是 0——0 会被误读成「有记录但记为 0」。
 *
 * @param scope 面板业务端口，提供指标候选与读数能力
 * @param onCreateCustom 额外的创建回调。一般不需要传——
 *   本页默认直接经 `scope.ruleStore` 落库，见 [MetricsViewModel.createCustom]
 */
@Composable
fun MetricsScreen(
    scope: PanelScope,
    modifier: Modifier = Modifier,
    onCreateCustom: ((String, MetricBucket) -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val vm = remember(scope, coroutineScope) { MetricsViewModel(scope, coroutineScope) }
    val state by vm.state.collectAsState()

    LaunchedEffect(vm) { vm.refresh() }

    MetricsContent(
        state = state,
        onToggleGroup = vm::toggleGroup,
        onRefresh = vm::refresh,
        onCreateCustom = { name, bucket ->
            // 先调外部的（如果有），再由本页自己落库：
            // 让调用方去写库的话，两端都得重复一遍 CustomSourceRecord 的构造
            onCreateCustom?.invoke(name, bucket)
            vm.createCustom(name, bucket)
        },
        modifier = modifier,
    )
}

/**
 * 无状态的展示层。
 *
 * 与 [MetricsScreen] 拆开是为了能被别处嵌入（例如以后放进设置页或面板抽屉），
 * 也方便预览与测试时不建 ViewModel。
 */
@Composable
fun MetricsContent(
    state: MetricsUiState,
    onToggleGroup: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onCreateCustom: ((String, MetricBucket) -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        MetricsHeader(
            state = state,
            onRefresh = onRefresh,
            onCreateCustom = onCreateCustom?.let { { creating = true } },
        )

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.groups.isEmpty() -> EmptyHint()

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.groups.forEach { group ->
                    item(key = "header_${group.name}") {
                        GroupHeader(
                            group = group,
                            expanded = group.name in state.expandedGroups,
                            onToggle = { onToggleGroup(group.name) },
                        )
                    }
                    if (group.name in state.expandedGroups) {
                        // 组内每一项是一行「指标名 + 当前值」，不再折叠一层：
                        // 一个源通常只有一个量纲，再点一次才看到值就太深了
                        items(group.items, key = { "${it.sourceKey}_${it.metricKey}" }) { item ->
                            MetricRow(item)
                        }
                    }
                }
            }
        }
    }

    if (creating && onCreateCustom != null) {
        NewMetricDialog(
            onCreate = { name, bucket ->
                onCreateCustom(name, bucket)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun MetricsHeader(
    state: MetricsUiState,
    onRefresh: () -> Unit,
    onCreateCustom: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("数据指标", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                state.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCreateCustom != null) {
            TextButton(onClick = onCreateCustom) { Text("新建指标") }
        }
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
    HorizontalDivider()
}

/**
 * 分组标题，可点击折叠。
 *
 * 默认全展开（见 [MetricsUiState.expandedGroups] 的初始化）：
 * 用户来这里就是想看内容，先让他点三次才见到东西没有道理。
 */
@Composable
private fun GroupHeader(
    group: MetricsGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // 用文字箭头而不是图标：Material Icons 的扩展包在桌面端不一定有
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            group.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${group.items.size} 项",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行指标：左边名称与量纲，右边当前值 */
@Composable
private fun MetricRow(item: MetricItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (item.hasData) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.metricLabel + " · " + item.unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            item.valueText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.hasData) FontWeight.Medium else FontWeight.Normal,
            color = if (item.hasData) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "还没有可用的数据指标",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 新建自定义指标。
 *
 * 与规则页的「新建数据源」是同一个东西的两种入口。这里只暴露次数型与每天一次型
 * 两种量纲——它们覆盖了「自己记个数」的全部常见场景，而时长/步数要靠自动采集，
 * 让用户手工造一个时长型指标只会得到永远填不满的空指标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewMetricDialog(
    onCreate: (String, MetricBucket) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf(MetricBucket.COUNT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建自定义指标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("指标名称") },
                    placeholder = { Text("例如：每日背单词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("计数方式", style = MaterialTheme.typography.bodySmall)
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
                    "自定义指标不会自动采集，数值由规则命中后累加，或手动记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), bucket) },
                enabled = name.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

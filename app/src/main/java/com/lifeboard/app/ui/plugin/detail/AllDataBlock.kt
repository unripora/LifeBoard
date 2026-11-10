package com.lifeboard.app.ui.plugin.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.RawRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 插件详情页的「全部数据」块。
 *
 * ## 这里放的是原始条目，不是聚合值
 *
 * 聚合值（「步数 1253」「睡眠 15.8 小时」）在仪表盘的指标卡上已经有了。
 * 这一块要回答的是另一个问题：**插件这次同步到底返回了哪些条目**。
 * 步数是几十个采样点累加出来的，睡眠是几段区间拼出来的——
 * 想核对总数为什么是这个数，只能看这些原始条目。
 *
 * 数据来自插件在 `fetch` 里通过 `PluginContext.reportRawRecords` 上报的内容，
 * 宿主按日期保存：同一天再同步就覆盖，不同日期各存各的。
 *
 * ## 为什么可以收起来
 *
 * 条目数量是几十到上百行，展开会把下面的「操作」区推到很远；
 * 而用户打开详情页最常做的事是点「立即同步」。默认展开是第一次有用
 * （一眼看到同步拿到了什么），之后用户会自己收起来。
 *
 * @param groups 当前时间段内按指标分组的原始条目
 * @param range 当前选中的时间段
 * @param availableRange 库里已有数据的日期范围，用来提示「有没有更早的数据可查」
 * @param onSelectRange 切换时间段
 */
@Composable
fun AllDataBlock(
    groups: List<RawRecordGroup>,
    range: RawRange,
    availableRange: Pair<String, String>?,
    onSelectRange: (RawRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var pickingCustom by rememberSaveable { mutableStateOf(false) }
    val total = groups.sumOf { it.records.size }
    // 有查询痕迹但没条目的指标数：标题上要说清「几项有数据、几项暂无」，
    // 否则「15 项指标 · 923 条」会让人以为 15 项都有数据
    val emptyGroups = groups.count { it.records.isEmpty() }

    GlassCard(modifier = modifier.fillMaxWidth()) {
        // 标题行整行可点：折叠控件的可点区域只包文字时用户很难点中
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "全部数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (groups.isEmpty()) {
                        "${range.label} · 没有原始记录"
                    } else {
                        val base = "${range.label} · ${groups.size} 项指标 · $total 条原始记录"
                        // 只在真有空的指标时才补这句话，全部有数据时不必多嘴
                        if (emptyGroups > 0) "$base · $emptyGroups 项暂无" else base
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RangePicker(
                    range = range,
                    availableRange = availableRange,
                    onSelect = onSelectRange,
                    onCustom = { pickingCustom = true },
                )

                if (groups.isEmpty()) {
                    Text(
                        "这段时间内没有原始记录。点下面的「立即同步」采一次，"
                            + "或用插件提供的「回补」按钮把更早的数据拉回来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    groups.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider()
                        RawGroup(group)
                    }
                }
            }
        }
    }

    if (pickingCustom) {
        CustomRangeDialog(
            initial = range as? RawRange.Custom,
            availableRange = availableRange,
            onDismiss = { pickingCustom = false },
            onConfirm = { from, to ->
                onSelectRange(RawRange.Custom(from, to))
                pickingCustom = false
            },
        )
    }
}

/**
 * 时间段选择：快捷按钮 + 「自定义」。
 *
 * 快捷按钮覆盖绝大多数场景（看今天同步到了什么、看这一周），
 * 自定义留给「核对某个具体日期」。两者都要，因为少任一个都会逼用户绕路。
 */
@Composable
private fun RangePicker(
    range: RawRange,
    availableRange: Pair<String, String>?,
    onSelect: (RawRange) -> Unit,
    onCustom: () -> Unit,
) {
    // 快捷项的顺序按「由近及远」，与用户查看数据的心智一致
    val quick = listOf(
        RawRange.Today,
        RawRange.Yesterday,
        RawRange.Last7Days,
        RawRange.Last30Days,
        RawRange.All,
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { option ->
                RangeChip(
                    text = option.label,
                    selected = range == option,
                    onClick = { onSelect(option) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RangeChip(
                text = if (range is RawRange.Custom) range.label else "自定义",
                selected = range is RawRange.Custom,
                onClick = onCustom,
            )
            if (availableRange != null) {
                // 有数据的最早/最晚日期：用户据此知道「能往前翻到哪天」，
                // 而不是反复点「全部」猜有没有更早的数据
                Text(
                    "有数据：${availableRange.first} ~ ${availableRange.second}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一个小胶囊按钮。用 Surface 而不是 Button：5 个挤一行时 Button 的内边距太宽 */
@Composable
private fun RangeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * 自定义起止日期。
 *
 * 用两个文本框而不是日期选择器：Compose 的 DatePicker 在窄屏弹窗里体验很差，
 * 而这里的输入是 `yyyy-MM-dd`，手输一下比连点两个月历视图快。
 * 格式不合法时禁用确定按钮并说明原因，不静默吞掉。
 */
@Composable
private fun CustomRangeDialog(
    initial: RawRange.Custom?,
    availableRange: Pair<String, String>?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    val default = availableRange?.second ?: LocalDate.now().toString()
    var from by rememberSaveable { mutableStateOf(initial?.from ?: default) }
    var to by rememberSaveable { mutableStateOf(initial?.to ?: default) }

    val fromOk = runCatching { LocalDate.parse(from) }.isSuccess
    val toOk = runCatching { LocalDate.parse(to) }.isSuccess
    // 起止顺序反了会让 BETWEEN 查不到任何行，直接挡住并提示，而不是返回空列表
    val ordered = fromOk && toOk && from <= to
    val valid = fromOk && toOk && ordered

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    singleLine = true,
                    isError = !fromOk,
                    label = { Text("开始日期") },
                    placeholder = { Text("2026-11-01") },
                )
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it },
                    singleLine = true,
                    isError = !toOk || !ordered,
                    label = { Text("结束日期") },
                    placeholder = { Text("2026-11-09") },
                )
                Text(
                    when {
                        !fromOk || !toOk -> "日期格式应为 yyyy-MM-dd"
                        !ordered -> "开始日期不能晚于结束日期"
                        availableRange != null ->
                            "有数据：${availableRange.first} ~ ${availableRange.second}"
                        else -> "这个插件还没有原始数据"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(from, to) }, enabled = valid) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 一个指标的原始条目列表。
 *
 * ## 默认只露 3 条
 *
 * 步数是分钟级采样，一天就上千条；全部铺开会把详情页撑到几十屏，
 * 连「立即同步」按钮都找不到。但也不能只给个条数——用户打开这块
 * 就是想看看「同步到底拿到了什么样子」，看几条比看数字直观得多。
 *
 * 所以默认显示最新的 [PREVIEW_COUNT] 条，底部给一行「展开全部 N 条」。
 * 展开后限制高度内部滚动，而不是把整页继续拉长。
 *
 * 显示的是**最新**的几条而不是最早的：用户刚同步完，关心的是「刚刚采到了什么」，
 * 早上 6 点的采样点排在最后才有意义。
 */
@Composable
private fun RawGroup(group: RawRecordGroup) {
    val bucket = MetricSpec.bucketOf(group.metricKey) ?: MetricBucket.COUNT
    var expanded by rememberSaveable(group.metricKey) { mutableStateOf(false) }

    val total = group.records.size
    // records 已按时间升序排好，取尾部即最新的几条
    val shown = if (expanded) group.records else group.records.takeLast(PREVIEW_COUNT)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (total == 0) "暂无" else "$total 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 查过但这个时间段里一条都没有：明确写「暂无」，
        // 而不是留一片空白让人怀疑是加载失败
        if (total == 0) {
            Text(
                "暂无",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            return@Column
        }

        // 展开后才内部滚动：收起态只有 3 行，不需要滚动容器，
        // 否则会吞掉外层的滑动手势
        val listModifier = if (expanded) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }

        Column(modifier = listModifier) {
            shown.forEach { record ->
                RawRecordRow(record = record, bucket = bucket, unit = group.unit)
            }
        }

        if (total > PREVIEW_COUNT) {
            Text(
                if (expanded) "收起" else "展开全部 $total 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** 收起态每个指标显示几条 */
private const val PREVIEW_COUNT = 3

/**
 * 一条原始记录：左边时间，右边数值与附注。
 *
 * 时间用 `MM-dd HH:mm`：跨天同步（昨天 + 今天）时只显示 `HH:mm` 会分不清是哪天的。
 */
@Composable
private fun RawRecordRow(record: RawRecord, bucket: MetricBucket, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimestamp(record.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (record.detail.isNotBlank()) {
                Text(
                    record.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatRawValue(record.value, bucket, unit),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private val RAW_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(RAW_TIME_FORMAT)

/**
 * 原始条目的数值文本。
 *
 * 睡眠条目是「一段区间的分钟数」，换算成小时读起来才有意义；
 * 步数这类整数不拖小数点。
 */
private fun formatRawValue(value: Double, bucket: MetricBucket, unit: String): String {
    val number = when (bucket) {
        MetricBucket.SLEEP_MINUTES -> "%.1f 小时".format(value / 60.0)
        MetricBucket.DURATION_MINUTES -> "%.0f 分钟".format(value)
        MetricBucket.FLAG -> if (value > 0.0) "完成" else "未完成"
        else -> {
            val text = if (value == value.toInt().toDouble()) {
                value.toInt().toString()
            } else {
                "%.1f".format(value)
            }
            if (unit.isBlank()) text else "$text $unit"
        }
    }
    return number
}

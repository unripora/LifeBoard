package com.lifeboard.shared.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.lifeboard.shared.dashboard.BuiltinSeries
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.shared.dashboard.ChartAxis
import com.lifeboard.shared.dashboard.ChartCardConfig
import com.lifeboard.shared.dashboard.ChartDensity
import com.lifeboard.shared.dashboard.ChartSeriesConfig
import com.lifeboard.shared.dashboard.ChartType
import com.lifeboard.shared.dashboard.CHART_TIME_WINDOWS_DAYS
import com.lifeboard.shared.dashboard.displayName
import com.lifeboard.shared.platform.LocalPlatform
import com.lifeboard.shared.panel.MetricCandidate
import com.lifeboard.shared.panel.SOURCE_GROUP_NATIVE
import com.lifeboard.shared.panel.sourceKeyFor

/**
 * 自定义图表的配置弹窗。
 *
 * ## 两类图表，两套配置
 *
 * - **饼图**只问「分类来源」和 Top N，不需要指标——它画的是应用时长这类分类占比，
 *   跟时间序列没有关系。硬让它先选一个指标，那个指标最后会被静默丢掉
 * - **其余形态**问「指标 + 时间范围」。环形进度虽然也是单值，但它的当前值来自序列
 *
 * ## 多指标的规则（问题 6 的答复）
 *
 * 时间序列类（折线/柱状/面积/散点）**不设条数上限**，这是刻意改掉的。
 * 原来的上限是 2，理由是「Vico 只有两根 Y 轴」——但现在渲染是自绘的，
 * 同一根轴上叠三条同单位的线完全可读，没理由拦着。
 *
 * 真正需要约束的不是**条数**，而是**轴数**：一根轴只能承载一种单位，
 * 两张图各画一个单位才是可比的。所以：
 * - 轴由**每条序列自己选**（左/右），不再按「第一条左、第二条右」硬分配
 * - 一条不同单位的序列被放到已有不同单位的轴上时，保存前就提示会读错
 *
 * 饼图只开放分类占比：查询层读取 `categorySourceKey`，不消费 `series`。
 * 指标占比需要先定义单位可比性和聚合口径，再一起交付配置与查询。
 *
 * ## 约束在哪一步生效
 *
 * - 环形进度与热力图只允许一个指标（单值 / 单标度）
 * - 连击、单条规则完成率这类序列必须指明看哪条任务
 * - 保存按钮在配置无效时不可点，且给出原因
 *
 * @param initial 编辑已有图表时的初始配置；为 null 表示新建
 * @param onConfirm 保存回调。调用方负责写入与关闭
 */
@Composable
fun ChartConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (ChartCardConfig) -> Unit,
    initial: ChartCardConfig? = null,
) {
    val scope = LocalPlatform.current.panelScope
    var options by remember { mutableStateOf<List<SeriesOption>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<TaskConfig>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var chartType by remember { mutableStateOf(initial?.chartType ?: ChartType.LINE) }
    var series by remember { mutableStateOf(initial?.series.orEmpty()) }
    var timeWindow by remember { mutableStateOf(initial?.timeWindowDays ?: 30) }
    // 标题：用户可自定义。默认留空 = 用类型名，不在新建时替用户填一个
    // 「折线图」，否则用户不点这一栏就永远是一串同名的卡
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var density by remember { mutableStateOf(initial?.density ?: ChartDensity.COMFORTABLE) }
    // Top N 只对饼图有意义：其余形态不读这个字段，但仍要保留编辑前的值，
    // 免得用户切一下类型再切回来就被重置
    var topN by remember { mutableStateOf(initial?.topN ?: DEFAULT_TOP_N) }
    // 是否正在浏览指标列表。与 picking 分开：
    // picking 只表示「已经点了一条需要任务的序列」，此时列表换成任务清单
    var browsing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<SeriesOption?>(null) }
    // 正在换任务的那条已选序列的下标；null 表示不在换任务。
    // 与 picking 分开是因为它改的是已有序列，而不是新增一条
    var editingTaskIndex by remember { mutableStateOf<Int?>(null) }
    // 指标选择器里展开着的插件分组名。空集 = 全部收起。
    // 默认收起是有意的：分组就是「先看有哪些插件，再挑指标」，
    // 一进来全展开等于没分组，还是要在一整屏里翻
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(scope) {
        // 采集源与内置序列一起列出来：对用户来说它们都是「能画的指标」，
        // 分成两个入口只会让人以为少了一半选择
        options = flattenCandidates(MetricQueryService(scope).candidates(scope)) + builtinOptions()
        tasks = runCatching {
            // 任务列表走业务层，不直接摸库：桌面端与 Android 端读的是同一份 schema，
            // 但装配方式不同，绕开它会让这一页在桌面端拿不到数据
            scope.taskStore.getAll()
        }.getOrDefault(emptyList())
        loading = false
    }

    // 按来源分组。分组键在降级时复用 SeriesOption.group 的默认值，
    // 这样即使某天数据源没带分组名，也不会出现一个标题为空的分组
    val groups = remember(options) { groupOptions(options) }

    // 饼图仅开放已有查询链路支持的分类占比。
    val usesCategory = chartType == ChartType.PIE
    // 单序列形态：环形进度是「一个当前值」，热力图是「一种深浅标度」
    val singleSeriesOnly = chartType == ChartType.RING || chartType == ChartType.HEATMAP

    val config = ChartCardConfig(
        title = title,
        chartType = chartType,
        series = if (usesCategory) emptyList() else series,
        timeWindowDays = timeWindow,
        showLegend = true,
        categorySourceKey = if (usesCategory) CATEGORY_APP_USAGE else null,
        topN = if (usesCategory) topN else null,
        density = density,
    )
    val validationError = validateChartConfig(chartType, config, loading)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加图表" else "编辑图表") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel("图表名称")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    // 占位提示必须说明「不填会怎样」，否则用户以为名称是必填的
                    placeholder = { Text(chartType.displayName(), style = MaterialTheme.typography.bodySmall) },
                    supportingText = {
                        Text(
                            "留空时显示「${chartType.displayName()}」",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )

                SectionLabel("图表类型")
                FlowOfChips(
                    items = ChartType.entries.map { it to chartTypeLabel(it) },
                    selected = chartType,
                    onSelect = { picked ->
                        chartType = picked
                        browsing = false
                        picking = null
                        // 单序列形态裁掉多余项；饼图的草稿序列暂存，切回折线时可继续编辑。
                        series = when {
                            picked == ChartType.RING || picked == ChartType.HEATMAP -> series.take(1)
                            else -> series
                        }
                    },
                )

                if (usesCategory) {
                    SectionLabel("分类来源")
                    Text(
                        "应用使用时长（按应用汇总，太长的尾部合并为「其他」）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionLabel("显示项数")
                    FlowOfChips(
                        items = CATEGORY_TOP_N_CHOICES.map { it to "前 $it 项" },
                        selected = topN,
                        onSelect = { topN = it },
                    )
                }

                if (!usesCategory) {
                    SectionLabel("数据")
                    val editIndex = editingTaskIndex
                    if (editIndex != null) {
                        // 换任务时原地换成任务清单，不动其它序列的轴与顺序
                        val current = series.getOrNull(editIndex)
                        if (current == null) {
                            editingTaskIndex = null
                        } else {
                            TaskPicker(
                                title = "为「${current.label}」换一条任务",
                                tasks = tasks,
                                // 原地替换没有外层标题栏，取消由它自己给
                                showCancel = true,
                                onSelect = { task ->
                                    series = series.mapIndexed { i, item ->
                                        if (i != editIndex) {
                                            item
                                        } else {
                                            // 标签里带着旧任务名，换任务必须一起换掉，
                                            // 否则图例还写着上一个任务
                                            item.copy(
                                                taskId = task.id,
                                                label = replaceTaskSuffix(item.label, task.title),
                                            )
                                        }
                                    }
                                    editingTaskIndex = null
                                },
                                onCancel = { editingTaskIndex = null },
                            )
                        }
                    } else {
                        DataSection(
                            loading = loading,
                            series = series,
                            groups = groups,
                            tasks = tasks,
                            browsing = browsing,
                            picking = picking,
                            maxSeries = if (singleSeriesOnly) 1 else Int.MAX_VALUE,
                            singleSeriesOnly = singleSeriesOnly,
                            expandedGroups = expandedGroups,
                            onToggleGroup = { name ->
                                expandedGroups = if (name in expandedGroups) {
                                    expandedGroups - name
                                } else {
                                    expandedGroups + name
                                }
                            },
                            onBrowse = { browsing = it },
                            onPick = { picking = it },
                            onRemove = { index ->
                                series = series.filterIndexed { i, _ -> i != index }
                            },
                            onEditTask = { index -> editingTaskIndex = index },
                            onSetSeriesTitle = { index, value ->
                                series = series.mapIndexed { i, item ->
                                    if (i == index) item.copy(label = value) else item
                                }
                            },
                            onSetAxis = { index, axis ->
                                series = series.mapIndexed { i, item ->
                                    if (i == index) item.copy(axis = axis) else item
                                }
                            },
                            onAdd = { option, task ->
                                // 新序列默认跟「第一条」同轴：直接把新指标画到右轴，
                                // 会让同单位的第二条线平白跳到另一根刻度上
                                val axis = series.firstOrNull()?.axis ?: ChartAxis.LEFT
                                series = series + option.toSeriesConfig(
                                    axis = axis,
                                    taskId = task?.id,
                                    taskLabel = task?.title,
                                )
                                picking = null
                                browsing = false
                            },
                        )
                    }
                }

                SectionLabel("卡片样式")
                FlowOfChips(
                    items = CHART_DENSITY_CHOICES.map { it to it.label },
                    selected = density,
                    onSelect = { density = it },
                )
                Text(
                    density.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("时间范围")
                FlowOfChips(
                    items = CHART_TIME_WINDOWS_DAYS.map { it to "$it 天" },
                    selected = timeWindow,
                    onSelect = { timeWindow = it },
                )

                if (validationError != null) {
                    Text(
                        validationError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(config) },
                enabled = validationError == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 卡片密度的展示名与说明。放在界面层：配置层只关心取值 */
private val ChartDensity.label: String
    get() = when (this) {
        ChartDensity.COMFORTABLE -> "大卡"
        ChartDensity.COMPACT -> "方块"
    }

private val ChartDensity.description: String
    get() = when (this) {
        ChartDensity.COMFORTABLE -> "图占主体，适合逐个细看"
        ChartDensity.COMPACT -> "大数字 + 迷你趋势，宽屏一屏能排好几列"
    }

private val CHART_DENSITY_CHOICES = listOf(ChartDensity.COMFORTABLE, ChartDensity.COMPACT)

/** 标题长度上限。足够长，又能防止一整段话被塞进卡片标题 */
private const val MAX_TITLE_LENGTH = 24

/**
 * 「数据」一栏：已选序列 + 指标选择器。
 *
 * 选择器里选中需要任务的序列时，先展开任务列表再落定——
 * 连击这类序列没有任务是算不出来的，不能先加进去再报错。
 *
 * ## 每条已选序列能改什么
 *
 * - **名称**：直接内联编辑，改的是图例上显示的字。同一份数据被加了两次
 *   （比如「步数」和「睡眠」都要看）时，不改名在图例上分不出来
 * - **轴**：左/右两个按钮。这是「Y 轴可选两根」的落地点——
 *   以前轴是按顺序硬分配的，用户没有发言权
 *
 * ## 三块内容为什么必须分开
 *
 * 「已选数据」「选择指标按钮」「展开后的数据源列表」原本上下紧挨着、
 * 样式都是普通文字，用户分不清哪一块是自己刚点开的、哪一块是当前生效的。
 * 现在用三层区分：
 *
 * 1. **已选**放进带边框的卡片，每条前面有轴标识，一眼看出「这是图上的东西」
 * 2. **选择指标**是独立按钮，并说明还能加几个
 * 3. **选择器**只在有值时出现，带自己的标题栏和「完成」出口；
 *    此时**隐藏已选列表**——两个列表同时出现最容易混
 */
@Composable
private fun DataSection(
    loading: Boolean,
    series: List<ChartSeriesConfig>,
    groups: List<SeriesGroup>,
    tasks: List<TaskConfig>,
    browsing: Boolean,
    picking: SeriesOption?,
    maxSeries: Int,
    singleSeriesOnly: Boolean,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onBrowse: (Boolean) -> Unit,
    onPick: (SeriesOption?) -> Unit,
    onRemove: (Int) -> Unit,
    onEditTask: (Int) -> Unit,
    onSetSeriesTitle: (Int, String) -> Unit,
    onSetAxis: (Int, ChartAxis) -> Unit,
    onAdd: (SeriesOption, TaskConfig?) -> Unit,
) {
    if (loading) {
        Text(
            "正在读取数据源…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // 选择器打开时只显示选择器：已选列表与可选列表同时铺开，
    // 用户会误点、也分不清哪一行是「已经加进去的」
    if (browsing) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (picking == null) "选择指标" else "选择任务",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                // 返回一步：从任务清单回到指标列表，从指标列表回到已选
                TextButton(onClick = { if (picking != null) onPick(null) else onBrowse(false) }) {
                    Text(if (picking != null) "返回" else "完成")
                }
            }
            PickerBody(
                option = picking,
                groups = groups,
                tasks = tasks,
                series = series,
                expandedGroups = expandedGroups,
                onToggleGroup = onToggleGroup,
                onPick = onPick,
                onAdd = onAdd,
                onCancel = { if (picking != null) onPick(null) else onBrowse(false) },
            )
        }
        return
    }

    // ---- 已选数据 ----
    if (series.isEmpty()) {
        Text(
            "还没有选择指标",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            series.forEachIndexed { index, item ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左轴 / 右轴：让用户直接选挂在哪根轴上，
                        // 而不是按添加顺序被动分配
                        FlowOfChips(
                            items = listOf(ChartAxis.LEFT to "左轴", ChartAxis.RIGHT to "右轴"),
                            selected = item.axis,
                            onSelect = { axis -> onSetAxis(index, axis) },
                            chunkSize = 2,
                            compact = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        SeriesTitleField(
                            value = item.label,
                            onValueChange = { value -> onSetSeriesTitle(index, value) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val needsTask = BuiltinSeries.fromMetricKey(item.metricKey)?.needsTask == true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 任务型序列被改了任务后必须能看出来看的是哪一条，
                        // 否则两条同名序列无法区分
                        if (needsTask) {
                            Text(
                                taskHint(item.taskId, tasks),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onEditTask(index) }) { Text("换任务") }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = { onRemove(index) }) { Text("移除") }
                    }
                }
            }
        }
    }

    // ---- 添加入口 ----
    // 只有单序列形态才拦：时间序列类可以叠任意多条，
    // 真正的约束在「轴」上——同一根轴只能是一种单位，那由每条序列自己选
    if (series.size < maxSeries) {
        if (groups.isEmpty()) {
            Text(
                "没有可用的数据源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 先进入浏览态，而不是直接选中第一项：
            // 可选项有十几条，替用户点第一个等于替他做了选择
            OutlinedButton(onClick = { onBrowse(true) }) {
                Text(if (series.isEmpty()) "选择指标" else "再添加一个指标")
            }
            Text(
                if (singleSeriesOnly) {
                    "这个图表类型只能显示一个指标"
                } else {
                    "已选 ${series.size} 个：可以继续加。" +
                        "不同单位的指标请分到左右两根轴上，否则同一根轴的刻度会读错"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            "这个图表类型只能显示一个指标",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 序列名内联编辑。
 *
 * 用单行输入框而不是「改名的弹窗」：改名是高频小动作，
 * 再弹一层对话框会让「加三个指标、各改个名」变成九次点击。
 */
@Composable
private fun SeriesTitleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_SERIES_TITLE_LENGTH)) },
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/** 序列名长度上限。图例上放不下更长的名字 */
private const val MAX_SERIES_TITLE_LENGTH = 20

/**
 * 指标选择器：按来源分组、可展开收起的列表。
 *
 * ## 为什么按插件分组
 *
 * 装了两个插件之后，可选项变成「小米健康 · 步数 / 小米健康 · 睡眠 / …」一长串，
 * 每行都得念一遍来源名才知道自己跳到了哪个插件。分组之后第一屏只有
 * 「原生」「小米健康」这两行，点开才看到组里的指标。
 *
 * ## 为什么分组标题也是可点的
 *
 * 收起状态下点标题只做展开，不替用户选第一个指标：一组的指标可能有七八个，
 * 替用户点第一个等于替他做了选择。
 *
 * ## 需要任务的两步
 *
 * 连击、单条规则完成率这类序列必须先选任务：没有任务就算不出任何值。
 * 所以点它们不会直接落定，而是把列表换成任务清单。
 *
 * ## 不自己留「取消」
 *
 * 退出选择器由 [DataSection] 的标题栏统一提供（返回 / 完成），
 * 这里再放一个只会多出一条并列的出口，用户不知道该点哪个。
 *
 * @param option 需要选任务时的那条序列；为 null 表示在浏览指标列表
 */
@Composable
private fun PickerBody(
    option: SeriesOption?,
    groups: List<SeriesGroup>,
    tasks: List<TaskConfig>,
    series: List<ChartSeriesConfig>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onPick: (SeriesOption?) -> Unit,
    onAdd: (SeriesOption, TaskConfig?) -> Unit,
    onCancel: () -> Unit,
) {
    if (option?.needsTask == true) {
        TaskPicker(
            title = "「${option.label}」要指定看哪条任务",
            tasks = tasks,
            onSelect = { task -> onAdd(option, task) },
            onCancel = onCancel,
        )
        return
    }

    val takenKeys = series.map { "${it.sourceKey}|${it.metricKey}" }.toSet()
    if (groups.isEmpty()) {
        Text(
            "没有可用的数据源",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // 行数 = 分组标题 + 已展开分组里的指标，都算进去才不会被限高截断
        val rowCount = groups.sumOf { group ->
            1 + if (group.name in expandedGroups) group.options.size else 0
        }
        ScrollableRows(rowCount = rowCount) {
            groups.forEach { group ->
                val expanded = group.name in expandedGroups
                GroupHeader(
                    name = group.name,
                    count = group.options.size,
                    expanded = expanded,
                    onClick = { onToggleGroup(group.name) },
                )
                if (expanded) {
                    group.options.forEach { current ->
                        val alreadyAdded = current.optionKey in takenKeys
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !alreadyAdded) {
                                    if (current.needsTask) onPick(current) else onAdd(current, null)
                                }
                                // 组内缩进一格，视觉上归属清楚
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (alreadyAdded) "${current.label}（已添加）" else current.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                current.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一个分组的标题行：图标（展开 / 收起）+ 名称 + 指标条数。
 *
 * 用文字箭头而不是旋转动画：这一行只在弹窗里出现几十毫秒内被点一次，
 * 加动画只会让展开看起来比实际慢。
 */
@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            // 收起时也要说明里面有多少项，否则用户不知道值不值得展开
            "$count 个指标",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 把标签末尾的「 · 旧任务名」换成新任务名。
 *
 * 只动最后一段：序列名本身可能带空格或点号（如「单条规则完成率 · 早起」，
 * 那是上一次追加的结果），按分隔符从右往左替换才不会越加越长。
 */
private fun replaceTaskSuffix(label: String, taskTitle: String): String {
    val head = label.substringBeforeLast(TASK_LABEL_SEPARATOR, label)
    return "$head$TASK_LABEL_SEPARATOR$taskTitle"
}

/**
 * 任务清单。新增序列和换任务共用一套：两处要选的是同一个东西，
 * 各写一份迟早会在「停用任务的显示」「空列表的说明」这类细节上分叉。
 *
 * @param title 说明为什么要在这一步选任务
 * @param onSelect 选中某条任务
 * @param showCancel 是否自带「取消」。放在选择器里时由外层标题栏统一提供出口，
 *   这里不再重复；原地替换（换任务）时没有外层标题栏，才需要它
 */
@Composable
private fun TaskPicker(
    title: String,
    tasks: List<TaskConfig>,
    onSelect: (TaskConfig) -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean = false,
) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    if (tasks.isEmpty()) {
        Text(
            "还没有任何任务，先建一条再来",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ScrollableRows(rowCount = tasks.size) {
        tasks.forEach { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(task) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!task.enabled) {
                    Text(
                        "已停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (showCancel) {
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

/**
 * 已选任务型序列的所属任务说明。
 *
 * 任务可能已经被删掉：这时不能静默当成「看所有任务」，
 * 要说出来，否则用户看到的是一条口径已经变了的线。
 */
private fun taskHint(taskId: Long?, tasks: List<TaskConfig>): String {
    if (taskId == null) return "未指定任务，保存前请重新选择"
    val task = tasks.firstOrNull { it.id == taskId }
        ?: return "原任务已不存在，请重新选择"
    return "任务：${task.title}${if (task.enabled) "" else "（已停用）"}"
}

/**
 * 定高的滚动容器。
 *
 * 弹窗本身已经可以滚动，里面再套一个无限高的列表会互相抢高度：
 * 外层按内容测高，内层又想要全部空间，结果就是整页越来越长。
 * 所以这里给列表一个明确的最大高度，只在超出时自己滚。
 *
 * 行数少时不出现滚动条，也不额外占高度。
 *
 * @param rowCount 行数，用来决定要不要限高
 */
@Composable
private fun ScrollableRows(rowCount: Int, content: @Composable () -> Unit) {
    val needsScroll = rowCount > VISIBLE_ROWS
    val modifier = if (needsScroll) {
        Modifier
            .fillMaxWidth()
            // 用行高而非固定 dp：字号随系统设置放大时，限高也要跟着长
            .heightIn(max = (VISIBLE_ROWS * ROW_HEIGHT_DP).dp)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        content()
    }
}

/** 列表超过这个行数才开始自己滚动 */
private const val VISIBLE_ROWS = 6

/** 单行大致高度。用于把「几行」换算成限高 */
private const val ROW_HEIGHT_DP = 44

/** 饼图目前唯一的内置分类源：应用使用时长 */
private const val CATEGORY_APP_USAGE = "app_usage"

/** 饼图默认显示项数。与查询层的缺省值保持一致 */
private const val DEFAULT_TOP_N = 5

/** 饼图可选的 Top N。再多扇区就只剩颜色没有信息了 */
private val CATEGORY_TOP_N_CHOICES = listOf(3, 5, 7, 10)

/** 序列标签里「序列名 · 任务名」的分隔符 */
private const val TASK_LABEL_SEPARATOR = " · "

/**
 * 内置业务序列在配置里使用的 `sourceKey`。
 *
 * 它们不来自采集源，这个键只是为了让 `ChartSeriesConfig` 的必填字段有值；
 * 真正的分派依据是 `metricKey`（见 `BuiltinSeries.fromMetricKey`）。
 */
private const val BUILTIN_SOURCE_KEY = "BUILTIN"

/**
 * 配置界面可选的一条序列。
 *
 * @property optionKey 弹窗内比较选中状态用的唯一键。「源 + 指标」才能区分
 *   同一个插件下的步数与睡眠，只比 sourceKey 会让后一项显示成「已添加」
 * @property sourceKey 写进配置的数据源键
 * @property metricKey 写进配置的指标键
 * @property needsTask 该序列是否必须再选一条任务
 * @property group 选择器里的分组名。插件源显示插件名（「小米健康」），
 *   其余归到「原生」（见 [GROUP_NATIVE]）
 */
private data class SeriesOption(
    val optionKey: String,
    val sourceKey: String,
    val metricKey: String,
    val label: String,
    val unit: String,
    val needsTask: Boolean = false,
    val group: String = GROUP_NATIVE,
) {
    /** 选中后写进配置的序列。轴由调用方按顺序决定 */
    fun toSeriesConfig(axis: ChartAxis, taskId: Long?, taskLabel: String?): ChartSeriesConfig =
        ChartSeriesConfig(
            sourceKey = sourceKey,
            metricKey = metricKey,
            // 任务类序列把任务名带进图例，否则两条「单条规则完成率」在图上同名
            label = if (taskLabel.isNullOrBlank()) label else "$label · $taskLabel",
            unit = unit,
            axis = axis,
            taskId = taskId,
        )
}

/** 选择器里的一个分组。名字用来做展开状态的键，同一份数据里不会重名 */
private data class SeriesGroup(
    val name: String,
    val options: List<SeriesOption>,
)

/**
 * 把可选项按 [SeriesOption.group] 聚成组。
 *
 * 分组顺序 = 首次出现的顺序。`flattenCandidates` 已经按「内置/自建 → 插件」
 * 排过，`builtinOptions` 接在后面，所以「原生」一定排在最前，
 * 插件之间按加载顺序——与它们在插件列表页里的顺序一致，用户不用重新认一遍。
 *
 * 用 LinkedHashMap 而不是 `groupBy`：后者返回的 Map 也是插入有序的，
 * 但分组名到列表的映射关系不如直接写清楚好读。
 */
private fun groupOptions(options: List<SeriesOption>): List<SeriesGroup> {
    val byName = linkedMapOf<String, MutableList<SeriesOption>>()
    options.forEach { option ->
        byName.getOrPut(option.group) { mutableListOf() }.add(option)
    }
    return byName.map { (name, items) -> SeriesGroup(name, items) }
}

/** 非插件来源（内置源、自建源、内置业务序列）在图表数据源里的分组名 */
private val GROUP_NATIVE = SOURCE_GROUP_NATIVE

/**
 * 把所有数据源的**每一个指标**摊平成可选项。
 *
 * 为什么不按源只取第一项：一个插件可以声明多个指标（小米健康的步数与睡眠），
 * 只取 `metrics[0]` 会让其余指标在图表里永远选不到。
 * 内置源与自建源只有一个指标，摊平后自然还是一行。
 *
 * 分组名取自 `MetricCandidate.groupName`：插件源是插件展示名，
 * 内置/自建源是「原生」。标签不再带来源前缀——前缀已经由分组标题说了，
 * 每行再重复一遍只会把真正要看的指标名挤到后面。
 */
private fun flattenCandidates(candidates: List<MetricCandidate>): List<SeriesOption> =
    candidates.flatMap { candidate ->
        candidate.metrics.map { option ->
            val sourceKey = candidate.sourceKeyFor(option)
            val metricKey = option.metricKey.orEmpty()
            SeriesOption(
                optionKey = "$sourceKey|$metricKey",
                sourceKey = sourceKey,
                metricKey = metricKey,
                // 只有一个指标的源直接用源名（「屏幕时间」），
                // 与它作为指标卡时的叫法一致；多指标的源用指标名
                label = if (candidate.metrics.size > 1) option.label else candidate.displayName,
                unit = option.unit,
                group = candidate.groupName,
            )
        }
    }

/** 内置业务序列作为可选项。排在采集源之后：它们是派生数据，不是「采集到了什么」 */
private fun builtinOptions(): List<SeriesOption> =
    BuiltinSeries.entries.map { builtin ->
        SeriesOption(
            optionKey = "$BUILTIN_SOURCE_KEY|${builtin.metricKey}",
            sourceKey = BUILTIN_SOURCE_KEY,
            metricKey = builtin.metricKey,
            label = builtin.label,
            unit = builtin.unit,
            needsTask = builtin.needsTask,
        )
    }

/**
 * 保存前的校验。
 *
 * 与 `ChartCardConfig.isRenderable()` 的关系：那边是**数据层**的兜底（挡住绕过界面的调用），
 * 这里是**界面层**的解释（告诉用户为什么不能保存）。两处都要有：
 * 只靠数据层会让按钮点了没反应，只靠界面层则挡不住其他入口。
 *
 * 所以最后一定回到 `isRenderable()`：界面上说的「可以保存」必须与数据层放行的
 * 是同一件事，否则会出现「按钮亮着、点下去存不进」或者反过来。
 *
 * @return 不能保存的原因；可以保存时返回 null
 */
internal fun validateChartConfig(
    type: ChartType,
    config: ChartCardConfig,
    loading: Boolean,
): String? {
    if (loading) return "正在读取数据源"
    // 饼图只支持分类查询；不能通过增加序列绕过分类源校验。
    if (type == ChartType.PIE) {
        return if (config.isRenderable()) null else "分类数据源不可用"
    }
    val series = config.series
    if (series.isEmpty()) return "请至少选择一个指标"
    // 指标卡允许 metricKey 为空（表示用源的默认量纲），但图表配置里必须写明，
    // 否则同一源的两个指标无法区分
    if (series.any { it.sourceKey.isBlank() || it.metricKey.isBlank() }) {
        return "所选指标缺少必要信息，请重新选择"
    }
    // 内置序列里需要任务的，没有任务就算不出任何值
    if (series.any { option ->
            BuiltinSeries.fromMetricKey(option.metricKey)?.needsTask == true && option.taskId == null
        }
    ) {
        return "连击、单条规则完成率需要指定任务，请重新选择"
    }
    // 单序列形态：环形进度是一个当前值，热力图是一种深浅标度
    if ((type == ChartType.RING || type == ChartType.HEATMAP) && series.size > 1) {
        return "这个图表类型只能显示一个指标"
    }
    // 同一根轴上出现两种单位，刻度只能按其中一种画，另一条线的读数就是错的。
    // 这里是**唯一的**单位冲突检查：多指标本身不再有上限，冲突只可能出在轴上
    val conflict = axisUnitConflict(series)
    if (conflict != null) return conflict
    return if (config.isRenderable()) null else "这张图表的配置还不完整，请检查后再保存"
}

/**
 * 检查两根轴上有没有混入不同单位。
 *
 * @return 无法保存的原因；没有冲突时返回 null
 */
private fun axisUnitConflict(series: List<ChartSeriesConfig>): String? {
    ChartAxis.entries.forEach { axis ->
        val units = series
            .filter { it.axis == axis }
            // 空单位不参与判断：没有量纲的序列（比如计数）跟谁都能共处
            .map { it.unit }
            .filter { it.isNotBlank() }
            .distinct()
        if (units.size > 1) {
            val name = if (axis == ChartAxis.LEFT) "左轴" else "右轴"
            return "$name 上混了「${units.joinToString("」和「")}」，刻度只能按一种单位画。" +
                "请把它们分到左右两根轴上"
        }
    }
    return null
}

/** 图表类型的中文名。配置层有同一份（[com.lifeboard.shared.dashboard.displayName]），
 * 这里给的是九宫格里的短名，能少一个字就少一个字 */
private fun chartTypeLabel(type: ChartType): String = when (type) {
    ChartType.LINE -> "折线"
    ChartType.COLUMN -> "柱状"
    ChartType.AREA -> "面积"
    ChartType.SCATTER -> "散点"
    ChartType.PIE -> "饼图"
    ChartType.RING -> "环形进度"
    ChartType.HEATMAP -> "热力图"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 一组单选标签。
 *
 * 用等宽的列表而不是 Material 的 FilterChip：这里的选项都有固定含义，
 * 等宽排列能一眼看完，横着挤成两行反而更难认。
 *
 * @param items 选项值与该选项的展示名
 * @param chunkSize 每行几个。默认 3 是给「图表类型」这类九宫格用的；
 *   原地改序列的轴时用 2，否则「左轴/右轴」会被排成一行三个的怪样子
 * @param compact 紧凑模式：内边距与字号都收一档，用在密集的行内
 */
@Composable
private fun <T> FlowOfChips(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    chunkSize: Int = 3,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(chunkSize).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowItems.forEach { (value, label) ->
                    val isSelected = value == selected
                    Text(
                        text = label,
                        style = if (compact) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .background(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onSelect(value) }
                            .padding(
                                horizontal = if (compact) 8.dp else 12.dp,
                                vertical = if (compact) 4.dp else 6.dp,
                            ),
                    )
                }
            }
        }
    }
}

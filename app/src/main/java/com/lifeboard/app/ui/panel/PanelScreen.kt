package com.lifeboard.app.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.dashboard.DashboardViewModel
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassProgress
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.util.Dates
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.server.dashboard.CardType
import com.lifeboard.server.dashboard.DashboardCards
import com.lifeboard.server.db.DashboardCardEntity
import com.lifeboard.server.health.SourceHealthChecker
import com.lifeboard.server.metric.MetricCandidate
import com.lifeboard.server.metric.MetricQueryService
import com.lifeboard.server.metric.MetricReading
import com.lifeboard.server.metric.MetricResult
import com.lifeboard.server.metric.candidates
import com.lifeboard.server.metric.sourceKeyFor
import java.time.LocalDate

/**
 * 首页仪表盘。
 *
 * 从上到下的信息层次：今日总分 → 待办列表 → 本周趋势。
 * 总分放最上面，是因为它是这个应用唯一的"一眼结论"；
 * 任务行只展示判断所需的四项：名称、时段、当前/目标、连击。
 *
 * ## 卡片化（M6a）
 *
 * 四块内容各自包成一张卡片，由 [CardHost] 按类型分发。
 * **卡片内部一行没改**，只是把原来内联的参数换成显式入参——
 * 这样 M6b 加拖拽、增删、跨页签移动时，卡片的渲染逻辑不用再碰。
 *
 * 卡片不接收 ViewModel，只接收 state 切片和事件 lambda：
 * 一是避免「同一个 ViewModel 在多个页签各存一份状态」，
 * 二是让卡片在没有 ViewModel 的地方（比如预览、其他页签）也能渲染。
 */
@Composable
fun PanelScreen(
    tabId: String,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    /** 变化时重新判定数据源健康度（如刚完成小米账号登录） */
    refreshKey: Int = 0,
    vm: PanelViewModel = viewModel(),
    dashVm: DashboardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<TaskRow?>(null) }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) vm.refreshHealth()
    }

    // 卡片来自 dashboard_card 表。就绪前先用默认卡片顶一帧，
    // 否则冷启动会先闪一下「没有卡片」的空页签
    val dashState by dashVm.state.collectAsStateWithLifecycle()
    val storedCards by dashVm.observeCards(tabId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val cards = dashVm.cardsFor(tabId, dashState.ready, storedCards)

    // 指标卡的读数不在卡片列表里：列表来自 dashboard_tab/card，读数来自 metric 表，
    // 两者的刷新时机不同。这里在卡片列表变化或采集刷新时拉一次
    val metricStates by vm.metricStates.collectAsStateWithLifecycle()
    LaunchedEffect(tabId, storedCards, refreshKey) {
        vm.loadMetrics(tabId, storedCards)
    }

    // 编辑态：显示每张卡的操作按钮与底部「添加卡片」。
    // 不做默认常驻的拖拽手柄——这个页面天天用，常驻的编辑控件会把
    // 卡片间距撑开，视觉上和改造前就不再一致了（M6a 的验收标准）
    var editing by remember(tabId) { mutableStateOf(false) }
    var adding by remember(tabId) { mutableStateOf(false) }

    // 指标卡的第二层选择（先选源，再选指标）。与 adding 分开：
    // 第一层关掉之后再开这一层，两个弹窗不会同时存在
    var pickingMetric by remember(tabId) { mutableStateOf(false) }

    // 正在换绑数据源的那张指标卡。null 表示不在换绑。
    // 与 pickingMetric 分开：换绑针对一张已存在的卡，加卡针对「将要新增的卡」，
    // 两者共用一个弹窗但落点不同
    var rebindingCard by remember(tabId) { mutableStateOf<DashboardCardEntity?>(null) }

    // 「已存在类型」按卡片行本身算，而不是按上面渲染用的 CardType：
    // 加卡片的判重必须和库里的唯一索引看同一份事实
    val existingTypes = remember(storedCards) {
        storedCards.mapNotNull { DashboardCards.parseType(it.type) }
    }
    val addable = DashboardCards.addableTo(existingTypes)

    // 拖拽排序（M6.8）。只在编辑态生效，松手时一次性把结果写回库里
    val drag = rememberCardDrag(
        count = cards.size,
        onDrop = { from, to ->
            // 用「把这张卡挪到目标下标」而不是两两交换：
            // 从 0 拖到 3 是三个位置的关系，交换会让中间两张卡平白换位
            storedCards.getOrNull(from)?.let { row ->
                dashVm.moveCardTo(tabId, row.cardId, to)
            }
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "完成" else "调整卡片")
            }
        }

        if (cards.isEmpty()) {
            // 只有确实读完了才显示空态：未就绪时上面已经用默认卡片兜住
            EmptyTabHint(
                onAdd = { adding = true },
                // 用户页签才有「添加卡片」；面板被清空时同样可以加回去
                canAdd = DashboardCards.isCardTab(tabId) && addable.isNotEmpty(),
            )
        }

        cards.forEachIndexed { index, type ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .dragHandle(
                        enabled = editing,
                        index = index,
                        count = cards.size,
                        drag = drag,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (editing) {
                    // 卡片 id 从库里按顺序取：渲染列表和卡片行是同一个 order 排序的，
                    // 所以下标能对上。对不上（库里有一条认不出的类型）时不给操作按钮，
                    // 免得「上移」动到的是另一张卡
                    val row = storedCards.getOrNull(index)
                    CardEditBar(
                        title = DashboardCards.labelOf(type),
                        canMoveUp = index > 0,
                        canMoveDown = index < cards.lastIndex,
                        onMoveUp = { row?.let { dashVm.moveCard(tabId, it.cardId, -1) } },
                        onMoveDown = { row?.let { dashVm.moveCard(tabId, it.cardId, 1) } },
                        onDelete = { row?.let { dashVm.deleteCard(tabId, it.cardId) } },
                        enabled = row != null,
                    )
                }
                CardHost(
                    type = type,
                    state = state,
                    metricReading = storedCards.getOrNull(index)
                        ?.let { row -> metricStates.firstOrNull { it.cardId == row.cardId } },
                    // 指标卡可以点开换数据源（M6b2c）。其他类型传 null：
                    // 它们读的是固定位置的数据，没有「换源」这回事
                    onChangeMetricSource = if (type == CardType.METRIC) {
                        { storedCards.getOrNull(index)?.let { rebindingCard = it } }
                    } else {
                        null
                    },
                    onRefresh = { vm.reevaluate() },
                    onMarkDone = { vm.markDone(it) },
                    onEditTask = onEditTask,
                    onNewTask = onNewTask,
                    onToggle = { vm.toggleEnabled(it) },
                    onDelete = { pendingDelete = it },
                    // 只有系统权限类数据源会带 actionKey，宿主直接跳系统设置页
                    onOpenSourceSettings = { vm.openSourceSettings(it) },
                )
            }
        }

        // 编辑态才出现：没有任何卡可加时整块不显示，避免一个点不动的按钮
        if (editing && DashboardCards.isCardTab(tabId) && addable.isNotEmpty()) {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加卡片") }
        }

        // 提示文本不是卡片：它是一过性的状态说明，没有摆放/拖拽的意义
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (adding) {
        AddCardDialog(
            candidates = addable,
            onDismiss = { adding = false },
            onPick = { type ->
                if (type == CardType.METRIC) {
                    // 指标卡还要选源与指标，交给下一层弹窗。
                    // 先把当前弹窗关掉，否则两层叠在一起，返回键的退栈顺序会很怪
                    adding = false
                    pickingMetric = true
                } else {
                    dashVm.addCard(tabId, type) { ok ->
                        // 失败（撞唯一索引或被策略挡下）时把弹窗留在原地：
                        // 关掉再让用户重开一遍，等于什么都没说
                        if (ok) adding = false
                    }
                }
            },
        )
    }

    if (pickingMetric) {
        MetricSourcePickerDialog(
            onDismiss = { pickingMetric = false },
            onPick = { sourceKey, metricKey ->
                dashVm.addCard(tabId, CardType.METRIC, sourceKey, metricKey) { ok ->
                    if (ok) pickingMetric = false
                }
            },
        )
    }

    // 换绑：与加卡共用同一个选择器。disabled 里塞的是本页签已被占用的绑定，
    // 用户能看见「睡眠（已添加）」而不是点下去没反应
    rebindingCard?.let { card ->
        MetricSourcePickerDialog(
            title = "切换数据源",
            disabled = remember(storedCards, card.cardId) {
                storedCards
                    .filter { it.cardId != card.cardId && it.type == CardType.METRIC.name }
                    .mapNotNull { row ->
                        val source = row.sourceKey?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        source to row.metricKey?.takeIf { it.isNotBlank() }
                    }
                    .toSet()
            },
            onDismiss = { rebindingCard = null },
            onPick = { sourceKey, metricKey ->
                dashVm.rebindMetricCard(tabId, card.cardId, sourceKey, metricKey) { ok ->
                    if (ok) rebindingCard = null
                }
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("「${row.task.title}」的完成记录与连击也会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTask(row)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 卡片外壳：按类型分发到具体实现。
 *
 * M6a 阶段它**只做分发**，不加拖拽手柄、边框、内边距——
 * 任何额外的东西都会改变现有间距，而这一阶段的验收标准是「看起来一模一样」。
 * M6b 的编辑态（拖拽、删除按钮）从这里长出来。
 *
 * 卡片内部自行判断是否有内容：`SOURCE_HEALTH` 在没有断源时整张不渲染，
 * 与改造前 `if (brokenSources.isNotEmpty())` 的效果一致。
 */
@Composable
private fun CardHost(
    type: CardType,
    state: PanelUiState,
    metricReading: MetricCardState?,
    /** 指标卡的「换数据源」入口；非指标卡为 null */
    onChangeMetricSource: (() -> Unit)?,
    onRefresh: () -> Unit,
    onMarkDone: (TaskRow) -> Unit,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onToggle: (TaskRow) -> Unit,
    onDelete: (TaskRow) -> Unit,
    onOpenSourceSettings: (SourceHealthChecker.Entry) -> Unit,
) {
    when (type) {
        CardType.SCORE -> ScoreCard(state = state, onRefresh = onRefresh)

        CardType.TODO -> TodoCard(
            rows = state.rows,
            activeCount = state.activeRows.size,
            completedCount = state.completedCount,
            onMarkDone = onMarkDone,
            onEdit = { onEditTask(it.task.id) },
            onToggle = onToggle,
            onDelete = onDelete,
            onNewTask = onNewTask,
        )

        CardType.WEEK -> WeekCard(scores = state.weekScores)

        CardType.SOURCE_HEALTH -> {
            // 无断源时整张不渲染，保持与改造前一致
            if (state.brokenSources.isNotEmpty()) {
                SourceHealthCard(
                    entries = state.brokenSources,
                    onOpenSettings = onOpenSourceSettings,
                )
            }
        }

        CardType.METRIC -> MetricCard(
            reading = metricReading,
            onChangeSource = onChangeMetricSource,
        )
    }
}

/**
 * 指标卡（M6b2b）。
 *
 * 三种状态分开显示，因为它们的下一步动作完全不同：
 * - 有值：显示数字，非当天的值标出日期（「这是前天的」和「这是今天的」不是一回事）
 * - 没有数据：空状态 + 一句为什么，让用户知道该等等还是该去查源
 * - 源不见了：明确说出来，并提示这张卡需要重新绑定
 *   （这张卡绑的自建源被删了、插件被卸了——不是「还没采集到」）
 *
 * ## 为什么换源按钮放在卡内右上角而不是编辑态里
 *
 * 换数据源是「这张卡要显示什么」的问题，和「这张卡摆在哪」是两回事。
 * 放进编辑态的话，用户想换一个源要先点「调整卡片」，换完再点「完成」——
 * 而他此刻只是想看另一个指标。右上角本来就空着，放一个「换源」不占新的一行。
 *
 * @param onChangeSource 换绑入口；为 null 时（非指标卡场景）连按钮都不出现
 */
@Composable
private fun MetricCard(reading: MetricCardState?, onChangeSource: (() -> Unit)? = null) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        when (val result = reading?.result) {
            null -> {
                // 读数还没回来。用与卡片同高的一行占位，避免列表在读数到达时跳一下
                Text("指标", style = MaterialTheme.typography.titleSmall)
                Text(
                    "正在读取…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is MetricResult.Value -> {
                val value = result.reading
                MetricCardHeader(title = value.title, onChangeSource = onChangeSource)
                Text(
                    formatMetricValue(value),
                    style = MaterialTheme.typography.headlineMedium,
                )
                // 今天没数据时显示的是最近一次的值，必须标出来，
                // 否则用户会把昨天的步数当成今天的
                if (value.date != Dates.todayString()) {
                    Text(
                        "最近一次：${value.date}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            MetricResult.NoData -> {
                MetricCardHeader(title = "指标", onChangeSource = onChangeSource)
                Text(
                    "还没有数据。采集完成后这里会自动更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MetricResult.SourceUnavailable -> {
                MetricCardHeader(title = "指标", onChangeSource = onChangeSource)
                Text(
                    "这个数据源已经不存在了。点右上角「换源」重新选一个即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 指标卡的标题行：左边标题，右边「换源」。
 *
 * 抽出来是因为上面三种状态各自的标题文字不同，而换源按钮要一直都在——
 * 源已经不存在时反而更需要它，那是用户唯一的出路。
 */
@Composable
private fun MetricCardHeader(title: String, onChangeSource: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (onChangeSource != null) {
            TextButton(onClick = onChangeSource) { Text("换源") }
        }
    }
}

/**
 * 指标值的展示文本。
 *
 * FLAG 量纲显示成「完成 / 未完成」而不是 1 / 0：后者只有写代码的人看得懂。
 *
 * 睡眠单独换算成小时：库里存的是分钟，但「946.3 分钟」没人读得出来是几小时，
 * 而睡眠正好是最常被放上卡片的指标之一。口径与插件详情页的「全部数据」块一致，
 * 两处显示同一个数字不该有两种写法。
 *
 * 其余量纲复用 `PanelViewModel.formatValue` 的数字规整（整数值不拖小数点）。
 */
private fun formatMetricValue(reading: MetricReading): String = when (reading.bucket) {
    MetricBucket.FLAG -> if (reading.value > 0.0) "完成" else "未完成"
    MetricBucket.SLEEP_MINUTES -> "%.1f 小时".format(reading.value / 60.0)
    else -> PanelViewModel.formatValue(reading.value, reading.unit)
}

/**
 * 指标卡的数据源 + 指标选择器（M6b2b）。
 *
 * 源和指标分两屏：内置源与自建源各只有一个指标，第一屏直接落到指标上会显得多余；
 * 而插件源通常有多个（小米健康同时发步数与睡眠）。所以「选了源之后
 * if 只有一项就立刻选完，否则展开选指标」——内置源与自建源点一下即完成，
 * 插件源多一屏。
 *
 * ## 数据源的范围
 *
 * [MetricQueryService.candidates] 同时列出内置源、自建源与**已装的插件源**，
 * 所以这个弹窗既是「加指标卡」的第二步，也是「给已有卡换源」的唯一入口。
 * 插件源在第二屏按 `MetricSpec` 逐个展开（步数 / 睡眠…），
 * 一个插件里有几个指标就有几项可选。
 *
 * @param title 弹窗标题。加卡与换源用同一个选择器，但用户的心理动作不同，
 *   标题要跟着变，否则换源时看到「选择数据源」会以为是在加一张新卡
 * @param disabled 已被同页签其他指标卡占用的「源 + 指标」组合。这些项仍然展示，
 *   但标成「已添加」且点不动——直接隐藏会让人以为数据源列表里少了东西
 */
@Composable
private fun MetricSourcePickerDialog(
    onDismiss: () -> Unit,
    onPick: (sourceKey: String, metricKey: String?) -> Unit,
    title: String = "选择数据源",
    disabled: Set<Pair<String, String?>> = emptySet(),
) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<MetricCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<MetricCandidate?>(null) }

    LaunchedEffect(Unit) {
        candidates = MetricQueryService.candidates(context)
        loading = false
    }

    /** 某个候选指标是否已被本页签的其他卡占用 */
    fun isTaken(candidate: MetricCandidate, optionIndex: Int): Boolean {
        val option = candidate.metrics.getOrNull(optionIndex) ?: return false
        val source = candidate.sourceKeyFor(option)
        val metric = option.metricKey?.takeIf { it.isNotBlank() }
        return source to metric in disabled
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (expanded == null) title else "选择指标") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when {
                    loading -> Text(
                        "正在读取数据源…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 没有任何可读的源。最常见的是还没配好任何数据源，
                    // 这时说「去设置里添加」比显示一个空列表有用
                    candidates.isEmpty() -> Text(
                        "还没有可以读取的数据源。先到数据源设置里添加一个，再回来加指标卡。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 第二屏：这个源的指标。插件可有多个，内置/自建只有一项
                    expanded != null -> expanded!!.metrics.forEachIndexed { index, option ->
                        val candidate = expanded!!
                        val taken = isTaken(candidate, index)
                        TextButton(
                            onClick = {
                                onPick(candidate.sourceKeyFor(option), option.metricKey)
                            },
                            enabled = !taken,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                buildString {
                                    append(candidate.displayName)
                                    append(" · ")
                                    append(option.label)
                                    if (option.unit.isNotBlank()) append("（${option.unit}）")
                                    if (taken) append(" · 已添加")
                                },
                            )
                        }
                    }

                    else -> candidates.forEach { candidate ->
                        // 整组只有一个指标且已被占用时，这一项本身也不该可点
                        val allTaken = candidate.metrics.indices.all { isTaken(candidate, it) }
                        TextButton(
                            onClick = {
                                if (candidate.metrics.size == 1) {
                                    val only = candidate.metrics.first()
                                    onPick(candidate.sourceKeyFor(only), only.metricKey)
                                } else {
                                    expanded = candidate
                                }
                            },
                            enabled = !allTaken,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                buildString {
                                    val title = if (candidate.metrics.size == 1) {
                                        "${candidate.displayName} · " +
                                            candidate.metrics.first().label
                                    } else {
                                        // 多项时显示条数，让用户知道点进去还有选择
                                        "${candidate.displayName}（${candidate.metrics.size} 个指标）"
                                    }
                                    append(title)
                                    if (allTaken) append(" · 已添加")
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 第二屏要能退回第一屏，否则选错源只能整个关掉重来
            if (expanded != null) {
                TextButton(onClick = { expanded = null }) { Text("返回") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 卡片拖拽排序的状态（M6.8）。
 *
 * ## 为什么不用现成的 reorderable 库
 *
 * 计划里写明了不引三方拖拽库。而这个场景比通用库简单得多：
 * 列表是整页竖向滚动的固定几项、每项高度不同、只在编辑态可拖。
 *
 * ## 为什么要记每项高度
 *
 * 卡片高度不固定（待办卡随任务条数变高）。按「固定行高 × 下标」算当前位置，
 * 拖过一张待办卡就会跳到错误的槽位。所以拖拽开始时实测每项高度，
 * 再用累计高度判断当前落在第几个槽。
 *
 * ## 为什么拖拽期间要占位而不真的移动列表
 *
 * 真的把列表项调换位置会让被拖的那张卡在手指下瞬移，看着像 bug。
 * 这里的做法是：被拖的项原地不动、加位移跟随手指；其余项按「它会插到哪」
 * 实时让位。松手时才提交最终顺序。
 */
@Stable
private class CardDragState(
    private var onDrop: (from: Int, to: Int) -> Unit,
) {
    /** 正在拖的下标；null 表示没在拖 */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /** 手指相对按下点的竖向位移 */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** 当前算出来的目标槽位 */
    var targetIndex by mutableStateOf<Int?>(null)
        private set

    /** 每项的实测高度（含间距），拖拽开始时填 */
    private var itemHeights = mutableListOf<Float>()

    /** 每项的顶部位置，由高度累加得出 */
    private var itemTops: List<Float> = emptyList()

    /** 换掉松手时的回调，见 [rememberCardDrag] 里的说明 */
    fun updateDrop(block: (from: Int, to: Int) -> Unit) {
        onDrop = block
    }

    /** 卡片数变了，旧的实测高度不再对应任何一项 */
    fun resetMeasurements() {
        itemHeights = mutableListOf()
        itemTops = emptyList()
    }

    fun onDragStart(index: Int) {
        draggingIndex = index
        dragOffset = 0f
        targetIndex = index
    }

    /** 记录实测高度。在拖拽开始前调用（见 [dragHandle] 的 onGloballyPositioned） */
    fun recordItem(index: Int, height: Float) {
        while (itemHeights.size <= index) itemHeights.add(0f)
        itemHeights[index] = height
        rebuildTops()
    }

    private fun rebuildTops() {
        var acc = 0f
        itemTops = itemHeights.map { h -> acc.also { acc += h } }
    }

    fun onDrag(delta: Float) {
        dragOffset += delta
        val from = draggingIndex ?: return
        if (itemHeights.isEmpty()) return
        // 被拖项的中心落在哪个槽里，就算哪个位置。
        // 用中心而不是顶边：顶边要求用户拖过整整一张卡的高度才认，
        // 中心更接近「现在这张卡盖住了谁」
        val center = itemTops.getOrElse(from) { 0f } +
            itemHeights.getOrElse(from) { 0f } / 2f + dragOffset
        targetIndex = slotAt(center, from)
    }

    /** 中心点在 [y] 时落在第几个槽；越界夹到两端 */
    private fun slotAt(y: Float, fallback: Int): Int {
        if (itemTops.isEmpty()) return fallback
        itemTops.forEachIndexed { index, top ->
            val bottom = top + itemHeights.getOrElse(index) { 0f }
            if (y in top..bottom) return index
        }
        return if (y < 0f) 0 else itemHeights.lastIndex.coerceAtLeast(fallback)
    }

    /**
     * 某一项在屏幕上应该额外偏移多少。
     *
     * 被拖的那项跟随手指；其余项如果正好夹在「原位置」与「目标位置」之间，
     * 就朝反方向让出一格——这正是它松手后会变成的样子。
     */
    fun offsetFor(index: Int): Float {
        val from = draggingIndex ?: return 0f
        val to = targetIndex ?: return 0f
        if (index == from) return dragOffset
        val h = itemHeights.getOrElse(index) { 0f }
        return when {
            // 往后拖：中间这些项整体上移一格
            from < to && index in (from + 1)..to -> -h
            // 往前拖：中间这些项整体下移一格
            to < from && index in to until from -> h
            else -> 0f
        }
    }

    fun onDragEnd() {
        val from = draggingIndex
        val to = targetIndex
        draggingIndex = null
        targetIndex = null
        dragOffset = 0f
        // 位置没变就不落库：一次误触不应该产生一次写操作
        if (from != null && to != null && from != to) onDrop(from, to)
    }

    fun onDragCancel() {
        draggingIndex = null
        targetIndex = null
        dragOffset = 0f
    }

    fun isDragging(index: Int): Boolean = draggingIndex == index
}

@Composable
private fun rememberCardDrag(
    count: Int,
    onDrop: (from: Int, to: Int) -> Unit,
): CardDragState {
    val state = remember { CardDragState(onDrop) }
    // 回调闭包会随重组换新，但状态对象是 remember 住的。
    // 不更新的话松手时调用的还是第一次组合的那个 lambda，
    // 它捕获的 tabId / storedCards 可能已经过期
    state.updateDrop(onDrop)
    // 卡片数变化（删了一张）时丢掉旧的实测高度，否则槽位会算到不存在的位置
    LaunchedEffect(count) { state.resetMeasurements() }
    return state
}

/**
 * 给一张卡片接上长按拖动。
 *
 * 用 `Modifier.pointerInput` 而不是 `draggable`：需要在长按之后才开始跟手，
 * 在此之前的手势要留给卡片内部（展开待办、点按钮）。
 * `detectDragGesturesAfterLongPress` 正好是这个语义。
 *
 * 只在 [enabled] 时挂手势：非编辑态长按不应该有任何反应，
 * 否则用户按着卡片想滚动列表会突然开始拖。
 */
private fun Modifier.dragHandle(
    enabled: Boolean,
    index: Int,
    count: Int,
    drag: CardDragState,
): Modifier = if (!enabled) this else this
    .zIndex(if (drag.isDragging(index)) 1f else 0f)
    .graphicsLayer { translationY = drag.offsetFor(index) }
    .onGloballyPositioned { coords ->
        // 实测高度交给状态机。用尺寸而不是高度常量：
        // 待办卡的条数会变，固定常量会让位置越拖越偏
        drag.recordItem(index, coords.size.height.toFloat())
    }
    .pointerInput(index, count) {
        detectDragGesturesAfterLongPress(
            onDragStart = { drag.onDragStart(index) },
            onDrag = { change, amount ->
                change.consume()
                drag.onDrag(amount.y)
            },
            onDragEnd = { drag.onDragEnd() },
            onDragCancel = { drag.onDragCancel() },
        )
    }

/**
 * 空页签的提示与入口（M6b2a）。
 *
 * 之前这里只有一句「后续版本会支持添加卡片」，那是个死胡同：
 * 用户新建页签之后看到的就是它，而下一步该做什么页面没说。
 * 现在直接给按钮，点开就是加卡选择器。
 *
 * @param canAdd 可选卡片已经全在这个页签上了，或者这个页签根本不能放卡
 */
@Composable
private fun EmptyTabHint(onAdd: () -> Unit, canAdd: Boolean) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("这个页签还没有卡片", style = MaterialTheme.typography.titleSmall)
        Text(
            if (canAdd) {
                "可以从下面挑一张卡片放进来。"
            } else {
                "已有的卡片都放上了。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canAdd) {
            Button(onClick = onAdd) { Text("添加卡片") }
        }
    }
}

/**
 * 编辑态下压在每张卡上方的一条操作栏。
 *
 * 放在卡片外面而不是塞进 `GlassCard` 里：卡片内部的排版是 M6a 定死的，
 * 掺进编辑控件会让它在非编辑态也跟着变高，视觉上就不再和改造前一致。
 * 用「上移 / 下移」而不是拖拽：拖拽要做到能用（判定区、滚动冲突、边缘吸附）
 * 是 6.8 的独立任务，按钮是它之前唯一不会误导人的排序手段。
 */
@Composable
private fun CardEditBar(
    title: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onMoveUp, enabled = enabled && canMoveUp) { Text("上移") }
        TextButton(onClick = onMoveDown, enabled = enabled && canMoveDown) { Text("下移") }
        if (enabled) {
            TextButton(onClick = onDelete) { Text("移除") }
        }
    }
}

/**
 * 加卡选择器。
 *
 * 只列 [DashboardCards.OFFERABLE] 里的一类东西，不做二级分类：
 * 现在一共四个选项，加一层「数据源 → 指标」的树反而让人多点两下。
 * METRIC 卡要选的源和指标是 M6b2b 的事，那之后这里会多一个分支。
 */
@Composable
private fun AddCardDialog(
    candidates: List<CardType>,
    onDismiss: () -> Unit,
    onPick: (CardType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加卡片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (candidates.isEmpty()) {
                    Text(
                        "没有可添加的卡片了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                candidates.forEach { type ->
                    TextButton(
                        onClick = { onPick(type) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(DashboardCards.labelOf(type)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 今日得分卡。
 *
 * 与其他卡片用同一种 GlassCard 底色，不用 primaryContainer 另起一套颜色——
 * 之前它独自偏色，夹在几张普通卡中间显得很突兀，而且它并不是页面上唯一重点。
 * 强调只落在「数字」与「进度条」上，靠内容而不是底色区分主次。
 */
@Composable
private fun ScoreCard(state: PanelUiState, onRefresh: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("今日得分", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                trimScore(state.weightedScore),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "/ ${trimScore(state.totalWeight)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlassProgress(progress = state.completionRate, height = 8.dp)
        Text(
            "${state.date} · 完成 ${state.completedCount}/${state.activeRows.size}" +
                " · 最长连击 ${state.bestCurrentStreak} 天",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text(if (state.loading) "刷新中…" else "重新判定")
        }
    }
}

/**
 * 今日待办卡。
 *
 * 只接收列表与回调，不接 ViewModel：同一份数据可能被摆到多个页签，
 * 各自持有一份状态会导致「这边勾完那边没变」。
 */
@Composable
private fun TodoCard(
    rows: List<TaskRow>,
    activeCount: Int,
    completedCount: Int,
    onMarkDone: (TaskRow) -> Unit,
    onEdit: (TaskRow) -> Unit,
    onToggle: (TaskRow) -> Unit,
    onDelete: (TaskRow) -> Unit,
    onNewTask: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("今日待办", style = MaterialTheme.typography.titleSmall)
            Text(
                "$completedCount / $activeCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (rows.isEmpty()) {
            Text(
                "还没有任务，先建一个吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rows.forEach { row ->
                TaskItem(
                    row = row,
                    onMarkDone = { onMarkDone(row) },
                    onEdit = { onEdit(row) },
                    onToggle = { onToggle(row) },
                    onDelete = { onDelete(row) },
                )
                HorizontalDivider()
            }
        }

        Button(onClick = onNewTask) { Text("新建任务") }
    }
}

@Composable
private fun TaskItem(
    row: TaskRow,
    onMarkDone: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = row.task
    var expanded by remember { mutableStateOf(false) }
    val dimmed = !task.enabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (row.completed) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (dimmed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!task.enabled) Badge("已停用", MaterialTheme.colorScheme.outline)
                LevelBadge(task.remindLevel)
            }
            TextButton(onClick = { expanded = !expanded }) { Text("···") }
        }

        Text(
            "${PanelViewModel.formatMinute(task.windowStartMinute)}-" +
                "${PanelViewModel.formatMinute(task.windowEndMinute)}" +
                " · ${PanelViewModel.formatValue(row.value, task.metricBucket.unit)}" +
                " / ${PanelViewModel.formatValue(task.threshold, task.metricBucket.unit)}" +
                " · 权重 ${trimScore(task.weight)}" +
                if (row.currentStreak > 0) " · 连击 ${row.currentStreak} 天" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 关联了规则时，把规则的生效时间与达成状态直接写出来：
        // 用户不需要再去「规则」页对照，就能判断"这条规则今天到底有没有达成"
        if (row.ruleSummary.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "规则：${row.ruleSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Badge(
                    if (row.completed) "已达成" else "未达成",
                    if (row.completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        GlassProgress(progress = row.progress, height = 4.dp)

        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!row.completed) {
                    TextButton(onClick = onMarkDone) { Text("手动完成") }
                }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onToggle) { Text(if (task.enabled) "停用" else "启用") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun LevelBadge(level: RemindLevel) {
    val color = when (level) {
        RemindLevel.NONE -> MaterialTheme.colorScheme.outline
        RemindLevel.WEAK -> MaterialTheme.colorScheme.secondary
        RemindLevel.STRONG -> MaterialTheme.colorScheme.error
    }
    Badge(PanelViewModel.levelName(level), color)
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun WeekCard(scores: List<Pair<LocalDate, Double>>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("本周得分", style = MaterialTheme.typography.titleSmall)
        if (scores.isEmpty()) {
            Text(
                "本周暂无数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GlassCard
        }
        // 用 1.0 兜底，避免全为 0 时除零；柱高下限 2% 保证 0 分也能看到基线
        val max = scores.maxOf { it.second }.coerceAtLeast(1.0)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            scores.forEach { (date, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        trimScore(value),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .fillMaxWidth()
                            .height((88 * (value / max).toFloat().coerceIn(0.02f, 1f)).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (date == Dates.today()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                },
                            ),
                    )
                    Text(
                        WEEK_LABELS[date.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 数据源状态（M4.8）。
 *
 * 只列出需要用户知道的源：缺权限的给一个直接跳设置的按钮，
 * 二期未接入的明确标注"二期接入"，避免用户看到永远是 0 却以为是采集坏了。
 */
@Composable
private fun SourceHealthCard(
    entries: List<SourceHealthChecker.Entry>,
    onOpenSettings: (SourceHealthChecker.Entry) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text("数据源状态", style = MaterialTheme.typography.titleSmall)
        entries.forEach { entry ->
            val missing = entry.state == SourceHealthChecker.State.NO_PERMISSION
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (!missing) {
                            Badge("二期接入", MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (entry.actionKey != null) {
                    TextButton(onClick = { onOpenSettings(entry) }) { Text("去授权") }
                }
            }
        }
    }
}

/** 去掉无意义的小数位：3.0 → "3"，3.5 → "3.5" */
private fun trimScore(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

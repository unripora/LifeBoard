package com.lifeboard.app.ui.panel

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeboard.app.ui.dashboard.DashboardViewModel
import com.lifeboard.app.ui.theme.GlassCard
import com.lifeboard.app.ui.theme.GlassProgress
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.util.Dates
import com.lifeboard.server.dashboard.CardType
import com.lifeboard.server.dashboard.DashboardCards
import com.lifeboard.server.health.SourceHealthChecker
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

    // 编辑态：显示每张卡的操作按钮与底部「添加卡片」。
    // 不做默认常驻的拖拽手柄——这个页面天天用，常驻的编辑控件会把
    // 卡片间距撑开，视觉上和改造前就不再一致了（M6a 的验收标准）
    var editing by remember(tabId) { mutableStateOf(false) }
    var adding by remember(tabId) { mutableStateOf(false) }

    // 「已存在类型」按卡片行本身算，而不是按上面渲染用的 CardType：
    // 加卡片的判重必须和库里的 (tabId, type) 唯一索引看同一份事实
    val existingTypes = remember(storedCards) {
        storedCards.mapNotNull { DashboardCards.parseType(it.type) }
    }
    val addable = DashboardCards.addableTo(existingTypes)

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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                dashVm.addCard(tabId, type) { ok ->
                    // 失败（撞唯一索引或被策略挡下）时把弹窗留在原地：
                    // 关掉再让用户重开一遍，等于什么都没说
                    if (ok) adding = false
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

        // METRIC 卡在 M6b 实现。这里不给兜底渲染，
        // 免得布局里残留一个空壳占位
        CardType.METRIC -> Unit
    }
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

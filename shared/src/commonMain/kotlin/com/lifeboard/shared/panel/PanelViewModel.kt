package com.lifeboard.shared.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.dashboard.CardType
import com.lifeboard.shared.dashboard.ChartAxis
import com.lifeboard.shared.dashboard.ChartConfigCodec
import com.lifeboard.shared.dashboard.ChartType
import com.lifeboard.shared.platform.PlatformDependencies
import com.lifeboard.shared.platform.remindLevelName
import com.lifeboard.shared.repository.model.DashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 面板上的一行任务：任务配置 + 今日记录 + 连击。
 */
data class TaskRow(
    val task: TaskConfig,
    val record: DailyRecord?,
    val streak: StreakInfo?,
    /** 关联规则的说明（如「每日 · 09:00–09:30」），未关联时为空 */
    val ruleSummary: String = "",
) {
    val completed: Boolean get() = record?.completed == true
    val value: Double get() = record?.value ?: 0.0

    /** 进度 0..1，阈值非法时按 0 处理，避免除零 */
    val progress: Float
        get() = if (task.threshold <= 0.0) 0f
        else (value / task.threshold).toFloat().coerceIn(0f, 1f)

    val currentStreak: Int get() = streak?.currentStreak ?: 0
    val bestStreak: Int get() = streak?.bestStreak ?: 0
}

/**
 * 面板上的一张指标卡的读数（M6b2b）。
 *
 * 卡片列表本身来自 `DashboardViewModel`，这里只负责「每张卡读出来是多少」。
 * 两者分开是因为读数的时机完全不同：列表在布局变化时变，读数在采集完成后变。
 *
 * @property tabId 这张卡在哪个页签里，用来把同一页签的卡一次读完
 */
data class MetricCardState(
    val cardId: String,
    val tabId: String,
    val result: MetricResult,
)

/**
 * 面板上的一张图表卡的渲染输入。
 *
 * 与 [MetricCardState] 并列而不是合并：图表读的是整段时间窗，
 * 指标卡读的是单点值，两者的失败模式与刷新时机都不同。
 *
 * @property model 已经整形好的渲染模型；null 表示配置损坏，无法绘制
 */
data class ChartCardState(
    val cardId: String,
    val tabId: String,
    val model: ChartRenderModel?,
    /** 图表形态。渲染器据此决定用哪套画法 */
    val chartType: ChartType = ChartType.LINE,
    /** 分类占比，仅饼图使用 */
    val categories: CategoryBreakdown? = null,
    /** 环形进度的目标值；null 表示没有可用目标 */
    val ringTarget: Double? = null,
    val configError: Boolean = false,
)

/**
 * 首页仪表盘状态。
 *
 * [weightedScore] 是"今日已得权重分"，[totalWeight] 是"今日所有启用任务的总权重"，
 * 两者相除即完成度。分开存是为了让界面既能显示 x/y 分，也能显示百分比。
 */
data class PanelUiState(
    val date: String = Dates.todayString(),
    val loading: Boolean = true,
    val rows: List<TaskRow> = emptyList(),
    val weightedScore: Double = 0.0,
    val totalWeight: Double = 0.0,
    val weekScores: List<Pair<LocalDate, Double>> = emptyList(),
    /** 有问题的数据源（缺权限 / 二期未接入），空列表表示全部正常 */
    val brokenSources: List<SourceHealthEntry> = emptyList(),
    val message: String = "",
) {
    val completionRate: Float
        get() = if (totalWeight <= 0.0) 0f else (weightedScore / totalWeight).toFloat()

    val completedCount: Int get() = rows.count { it.completed }
    val activeRows: List<TaskRow> get() = rows.filter { it.task.enabled }
    val bestCurrentStreak: Int get() = rows.maxOfOrNull { it.currentStreak } ?: 0
}

/**
 * 首页仪表盘 ViewModel（M3.2 / M3.4）。
 *
 * 三个数据源（任务、今日记录、连击）用 combine 合成一个状态流，
 * 任何一处变化——包括后台 Worker 写入的记录——都会自动刷新界面，不需要手动轮询。
 */
class PanelViewModel(
    private val deps: PlatformDependencies,
) : ViewModel() {

    /**
     * 面板业务层的数据与副作用入口。
     *
     * 由装配点提供，ViewModel 不关心它是 Android 的
     * `SharedPreferences` + `AlarmManager` 还是桌面的其他实现。
     */
    private val scope = deps.panelScope

    private val repo = TaskRepository(scope)

    private val _message = MutableStateFlow("")
    private val _weekScores = MutableStateFlow<List<Pair<LocalDate, Double>>>(emptyList())
    private val _refreshing = MutableStateFlow(true)
    private val _brokenSources = MutableStateFlow<List<SourceHealthEntry>>(emptyList())

    /** 规则 id → 生效摘要，任务行据此显示「规则：每日 · 09:00–09:30」 */
    private val _ruleSummaries = MutableStateFlow<Map<Long, String>>(emptyMap())

    /**
     * 指标卡的读数。独立于 [state]：读一张卡要解析数据源、可能还要查插件注册表，
     * 塞进那个 8 路 combine 会让整页每次都跟着重算，而面板平时压根没有指标卡。
     */
    private val _metricStates = MutableStateFlow<List<MetricCardState>>(emptyList())
    val metricStates: StateFlow<List<MetricCardState>> = _metricStates.asStateFlow()

    /**
     * 图表卡的渲染输入。与 [metricStates] 一样独立于 [state]：
     * 图表要读整段时间窗，比单个读数重得多，不能让整页 Combine 跟着重算。
     */
    private val _chartStates = MutableStateFlow<List<ChartCardState>>(emptyList())
    val chartStates: StateFlow<List<ChartCardState>> = _chartStates.asStateFlow()

    private val today get() = Dates.todayString()

    val state: StateFlow<PanelUiState> = combine(
        repo.observeTasks(),
        repo.observeRecords(today),
        repo.observeStreaks(),
        _weekScores,
        _message,
        _refreshing,
        _brokenSources,
        _ruleSummaries,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tasks = values[0] as List<TaskConfig>
        @Suppress("UNCHECKED_CAST")
        val records = values[1] as Map<Long, DailyRecord>
        @Suppress("UNCHECKED_CAST")
        val streaks = values[2] as Map<Long, StreakInfo>
        @Suppress("UNCHECKED_CAST")
        val week = values[3] as List<Pair<LocalDate, Double>>
        val message = values[4] as String
        val refreshing = values[5] as Boolean
        @Suppress("UNCHECKED_CAST")
        val broken = values[6] as List<SourceHealthEntry>
        @Suppress("UNCHECKED_CAST")
        val summaries = values[7] as Map<Long, String>

        val rows = tasks.map { t ->
            TaskRow(
                task = t,
                record = records[t.id],
                streak = streaks[t.id],
                ruleSummary = summaries[t.ruleId] ?: "",
            )
        }
        val active = rows.filter { it.task.enabled }
        PanelUiState(
            loading = refreshing,
            rows = rows,
            weightedScore = active.sumOf { it.record?.score ?: 0.0 },
            totalWeight = active.sumOf { it.task.weight },
            weekScores = week,
            brokenSources = broken,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PanelUiState(),
    )

    init {
        reloadWeek()
        reloadHealth()
        reloadRuleSummaries()
        // 首次进入先跑一次判定，保证刚从后台采集到的指标立刻反映成达成状态
        reevaluate(silent = true)
    }

    /** 读取规则摘要，让任务行能显示"关联的是哪条规则、什么时候生效" */
    private fun reloadRuleSummaries() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                runCatching {
                    scope.ruleStore.getAllRules()
                        .associate { it.id to it.schedule.summary() }
                }.getOrDefault(emptyMap())
            }
            _ruleSummaries.value = map
        }
    }

    /** 打开某个断源对应的设置页 */
    fun openSourceSettings(entry: SourceHealthEntry): Boolean {
        val key = entry.actionKey ?: return true
        deps.sourceHealth.openSettings(key)
        return true
    }

    /** 重新判定数据源健康度。登录小米账号后调用，让面板立刻反映最新授权状态 */
    fun refreshHealth() {
        reloadHealth()
    }

    /** 重新判定今日全部任务并刷新本周得分 */
    fun reevaluate(silent: Boolean = false) {
        viewModelScope.launch {
            _refreshing.value = true
            val count = withContext(Dispatchers.IO) {
                runCatching { repo.reevaluateAll(today) }.getOrDefault(-1)
            }
            reloadWeekSuspend()
            reloadHealth()
            _refreshing.value = false
            if (!silent) {
                _message.value = if (count >= 0) "已重新判定 $count 个任务" else "判定失败"
            }
        }
    }

    /** 手动打卡：把当日记录标记为达成，采集源已有数据时不覆盖 */
    fun markDone(row: TaskRow) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { repo.manualMark(row.task, today) }.getOrDefault(false)
            }
            _message.value = if (ok) {
                "「${row.task.title}」已记为完成"
            } else {
                "今天已采集到真实数据，请以自动判定为准"
            }
            reloadWeekSuspend()
        }
    }

    /** 删除任务 */
    fun deleteTask(row: TaskRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteTask(row.task.id) }
            _message.value = "已删除「${row.task.title}」"
            reloadWeekSuspend()
        }
    }

    /** 启用/停用任务 */
    fun toggleEnabled(row: TaskRow) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.setEnabled(row.task, !row.task.enabled)
            }
            _message.value =
                if (row.task.enabled) "「${row.task.title}」已停用" else "「${row.task.title}」已启用"
        }
    }

    fun consumeMessage() {
        _message.value = ""
    }

    private fun reloadWeek() {
        viewModelScope.launch { reloadWeekSuspend() }
    }

    /**
     * 读一遍某个页签里的指标卡。
     *
     * 由界面在卡片列表变化或 `refreshKey` 变化时调用，不自己挂观察：
     * `metric` 表没有暴露 Flow（采集是批量 upsert，逐行 emit 会把主线程刷爆），
     * 拉取式读一次反而更可控。
     *
     * 读失败（源被删、插件卸了）不抛到这里，`MetricQueryService` 会折成
     * [MetricResult.SourceUnavailable]，卡片自己显示该说的话。
     */
    fun loadMetrics(tabId: String, cards: List<DashboardCard>) {
        val metricCards = cards.filter { it.type == CardType.METRIC.name }
        if (metricCards.isEmpty()) {
            _metricStates.value = _metricStates.value.filterNot { it.tabId == tabId }
            return
        }
        viewModelScope.launch {
            val today = Dates.todayString()
            val states = withContext(Dispatchers.IO) {
                metricCards.map { card ->
                    MetricCardState(
                        cardId = card.cardId,
                        tabId = tabId,
                        result = MetricQueryService(scope).readCard(card, today),
                    )
                }
            }
            // 只替换本页签的部分：别的页签的读数还留在列表里，
            // 切回去时不用再等一次采集
            _metricStates.value = _metricStates.value.filterNot { it.tabId == tabId } + states
        }
    }

    /**
     * 读一遍某个页签里的图表卡。
     *
     * 每张卡按自己的配置取时间窗与序列，读取失败（源没了、配置坏了）不抛，
     * 折成 [ChartCardState] 里的状态让卡片自己说明原因。
     */
    fun loadCharts(tabId: String, cards: List<DashboardCard>) {
        val chartCards = cards.filter { it.type == CardType.CHART.name }
        if (chartCards.isEmpty()) {
            _chartStates.value = _chartStates.value.filterNot { it.tabId == tabId }
            return
        }
        viewModelScope.launch {
            val today = Dates.todayString()
            val states = withContext(Dispatchers.IO) {
                chartCards.map { card -> readChartCard(tabId, card, today) }
            }
            _chartStates.value = _chartStates.value.filterNot { it.tabId == tabId } + states
        }
    }

    /**
     * 读一张图表卡。
     *
     * 配置解析失败与数据读取失败分开处理：前者是「这张卡坏了，需要改配置」，
     * 后者是「源没了或没数据」，用户要做的动作不一样。
     */
    private suspend fun readChartCard(
        tabId: String,
        card: DashboardCard,
        today: String,
    ): ChartCardState {
        val config = ChartConfigCodec.decode(card.configJson)
            ?: return ChartCardState(cardId = card.cardId, tabId = tabId, model = null, configError = true)

        // 饼图读分类数据，不走时间序列：两者是不同的查询路径
        if (config.chartType == ChartType.PIE) {
            val categories = ChartQueryService(scope).readCategoryBreakdown(
                sourceKey = config.categorySourceKey.orEmpty(),
                topN = config.topN ?: 5,
                days = config.timeWindowDays,
                today = today,
            )
            return ChartCardState(
                cardId = card.cardId,
                tabId = tabId,
                model = null,
                chartType = ChartType.PIE,
                categories = categories,
            )
        }

        val loaded = config.series.map { series ->
            ChartQueryService(scope).readConfiguredSeries(
                sourceKey = series.sourceKey,
                metricKey = series.metricKey,
                label = series.label,
                unit = series.unit,
                days = config.timeWindowDays,
                today = today,
                taskId = series.taskId,
            )
        }
        // 顺序必须与 loaded 一一对应：渲染模型按顺序配对轴，
        // 同一个指标可以带不同任务、挂不同轴各出现一次
        val requested = config.series.map {
            RequestedSeries(
                label = it.label,
                unit = it.unit,
                axis = if (it.axis == ChartAxis.RIGHT) ChartAxisSlot.RIGHT else ChartAxisSlot.LEFT,
            )
        }
        // 环形进度要目标值：复用任务规则里的阈值。只有单序列形态才需要，
        // 多序列各自一个目标没有可比性
        val ringTarget = if (config.chartType == ChartType.RING) {
            val first = config.series.firstOrNull()
            if (first == null) {
                null
            } else {
                val tasks = scope.taskStore.getAll()
                resolveRingTarget(first.sourceKey, first.metricKey, tasks)
            }
        } else {
            null
        }
        return ChartCardState(
            cardId = card.cardId,
            tabId = tabId,
            model = buildChartRenderModel(requested, loaded, showLegend = config.showLegend),
            chartType = config.chartType,
            ringTarget = ringTarget,
        )
    }

    /**
     * 刷新数据源健康度，只保留需要用户处理的两类：
     * 缺权限（可以去设置里补）和二期未接入（无法处理，但要让人知道为什么是 0）。
     */
    private fun reloadHealth() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { deps.sourceHealth.probeAll() }.getOrDefault(emptyList())
            }
            _brokenSources.value = entries.filter {
                it.state == SourceHealthState.NO_PERMISSION ||
                    it.state == SourceHealthState.NOT_IMPLEMENTED
            }
        }
    }

    private suspend fun reloadWeekSuspend() {
        val todayDate = Dates.today()
        val scores = withContext(Dispatchers.IO) {
            repo.dailyScores(Dates.weekStart(todayDate), todayDate)
        }
        _weekScores.value = scores
    }

    companion object {
        /** 分钟数转 HH:mm，用于时段展示 */
        fun formatMinute(minuteOfDay: Int): String =
            "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

        fun levelName(level: RemindLevel): String = remindLevelName(level)

        /** 指标值格式化：整数不带小数，小数保留一位 */
        fun formatValue(value: Double, unit: String): String {
            val number = if (value == value.toInt().toDouble()) {
                value.toInt().toString()
            } else {
                "%.1f".format(value)
            }
            return if (unit.isEmpty()) number else "$number $unit"
        }
    }
}

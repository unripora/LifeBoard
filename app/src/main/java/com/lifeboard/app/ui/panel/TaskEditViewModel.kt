package com.lifeboard.app.ui.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.model.TimeSource
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.server.panel.TaskRepository
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 每个数据源一期的可用分桶，避免用户选出采集不到的组合 */
private val SOURCE_BUCKETS: Map<SourceType, List<MetricBucket>> = mapOf(
    SourceType.SCREEN_TIME to listOf(MetricBucket.DURATION_MINUTES),
    SourceType.FEISHU_ATTENDANCE to listOf(MetricBucket.FLAG, MetricBucket.COUNT),
    SourceType.DUOLINGO to listOf(MetricBucket.COUNT, MetricBucket.FLAG),
    SourceType.MI_HEALTH to listOf(MetricBucket.STEPS, MetricBucket.SLEEP_MINUTES),
    SourceType.LEGADO to listOf(MetricBucket.DURATION_MINUTES),
    SourceType.READEST to listOf(MetricBucket.DURATION_MINUTES),
    SourceType.QC_BOOK_LOG to listOf(MetricBucket.DURATION_MINUTES),
    SourceType.MANUAL to listOf(MetricBucket.FLAG),
)

/**
 * 任务编辑页状态。
 *
 * 只保留"用户能改的字段"，id 用于区分新建与编辑。
 * 校验结果放在 [error] 里由界面直接展示，不做抛异常式校验。
 */
data class TaskEditUiState(
    val id: Long = 0L,
    val title: String = "",
    val source: SourceRef = SourceRef.Builtin(SourceType.SCREEN_TIME),
    val metricBucket: MetricBucket = MetricBucket.DURATION_MINUTES,
    val threshold: String = "30",
    val weight: String = "1",
    val remindLevel: RemindLevel = RemindLevel.WEAK,
    /** 完成提醒档位，与未完成提醒相互独立；默认不提醒 */
    val doneRemindLevel: RemindLevel = RemindLevel.NONE,
    /** 完成时是否弹应用内小浮窗 */
    val doneToastEnabled: Boolean = true,
    /** 未达成提醒相对时段结束的偏移（分钟），-60..60 */
    val windowOffsetMinutes: Int = 5,
    /** 生效规则：关联了采集规则时由规则决定，这里只在未关联时兜底 */
    val schedule: ScheduleRule = ScheduleRule(
        windows = listOf(TimeWindow(0, 23 * 60 + 30)),
    ),
    /** 关联的采集规则 id，0 表示不关联（此时用 schedule 兜底） */
    val ruleId: Long = 0L,
    /** 时间来源：跟随规则 or 任务自己定 */
    val timeSource: TimeSource = TimeSource.FOLLOW_RULE,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
    val done: Boolean = false,
    /** 加载完成时的快照，用来判断用户这次有没有真的改过东西 */
    val snapshot: TaskEditUiState? = null,
) {
    /**
     * 是否有未保存的改动。
     *
     * 编辑页拿加载快照比对；新建页没有快照，就和空白默认值比对，
     * 否则用户填完名字直接退出，内容会静默丢失。
     */
    val dirty: Boolean
        get() = !sameContent(snapshot ?: TaskEditUiState())

    private fun sameContent(other: TaskEditUiState): Boolean =
        title == other.title &&
            source == other.source &&
            metricBucket == other.metricBucket &&
            threshold == other.threshold &&
            weight == other.weight &&
            remindLevel == other.remindLevel &&
            doneRemindLevel == other.doneRemindLevel &&
            doneToastEnabled == other.doneToastEnabled &&
            windowOffsetMinutes == other.windowOffsetMinutes &&
            schedule == other.schedule &&
            ruleId == other.ruleId &&
            timeSource == other.timeSource &&
            enabled == other.enabled

    /** 当前数据源支持的分桶 */
    val availableBuckets: List<MetricBucket>
        get() = (source as? SourceRef.Builtin)?.let { SOURCE_BUCKETS[it.type] } ?: listOf(MetricBucket.FLAG)

    val unit: String get() = metricBucket.unit

    /** 兼容旧界面：首个时段的起止时间 */
    val windowStart: Int get() = schedule.effectiveWindows.first().startMinute

    val windowEnd: Int get() = schedule.effectiveWindows.first().endMinute
}

/** 可关联的规则（带应用名，给选择列表展示） */
data class RuleOption(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val sourceName: String,
    /** 规则写入的量纲；任务关联后量纲跟它走，不再单独配 */
    val bucketName: String,
    val summary: String,
    /** 规则名称里的识别关键词，用于两级选择时区分同一应用下的多条规则 */
    val keywords: String = "",
)

/**
 * 一个应用及其名下的规则。
 *
 * 数据源不能平铺成「源」列表：同一个应用（如飞书）下往往挂着多条规则
 * （上班打卡、下班打卡、消息回复……），平铺后用户既看不出归属，
 * 也容易在后续加规则时选错。所以选择器按「应用 → 规则」两级展开。
 */
data class AppRuleGroup(
    val packageName: String,
    val appLabel: String,
    val rules: List<RuleOption>,
)

/**
 * 任务编辑 ViewModel（M3.3）。
 *
 * 切换数据源时自动把分桶纠正到该源支持的选项，否则会存出"读不到数据的任务"。
 */
class TaskEditViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaskRepository(app)

    private val _state = MutableStateFlow(TaskEditUiState())
    val state: StateFlow<TaskEditUiState> = _state.asStateFlow()

    /** 可关联的采集规则列表 */
    private val _rules = MutableStateFlow<List<RuleOption>>(emptyList())
    val rules: StateFlow<List<RuleOption>> = _rules.asStateFlow()

    /** 按应用分组后的规则，数据源选择器用它做「应用 → 规则」两级展开 */
    val ruleGroups: StateFlow<List<AppRuleGroup>> = combine(_rules, _state) { rules, state ->
        groupByApp(rules, state.ruleId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val list = withContext(Dispatchers.IO) {
                LifeBoardDatabase.get(context).captureRuleDao().getEnabled().map { rule ->
                    RuleOption(
                        id = rule.id,
                        packageName = rule.packageName,
                        appLabel = runCatching {
                            val pm = context.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(rule.packageName, 0)).toString()
                        }.getOrDefault(rule.packageName),
                        sourceName = rule.sourceName,
                        bucketName = rule.bucketName,
                        summary = rule.toRuleSchedule().summary(),
                        keywords = rule.includeKeywords,
                    )
                }
            }
            _rules.value = list
        }
    }

    /**
     * 按应用分组，当前已关联的应用排最前。
     *
     * 排序目标不是"好看"，而是让正在编辑的任务一眼看到自己用的那条规则：
     * 规则多了以后，用户回来改任务时最想确认的就是「我现在绑的是哪条」。
     */
    private fun groupByApp(rules: List<RuleOption>, ruleId: Long): List<AppRuleGroup> {
        val current = rules.firstOrNull { it.id == ruleId }
        return rules.groupBy { it.packageName }.map { (pkg, list) ->
            AppRuleGroup(
                packageName = pkg,
                appLabel = list.first().appLabel,
                rules = list,
            )
        }.sortedWith(
            compareByDescending<AppRuleGroup> { it.packageName == current?.packageName }
                .thenBy { it.appLabel },
        )
    }

    /**
     * 选择关联规则：数据源、量纲、时间全部跟着规则走。
     *
     * 量纲只有规则一个事实来源——任务页不再单独配，
     * 否则「规则写 FLAG、任务读 COUNT」这种两边对不上的配置静默产生一个永远是 0 的任务。
     */
    fun setRule(option: RuleOption?) {
        val current = _state.value
        _state.value = if (option == null) {
            current.copy(ruleId = 0L, error = "")
        } else {
            val resolved = SourceRegistry.resolveSyncOrNull(getApplication(), option.sourceName)
            current.copy(
                ruleId = option.id,
                source = resolved ?: current.source,
                metricBucket = runCatching { MetricBucket.valueOf(option.bucketName) }
                    .getOrDefault(current.metricBucket),
                error = "",
            )
        }
    }

    /** 编辑已有任务，id 为 0 时不做任何事 */
    fun load(taskId: Long) {
        if (taskId == 0L) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val task = withContext(Dispatchers.IO) { repo.getTask(taskId) }
            if (task == null) {
                _state.value = _state.value.copy(loading = false, error = "任务不存在")
                return@launch
            }
            val loaded = TaskEditUiState(
                id = task.id,
                title = task.title,
                source = task.source,
                metricBucket = task.metricBucket,
                threshold = trimNumber(task.threshold),
                weight = trimNumber(task.weight),
                remindLevel = task.remindLevel,
                doneRemindLevel = task.doneRemindLevel,
                doneToastEnabled = task.doneToastEnabled,
                windowOffsetMinutes = task.windowOffsetMinutes,
                schedule = task.schedule,
                ruleId = task.ruleId,
                timeSource = task.timeSource,
                enabled = task.enabled,
            )
            // 旧任务可能存有与规则不一致的量纲（导致任务永远为 0），加载时按规则纠正
            val corrected = if (task.ruleId != 0L) {
                val rule = withContext(Dispatchers.IO) {
                    LifeBoardDatabase.get(getApplication()).captureRuleDao().getById(task.ruleId)
                }
                loaded.copy(
                    metricBucket = rule?.let {
                        runCatching { MetricBucket.valueOf(it.bucketName) }.getOrDefault(loaded.metricBucket)
                    } ?: loaded.metricBucket,
                )
            } else {
                loaded
            }
            // 快照取纠正后的状态：纠正属于加载行为，不该被当成"用户改了东西"
            _state.value = corrected.copy(loading = false, snapshot = corrected)
        }
    }

    /**
     * 放弃编辑，把状态还原成加载时的样子。
     *
     * 编辑器 ViewModel 跟着 Activity 级的作用域走，不重置的话
     * 下次点「新建任务」会看到上次丢弃的内容。
     */
    fun reset() {
        _state.value = _state.value.snapshot ?: TaskEditUiState()
    }

    /**
     * 页面关闭后清掉一次性的完成标志。
     *
     * 必须做：`done` 一旦置 true 就再没人动过，而 [TaskEditScreen] 里
     * `LaunchedEffect(state.done) { if (state.done) onDone() }` 会在每次重新进入时
     * 立刻命中——表现为「点编辑进不去、闪一下就退回」，而且 taskId 相同时
     * `LaunchedEffect(taskId)` 不会重跑，问题更明显。
     */
    fun consumeDone() {
        if (_state.value.done) _state.value = _state.value.copy(done = false)
    }

    fun setTitle(value: String) {
        _state.value = _state.value.copy(title = value, error = "")
    }

    fun setSource(value: SourceRef) {
        val current = _state.value
        // 分桶必须跟着数据源走，切源后原分桶多半不适用
        val buckets = (value as? SourceRef.Builtin)?.let { SOURCE_BUCKETS[it.type] } ?: listOf(MetricBucket.FLAG)
        val bucket = if (buckets.contains(current.metricBucket)) current.metricBucket else buckets.first()
        _state.value = current.copy(source = value, metricBucket = bucket, error = "")
    }

    fun setBucket(value: MetricBucket) {
        _state.value = _state.value.copy(metricBucket = value)
    }

    fun setThreshold(value: String) {
        _state.value = _state.value.copy(threshold = value, error = "")
    }

    fun setWeight(value: String) {
        _state.value = _state.value.copy(weight = value, error = "")
    }

    fun setRemindLevel(value: RemindLevel) {
        _state.value = _state.value.copy(remindLevel = value)
    }

    fun setDoneRemindLevel(value: RemindLevel) {
        _state.value = _state.value.copy(doneRemindLevel = value)
    }

    fun setDoneToastEnabled(value: Boolean) {
        _state.value = _state.value.copy(doneToastEnabled = value)
    }

    /** 偏移超出 -60..60 直接夹住，避免滑到无效区间 */
    fun setWindowOffset(value: Int) {
        _state.value = _state.value.copy(windowOffsetMinutes = value.coerceIn(-60, 60))
    }

    fun setEnabled(value: Boolean) {
        _state.value = _state.value.copy(enabled = value)
    }

    /**
     * 切换时间来源。
     *
     * 从「跟随规则」切到「自定义时间」时不动 schedule——它本来就是任务自己的时段，
     * 之前只是被规则盖住了。反过来切回规则模式时也保留 schedule，
     * 用户随时可以切回来，不用重配。
     */
    fun setTimeSource(value: TimeSource) {
        _state.value = _state.value.copy(timeSource = value, error = "")
    }

    /** 时段调整，步进 30 分钟；作用在首个时段上（多时段在任务页暂不暴露） */
    fun shiftStart(deltaMinutes: Int) {
        val s = _state.value
        val first = s.schedule.effectiveWindows.first()
        val next = (first.startMinute + deltaMinutes).coerceIn(0, first.endMinute - 30)
        _state.value = s.copy(
            schedule = s.schedule.copy(windows = listOf(first.copy(startMinute = next))),
            error = "",
        )
    }

    fun shiftEnd(deltaMinutes: Int) {
        val s = _state.value
        val first = s.schedule.effectiveWindows.first()
        val next = (first.endMinute + deltaMinutes).coerceIn(first.startMinute + 30, 24 * 60 - 1)
        _state.value = s.copy(
            schedule = s.schedule.copy(windows = listOf(first.copy(endMinute = next))),
            error = "",
        )
    }

    fun save() {
        val s = _state.value
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.value = s.copy(error = "任务名称不能为空")
            return
        }
        val threshold = s.threshold.toDoubleOrNull()
        if (threshold == null || threshold <= 0.0) {
            _state.value = s.copy(error = "阈值需为大于 0 的数字")
            return
        }
        val weight = s.weight.toDoubleOrNull()
        if (weight == null || weight <= 0.0) {
            _state.value = s.copy(error = "权重需为大于 0 的数字")
            return
        }
        if (s.windowStart >= s.windowEnd) {
            _state.value = s.copy(error = "检测时段结束必须晚于开始")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = "")
            val first = s.schedule.effectiveWindows.first()
            val task = TaskConfig(
                id = s.id,
                title = title,
                source = s.source,
                metricBucket = s.metricBucket,
                threshold = threshold,
                weight = weight,
                remindLevel = s.remindLevel,
                windowStartMinute = first.startMinute,
                windowEndMinute = first.endMinute,
                schedule = s.schedule,
                ruleId = s.ruleId,
                timeSource = s.timeSource,
                enabled = s.enabled,
                doneRemindLevel = s.doneRemindLevel,
                doneToastEnabled = s.doneToastEnabled,
                windowOffsetMinutes = s.windowOffsetMinutes,
            )
            val ok = withContext(Dispatchers.IO) {
                runCatching { repo.saveTask(task) }.isSuccess
            }
            _state.value = if (ok) {
                _state.value.copy(saving = false, done = true)
            } else {
                _state.value.copy(saving = false, error = "保存失败，请重试")
            }
        }
    }

    companion object {
        /** 3.0 → "3"，3.5 → "3.5"，让输入框不显示多余小数 */
        fun trimNumber(value: Double): String =
            if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString()
    }
}

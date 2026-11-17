package com.lifeboard.shared.rules

import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.platform.RulesBackend
import com.lifeboard.shared.repository.RuleStore
import com.lifeboard.shared.repository.SourceKeys
import com.lifeboard.shared.repository.model.CaptureRuleRecord
import com.lifeboard.shared.repository.model.CustomSourceRecord
import com.lifeboard.shared.repository.sqldelight.SqldelightMappers.toRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 规则列表页状态 */
data class RulesUiState(
    val rules: List<CaptureRuleRecord> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    /** 源 key → 今日计数 */
    val counts: Map<String, Double> = emptyMap(),
    /** 通知使用权（Android）是否已授权；桌面端恒为 false */
    val listenerEnabled: Boolean = false,
    /** 读屏采集服务是否已启用；桌面端恒为 false */
    val captureEnabled: Boolean = false,
    val message: String = "",
) {
    /** 采集通道是否至少开了一个——两个都没开时规则不会产生任何数据 */
    val channelReady: Boolean get() = listenerEnabled || captureEnabled

    val enabledCount: Int get() = rules.count { it.enabled }

    /** 按应用分组，键是标识符（包名或程序路径） */
    val groups: List<Pair<String, List<CaptureRuleRecord>>>
        get() = rules.groupBy { it.packageName }.toList().sortedBy { it.first }
}

/**
 * 规则页的状态持有者（M4-4.6）。
 *
 * ## 为什么不是 ViewModel
 *
 * 这一份要同时跑在 Android（有 `ViewModelStoreOwner`）和桌面端（没有）。
 * 继承 `androidx.lifecycle.ViewModel` 会把它锁死在 Android 的依赖上，
 * 桌面端就得再造一个「假 owner」。改成普通类 + 外部传入 [CoroutineScope]：
 * Android 传 `viewModelScope`、桌面传窗口作用域的 scope，生命周期语义都由调用方决定。
 *
 * ## 与「通知源」调试页的分工
 *
 * 这里只负责规则的增删改与生效状态。授权引导、原始流水、匹配测试留在调试页——
 * 那些是排查工具，混在正式功能页里会让界面越来越杂。
 *
 * @param scope 承载异步操作的协程作用域，生命周期由调用方掌管
 */
class RulesController(
    private val ruleStore: RuleStore,
    private val backend: RulesBackend,
    private val scope: CoroutineScope,
    private val onState: (RulesUiState) -> Unit,
) {

    private var state = RulesUiState()

    private fun update(block: (RulesUiState) -> RulesUiState) {
        state = block(state)
        onState(state)
    }

    fun start() {
        scope.launch {
            ruleStore.observeRules().collect { list ->
                update { it.copy(rules = list) }
                refreshCounts()
            }
        }
        scope.launch {
            ruleStore.observeCustomSources().collect {
                loadSources()
                refreshCounts()
            }
        }
        refresh()
    }

    fun refresh() {
        refreshPermissions()
        loadSources()
        refreshCounts()
    }

    private fun refreshPermissions() {
        scope.launch {
            val listener = runCatching { backend.isNotificationListenerEnabled() }.getOrDefault(false)
            val capture = runCatching { backend.isCaptureServiceEnabled() }.getOrDefault(false)
            update { it.copy(listenerEnabled = listener, captureEnabled = capture) }
        }
    }

    private fun loadSources() {
        scope.launch {
            val list = runCatching { backend.selectableSources() }.getOrDefault(emptyList())
            update { it.copy(sources = list) }
        }
    }

    private fun refreshCounts() {
        scope.launch {
            val counts = runCatching {
                ruleStore.getAllRules()
                    .map { it.sourceName to it.bucketName }
                    .distinct()
                    .associate { (src, bucket) -> src to backend.todayCount(src, bucket) }
            }.getOrDefault(emptyMap())
            update { it.copy(counts = counts) }
        }
    }

    /**
     * 解析一条规则引用的数据源，供编辑页回填。
     *
     * 读路径要「尽量给个能显示的」：自建源被删掉时 [SourceKeys.resolve]
     * 会兜底成 MANUAL 并给出原因，这里连原因一起返回，
     * 由界面决定要不要提示——数字一直为 0 时靠这条线索才能查出根因。
     */
    suspend fun resolveSource(key: String): SourceResolveResult = runCatching {
        val customs = ruleStore.getCustomSources().map { it.toRef() }
        val lookup = SourceKeys.CustomSourceLookup { id -> customs.firstOrNull { it.id == id } }
        when (val resolved = SourceKeys.resolve(key, lookup)) {
            is SourceKeys.Resolved.Ok -> SourceResolveResult(resolved.ref, null)
            is SourceKeys.Resolved.Fallback -> SourceResolveResult(resolved.ref, resolved.reason)
        }
    }.getOrElse { SourceResolveResult(null, it.message) }

    /** 一次数据源解析的结果；[warning] 非空表示走了兜底 */
    data class SourceResolveResult(val ref: SourceRef?, val warning: String?)

    /**
     * 打开编辑页：先解析数据源再回填草稿。
     *
     * 解析是 `suspend`（要查自建源表），所以由控制器起协程而不是由界面 `LaunchedEffect`——
     * 界面那边每重绘一次就会重跑一次解析，而解析结果只取决于规则本身。
     */
    fun loadEditState(rule: CaptureRuleRecord, onReady: (RuleEditUiState) -> Unit) {
        scope.launch {
            val resolved = resolveSource(rule.sourceName)
            onReady(buildEditState(rule, resolved.ref))
        }
    }

    fun saveRule(rule: CaptureRuleRecord) {
        scope.launch {
            runCatching { ruleStore.saveRule(rule) }
                .onFailure { return@launch update { s -> s.copy(message = "保存失败：${it.message}") } }
            // 规则变了，Android 端无障碍服务订阅的包名要跟着变，否则新加的应用收不到事件；
            // 桌面端没有采集服务，两个调用都是空实现
            runCatching { backend.notifyRulesChanged() }
            runCatching { backend.rescheduleReminders() }
            update { it.copy(message = "规则已保存") }
        }
    }

    fun deleteRule(id: Long) {
        scope.launch {
            val rule = runCatching { ruleStore.getRule(id) }.getOrNull()
            if (rule == null) {
                update { it.copy(message = "规则已不存在") }
                return@launch
            }
            runCatching { ruleStore.deleteRule(id) }
                .onFailure { return@launch update { s -> s.copy(message = "删除失败：${it.message}") } }
            runCatching { backend.notifyRulesChanged() }
            runCatching { backend.rescheduleReminders() }
            update { it.copy(message = "规则已删除") }
        }
    }

    fun toggleRule(rule: CaptureRuleRecord) {
        saveRule(rule.copy(enabled = !rule.enabled))
    }

    /** 清空各源今日计数，便于反复调试规则 */
    fun clearToday() {
        scope.launch {
            runCatching {
                ruleStore.getAllRules()
                    .map { it.sourceName to it.bucketName }
                    .distinct()
                    .forEach { (source, bucket) -> backend.clearTodayCount(source, bucket) }
            }
            update { it.copy(message = "已清空今日计数") }
            refreshCounts()
        }
    }

    // ---- 自定义数据源 ----

    fun createCustomSource(name: String, bucket: MetricBucket) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            update { it.copy(message = "数据源名称不能为空") }
            return
        }
        scope.launch {
            runCatching {
                ruleStore.saveCustomSource(
                    CustomSourceRecord(displayName = trimmed, bucket = bucket.name),
                )
            }.onFailure {
                return@launch update { s -> s.copy(message = "新建失败：${it.message}") }
            }
            update { it.copy(message = "已新建数据源「$trimmed」") }
        }
    }

    /**
     * 删除自建源。
     *
     * 有规则还在引用时拒绝删除：删掉后那些规则仍指向 `CUSTOM_x`，
     * 解析会回落到手动打卡，表现为「规则还在但计数跑到别处去了」，极难排查。
     */
    fun deleteCustomSource(ref: SourceRef.Custom) {
        scope.launch {
            val refs = runCatching { backend.referenceCount(ref.key) }.getOrDefault(0)
            if (refs > 0) {
                update {
                    it.copy(message = "「${ref.displayName}」还有 $refs 条规则在用，先改掉它们再删")
                }
                return@launch
            }
            runCatching { ruleStore.deleteCustomSource(ref.id) }
                .onFailure { return@launch update { s -> s.copy(message = "删除失败：${it.message}") } }
            update { it.copy(message = "已删除数据源「${ref.displayName}」") }
        }
    }
}

/**
 * 规则编辑页的草稿状态。
 *
 * 单独一个类而不是塞进 [RulesController]：编辑页有一份「草稿」，
 * 和列表状态混在一起时，草稿变化会连带触发列表重组，滚动位置都会跳。
 */
data class RuleEditUiState(
    val id: Long = 0L,
    /** 平台内应用标识：Android 是包名，Windows 是可执行文件路径 */
    val packageName: String = "",
    val source: SourceRef? = null,
    val bucket: MetricBucket = MetricBucket.FLAG,
    val includeKeywords: String = "",
    val excludeKeywords: String = "",
    val timeRegex: String = "",
    val windows: List<TimeWindow> = listOf(TimeWindow(0, 24 * 60)),
    val startDate: String = "",
    val endDate: String = "",
    val repeatType: RepeatType = RepeatType.DAILY,
    val weekdays: Set<Int> = emptySet(),
    val anchorDay: Int = 1,
    val anchorMonth: Int = 1,
    val dailyOnce: Boolean = true,
    val enabled: Boolean = true,
    /** 应用显示名，仅用于界面回显；空则回落到 [packageName] */
    val appLabel: String = "",
    val error: String = "",
) {
    val schedule: ScheduleRule
        get() = ScheduleRule(
            windows = windows,
            startDate = startDate,
            endDate = endDate,
            repeatType = repeatType,
            weekdays = weekdays,
            anchorDay = anchorDay,
            anchorMonth = anchorMonth,
        )
}

/** 草稿 → 记录 */
fun RuleEditUiState.toRecord(): CaptureRuleRecord {
    val first = windows.firstOrNull() ?: TimeWindow(0, 24 * 60)
    return CaptureRuleRecord(
        id = id,
        packageName = packageName,
        sourceName = source?.key.orEmpty(),
        bucketName = bucket.name,
        includeKeywords = includeKeywords,
        excludeKeywords = excludeKeywords,
        timeRegex = timeRegex,
        // 旧字段保留并同步第一个时段，任何还没升级的读取方都不会读到矛盾数据
        windowStartMinute = first.startMinute,
        windowEndMinute = first.endMinute,
        dailyOnce = dailyOnce,
        enabled = enabled,
        windows = ScheduleRule.encodeWindows(windows),
        startDate = startDate,
        endDate = endDate,
        repeatType = repeatType.name,
        weekdayMask = ScheduleRule.encodeWeekdays(weekdays),
        anchorDay = anchorDay,
        anchorMonth = anchorMonth,
    )
}

/** 记录 → 草稿（编辑已有规则时用） */
fun buildEditState(
    rule: CaptureRuleRecord,
    resolved: SourceRef?,
    appLabel: String = "",
): RuleEditUiState {
    val schedule = rule.schedule
    return RuleEditUiState(
        id = rule.id,
        packageName = rule.packageName,
        source = resolved,
        bucket = MetricBucket.entries.firstOrNull { it.name == rule.bucketName }
            ?: MetricBucket.FLAG,
        includeKeywords = rule.includeKeywords,
        excludeKeywords = rule.excludeKeywords,
        timeRegex = rule.timeRegex,
        windows = schedule.effectiveWindows,
        startDate = schedule.startDate,
        endDate = schedule.endDate,
        repeatType = schedule.repeatType,
        weekdays = schedule.weekdays,
        anchorDay = schedule.anchorDay,
        anchorMonth = schedule.anchorMonth,
        dailyOnce = rule.dailyOnce,
        enabled = rule.enabled,
        appLabel = appLabel,
    )
}

/**
 * 保存前校验：只拦真正会让规则失效的问题。
 *
 * 不校验关键词为空——「该应用任何内容都算」是合法配置。
 */
fun RuleEditUiState.validate(): String? = when {
    packageName.isBlank() -> "先选择要应用"
    source == null -> "先选择数据源"
    windows.isEmpty() -> "至少保留一个生效时段"
    startDate.isNotBlank() && endDate.isNotBlank() && startDate > endDate ->
        "开始日期不能晚于结束日期"
    repeatType == RepeatType.WEEKLY && weekdays.isEmpty() -> "每周规则需要选择具体星期"
    else -> null
}

/** 关键词拆分。两处（规则列表摘要、匹配引擎）必须用同一套拆法，否则显示与实际命中不一致 */
fun splitRuleKeywords(raw: String): List<String> =
    raw.split(',', '，', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** 计数显示：整数不带小数点 */
fun formatRuleCount(value: Double): String =
    if (value == value.toInt().toDouble()) value.toInt().toString() else "%.1f".format(value)

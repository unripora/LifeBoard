package com.lifeboard.app.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.schedule.RepeatType
import com.lifeboard.core.schedule.ScheduleRule
import com.lifeboard.core.schedule.TimeWindow
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.db.CustomSourceEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.notice.CaptureRuleMatcher
import com.lifeboard.server.notice.NoticeListenerService
import com.lifeboard.server.notice.NoticeSourceStatus
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService
import com.lifeboard.server.notice.toRuleSchedule
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 规则列表页状态 */
data class RulesUiState(
    val rules: List<CaptureRuleEntity> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    /** 源 key → 今日计数 */
    val counts: Map<String, Double> = emptyMap(),
    val listenerEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val message: String = "",
)

/**
 * 规则页 ViewModel。
 *
 * 与「通知源」调试页的分工：这里只负责规则的增删改与生效状态，
 * 授权引导、原始流水、匹配测试留在调试页——那些是排查工具，
 * 混在正式功能页里会让界面越来越杂。
 */
class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RulesUiState())
    val state: StateFlow<RulesUiState> = _state.asStateFlow()

    private val db get() = LifeBoardDatabase.get(getApplication())

    init {
        viewModelScope.launch {
            db.captureRuleDao().observeAll().collect { list ->
                _state.value = _state.value.copy(rules = list)
                refreshCounts()
            }
        }
        viewModelScope.launch {
            db.customSourceDao().observeAll().collect {
                loadSources()
                refreshCounts()
            }
        }
        refreshPermissions()
    }

    fun refresh() {
        refreshPermissions()
        loadSources()
        refreshCounts()
    }

    private fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            listenerEnabled = NoticeListenerService.isEnabled(ctx),
            accessibilityEnabled = ScreenCaptureAccessibilityService.isEnabled(ctx),
        )
    }

    private fun loadSources() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { SourceRegistry.selectable(getApplication()) }
            _state.value = _state.value.copy(sources = list)
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val rules = db.captureRuleDao().getAll()
                rules.map { it.sourceName to it.bucketName }.distinct().associate { (src, bucket) ->
                    src to NoticeSourceStatus.todayCount(getApplication(), src, bucket)
                }
            }
            _state.value = _state.value.copy(counts = counts)
        }
    }

    fun saveRule(rule: CaptureRuleEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.captureRuleDao().upsert(rule) }
            // 规则变了，无障碍服务订阅的包名要跟着变，否则新加的应用收不到事件
            ScreenCaptureAccessibilityService.notifyRulesChanged()
            runCatching { RemindScheduler.rescheduleAll(getApplication()) }
            _state.value = _state.value.copy(message = "规则已保存")
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            val refs = withContext(Dispatchers.IO) { db.taskDao().countByRule(id) }
            if (refs > 0) {
                _state.value = _state.value.copy(
                    message = "还有 $refs 个任务关联这条规则，先改掉它们再删",
                )
                return@launch
            }
            withContext(Dispatchers.IO) { db.captureRuleDao().deleteById(id) }
            ScreenCaptureAccessibilityService.notifyRulesChanged()
            runCatching { RemindScheduler.rescheduleAll(getApplication()) }
            _state.value = _state.value.copy(message = "规则已删除")
        }
    }

    fun toggleRule(rule: CaptureRuleEntity) {
        saveRule(rule.copy(enabled = !rule.enabled))
    }

    /** 清空某源今日计数，便于反复测试规则 */
    fun clearToday() {
        viewModelScope.launch {
            val date = Dates.todayString()
            withContext(Dispatchers.IO) {
                runCatching {
                    db.captureRuleDao().getAll()
                        .map { it.sourceName to it.bucketName }
                        .distinct()
                        .forEach { (source, bucket) ->
                            db.metricDao().upsert(
                                MetricEntity(sourceType = source, date = date, bucket = bucket, value = 0.0),
                            )
                        }
                }
            }
            _state.value = _state.value.copy(message = "已清空今日计数")
            refreshCounts()
        }
    }

    // ---- 自定义数据源 ----

    fun createCustomSource(name: String, bucket: MetricBucket) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(message = "数据源名称不能为空")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.customSourceDao().upsert(
                    CustomSourceEntity(displayName = trimmed, bucket = bucket.name),
                )
            }
            _state.value = _state.value.copy(message = "已新建数据源「$trimmed」")
        }
    }

    /**
     * 删除自建源。
     *
     * 有规则还在引用时拒绝删除：删掉后那些规则仍指向 `CUSTOM_x`，
     * 解析会回落到手动打卡，表现为"规则还在但计数跑到别处去了"，极难排查。
     */
    fun deleteCustomSource(ref: SourceRef.Custom) {
        viewModelScope.launch {
            val refs = withContext(Dispatchers.IO) { SourceRegistry.referenceCount(getApplication(), ref.key) }
            if (refs > 0) {
                _state.value = _state.value.copy(
                    message = "「${ref.displayName}」还有 $refs 条规则在用，先改掉它们再删",
                )
                return@launch
            }
            withContext(Dispatchers.IO) { db.customSourceDao().deleteById(ref.id) }
            _state.value = _state.value.copy(message = "已删除数据源「${ref.displayName}」")
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = "")
    }
}

/**
 * 规则编辑页状态与操作。
 *
 * 单独一个类而不是塞进 [RulesViewModel]：编辑页有一份「草稿」，
 * 和列表状态混在一起时，草稿变化会连带触发列表重组，滚动位置都会跳。
 */
data class RuleEditUiState(
    val id: Long = 0L,
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
    val error: String = "",
    val done: Boolean = false,
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

/** 草稿 → 实体 */
fun RuleEditUiState.toEntity(): CaptureRuleEntity {
    val first = windows.firstOrNull() ?: TimeWindow(0, 24 * 60)
    return CaptureRuleEntity(
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

/** 规则实体 → 草稿（编辑已有规则时用） */
fun buildEditState(rule: CaptureRuleEntity, resolved: SourceRef?): RuleEditUiState {
    val schedule = rule.toRuleSchedule()
    return RuleEditUiState(
        id = rule.id,
        packageName = rule.packageName,
        source = resolved,
        bucket = MetricBucket.entries.firstOrNull { it.name == rule.bucketName } ?: MetricBucket.FLAG,
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
    )
}

/** 供外部构造空规则时使用的默认数据源 */
val defaultRuleSource: SourceRef get() = builtinRef(SourceType.FEISHU_ATTENDANCE)

/** 关键词拆分复用匹配引擎的实现，避免两处规则不一致 */
fun splitRuleKeywords(raw: String): List<String> = CaptureRuleMatcher.splitKeywords(raw)

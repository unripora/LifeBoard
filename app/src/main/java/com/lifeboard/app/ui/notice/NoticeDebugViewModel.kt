package com.lifeboard.app.ui.notice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.CaptureRuleEntity
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.MetricEntity
import com.lifeboard.server.notice.CaptureRuleMatcher
import com.lifeboard.server.notice.NoticeListenerService
import com.lifeboard.server.notice.NoticeSourceStatus
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** 测试匹配结果 */
data class MatchResult(
    val packageName: String,
    val content: String,
    val matchedSources: List<String>,
)

/** 原始流水的一行 */
data class RawItem(
    val packageName: String,
    val title: String,
    val text: String,
    val matchedSources: String,
    val postedAt: Long,
)

data class NoticeDebugUiState(
    val listenerEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val rules: List<CaptureRuleEntity> = emptyList(),
    val counts: Map<String, Double> = emptyMap(),
    val raws: List<RawItem> = emptyList(),
    val samplePackage: String = "",
    val sampleContent: String = "",
    val matchResult: MatchResult? = null,
    val message: String = "",
)

/**
 * 采集规则与流水的调试 ViewModel。
 *
 * 规则全部来自用户配置（`capture_rule` 表），不再有代码里写死的关键词。
 * 这个页面负责三件事：看授权状态、看真实流水、把真实文案粘进来验证规则。
 */
class NoticeDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(NoticeDebugUiState())
    val state: StateFlow<NoticeDebugUiState> = _state.asStateFlow()

    private val db get() = LifeBoardDatabase.get(getApplication())

    init {
        refresh()
        viewModelScope.launch {
            // 流水与规则都用 Flow 观察，改完规则立刻能验证，不用手动刷新
            db.rawNotificationDao().observeRecent().collect { list ->
                _state.value = _state.value.copy(
                    raws = list.map {
                        RawItem(
                            packageName = it.packageName,
                            title = it.title,
                            text = it.text,
                            matchedSources = it.matchedSources,
                            postedAt = it.postedAt,
                        )
                    },
                )
                // 有新流水说明采集通道刚写过库，计数大概率也变了，跟着刷新，
                // 否则用户看到「已命中」但今日仍是 0，会误以为没生效
                refreshCounts()
            }
        }
        viewModelScope.launch {
            db.captureRuleDao().observeAll().collect { list ->
                _state.value = _state.value.copy(rules = list)
                refreshCounts()
            }
        }
    }

    /** 只刷新今日计数，不动授权状态（开销小，可随流水变化频繁调用） */
    private suspend fun refreshCounts() {
        val context = getApplication<Application>()
        val counts = withContext(Dispatchers.IO) {
            val rules = db.captureRuleDao().getAll()
            rules.map { it.sourceName to it.bucketName }
                .distinct()
                .associate { (source, bucket) ->
                    source to NoticeSourceStatus.todayCount(context, source, bucket)
                }
        }
        _state.value = _state.value.copy(counts = counts)
    }

    /** 清空原始流水（只影响校准数据，不动今日计数） */
    fun clearRaws() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { db.rawNotificationDao().clear() } }
            _state.value = _state.value.copy(message = "已清空原始流水")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _state.value = _state.value.copy(
                listenerEnabled = NoticeListenerService.isEnabled(context),
                accessibilityEnabled = ScreenCaptureAccessibilityService.isEnabled(context),
            )
            refreshCounts()
        }
    }

    fun setSamplePackage(pkg: String) {
        _state.value = _state.value.copy(samplePackage = pkg, matchResult = null, message = "")
    }

    fun setSampleContent(value: String) {
        _state.value = _state.value.copy(sampleContent = value, matchResult = null)
    }

    /** 用输入的内容跑一遍规则匹配，不写库 */
    fun testMatch() {
        val s = _state.value
        viewModelScope.launch {
            val rules = withContext(Dispatchers.IO) {
                db.captureRuleDao().getEnabledByPackage(s.samplePackage)
            }
            val now = LocalDateTime.now()
            val hits = CaptureRuleMatcher.match(rules, s.sampleContent, now)
            _state.value = s.copy(
                matchResult = MatchResult(
                    packageName = s.samplePackage,
                    content = s.sampleContent,
                    matchedSources = hits.map { it.rule.sourceName },
                ),
                message = when {
                    s.samplePackage.isBlank() -> "先选择要测试的应用"
                    rules.isEmpty() -> "该应用还没有启用的规则"
                    hits.isEmpty() -> "没有命中：检查关键词、否定词或时段是否拦住了"
                    else -> "命中 ${hits.size} 条规则"
                },
            )
        }
    }

    /** 清空某源今日计数，便于反复测试 */
    fun clearToday() {
        viewModelScope.launch {
            val date = Dates.todayString()
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val rules = db.captureRuleDao().getAll()
                    rules.map { it.sourceName to it.bucketName }
                        .distinct()
                        .forEach { (source, bucket) ->
                            db.metricDao().upsert(
                                MetricEntity(
                                    sourceType = source,
                                    date = date,
                                    bucket = bucket,
                                    value = 0.0,
                                ),
                            )
                        }
                    "已清空今日计数"
                }.getOrElse { "清空失败：${it.message}" }
            }
            _state.value = _state.value.copy(message = msg)
            refresh()
        }
    }

    // ---- 规则增删改 ----

    fun saveRule(rule: CaptureRuleEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.captureRuleDao().upsert(rule) }
            // 无障碍服务把规则缓存在内存里（rulesByPackage），不通知它就一直用旧的。
            // 漏掉这一步的后果是：删掉的规则仍在命中、改了的关键词不生效。
            ScreenCaptureAccessibilityService.notifyRulesChanged()
            _state.value = _state.value.copy(message = "规则已保存")
            refresh()
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.captureRuleDao().deleteById(id) }
            // 同上：不同步的话，已删除的规则会在无障碍通道里继续匹配。
            ScreenCaptureAccessibilityService.notifyRulesChanged()
            _state.value = _state.value.copy(message = "规则已删除")
            refresh()
        }
    }

    fun toggleRule(rule: CaptureRuleEntity) {
        saveRule(rule.copy(enabled = !rule.enabled))
    }

    /** 可选的源名：沿用既有 SourceType，方便面板按源聚合 */
    val availableSources: List<SourceType> = SourceType.entries
        .filter { it != SourceType.SCREEN_TIME && it != SourceType.MANUAL }
}

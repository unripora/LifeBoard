package com.lifeboard.shared.metrics

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.shared.panel.MetricCandidate
import com.lifeboard.shared.panel.MetricQueryService
import com.lifeboard.shared.panel.PanelScope
import com.lifeboard.shared.panel.SOURCE_GROUP_CUSTOM
import com.lifeboard.shared.panel.SOURCE_GROUP_NATIVE
import com.lifeboard.shared.panel.SourceResolver
import com.lifeboard.shared.panel.candidates
import com.lifeboard.shared.repository.model.CustomSourceRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 一个分组下的一个指标，已经带上当天读数。
 *
 * @property sourceKey 数据源 key，与图表卡片里存的是同一套
 * @property metricKey 插件指标才有；内置源与自建源为 null
 * @property valueText 已格式化的展示值。没有数据时是「暂无数据」而不是「0」
 */
data class MetricItem(
    val sourceKey: String,
    val metricKey: String?,
    val displayName: String,
    val metricLabel: String,
    val unit: String,
    val valueText: String,
    val hasData: Boolean,
)

/** 一个分组（原生 / 自定义 / 某插件） */
data class MetricsGroup(
    val name: String,
    val items: List<MetricItem>,
)

data class MetricsUiState(
    val loading: Boolean = true,
    val groups: List<MetricsGroup> = emptyList(),
    /** 折叠中的分组名。默认全展开，所以这里存的是「被折叠掉的」 */
    val collapsedGroups: Set<String> = emptySet(),
    val today: String = LocalDate.now().toString(),
) {
    /** 展开中的分组 = 全部 - 折叠的。反着存是为了「新出现的组默认展开」不用额外处理 */
    val expandedGroups: Set<String>
        get() = groups.map { it.name }.toSet() - collapsedGroups

    /** 头部那行小字：「原生 2 · 自定义 3 · 插件 15」 */
    val summary: String
        get() = if (groups.isEmpty()) {
            "暂无指标"
        } else {
            groups.joinToString(" · ") { "${it.name} ${it.items.size}" }
        }
}

/**
 * 「数据指标」页的状态（M13）。
 *
 * 与 `PanelViewModel` 的关系：那个管的是**看板卡片**的增删改，这里管的是
 * **指标本身**的浏览。两者共用 [candidates] 这份候选产出，所以「有哪些指标」
 * 永远一致——这正是用户会在两个地方对照着看的东西。
 *
 * 读数口径：取当天那一行。不看「最近有数据的日期」是因为这个页面要回答的是
 * 「今天在记吗」，翻出三天前的数字反而会让人以为今天已经记过了。
 */
class MetricsViewModel(
    private val scope: PanelScope,
    private val coroutineScope: CoroutineScope,
    private val todayProvider: () -> String = { LocalDate.now().toString() },
) {

    private val _state = MutableStateFlow(MetricsUiState())
    val state: StateFlow<MetricsUiState> = _state.asStateFlow()

    fun refresh() {
        coroutineScope.launch {
            _state.value = _state.value.copy(loading = true)
            val loaded = runCatching { load() }.getOrElse { emptyList() }
            _state.value = _state.value.copy(
                loading = false,
                groups = loaded,
                today = todayProvider(),
            )
        }
    }

    /**
     * 折叠或展开一个分组。
     *
     * 记「折叠掉的」而不是「展开的」：新装的插件会带来一个新分组，
     * 反着存的话它默认是收起的，用户根本不知道多了一组。
     */
    fun toggleGroup(name: String) {
        val current = _state.value.collapsedGroups
        _state.value = _state.value.copy(
            collapsedGroups = if (name in current) current - name else current + name,
        )
    }

    /**
     * 新建一个自定义指标，成功后刷新列表。
     *
     * 直接写 `custom_source` 表（经 `ruleStore`），而不是走一条本页专用的创建链路：
     * 自建源只有这一个落库点，规则页的「新建数据源」写的也是同一张表，
     * 两处造出两种记录格式迟早对不上。
     *
     * 空名字直接忽略：对话框那边按钮已经 disabled，这里再守一道
     * 是为了防止别处调用时把空名写进库（`custom_source` 没有非空约束）。
     */
    fun createCustom(name: String, bucket: MetricBucket) {
        if (name.isBlank()) return
        coroutineScope.launch {
            runCatching {
                scope.ruleStore.saveCustomSource(
                    CustomSourceRecord(
                        displayName = name.trim(),
                        bucket = bucket.name,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            refresh()
        }
    }

    private suspend fun load(): List<MetricsGroup> = withContext(Dispatchers.IO) {
        val date = todayProvider()
        val resolver = SourceResolver(scope)

        // 候选顺序已经是「原生 → 自定义 → 插件」，groupBy 保持首次出现顺序
        val candidates: List<MetricCandidate> = MetricQueryService(scope).candidates(scope)
        val itemsByGroup = candidates.map { candidate ->
            candidate.groupName to itemsOf(candidate, resolver, date)
        }

        itemsByGroup
            .groupBy({ it.first }, { it.second })
            .map { (name, lists) -> MetricsGroup(name, lists.flatten()) }
            .sortedWith(GROUP_ORDER)
    }

    /** 一个候选源展开成若干行（插件一个源可能有多个指标） */
    private suspend fun itemsOf(
        candidate: MetricCandidate,
        resolver: SourceResolver,
        date: String,
    ): List<MetricItem> = candidate.metrics.map { option ->
        // 读数的量纲必须走 resolver：插件源的量纲只存在于插件自己的声明里，
        // 用 MetricSpec 猜会掉到 COUNT，读出来永远是空
        val reading = runCatching {
            resolver.resolveReading(candidate.sourceKey, option.metricKey)
        }.getOrNull()

        val bucket = reading?.bucket
        val record = if (bucket == null) {
            null
        } else {
            runCatching {
                scope.metricStore.getMetric(candidate.sourceKey, date, bucket.name)
            }.getOrNull()
        }
        val value = record?.value

        MetricItem(
            sourceKey = candidate.sourceKey,
            metricKey = option.metricKey,
            displayName = candidate.displayName,
            metricLabel = reading?.metricLabel ?: option.label,
            unit = reading?.unit ?: option.unit,
            valueText = formatValue(value, reading?.unit ?: option.unit),
            hasData = value != null,
        )
    }

    private companion object {
        /**
         * 分组展示顺序：原生 → 自定义 → 插件。
         *
         * 不用字母序：插件的组名是插件自己的展示名（中文），
         * 按字母排会把「小米健康」排到「原生」前面，用户第一眼看到的是插件。
         */
        val GROUP_ORDER: Comparator<MetricsGroup> = compareBy(
            {
                when (it.name) {
                    SOURCE_GROUP_NATIVE -> 0
                    SOURCE_GROUP_CUSTOM -> 1
                    else -> 2
                }
            },
            { it.name },
        )

        /**
         * 数值 + 单位。
         *
         * 整数不显示小数点：屏幕时长 6.0 分钟写成「6.0 分钟」会让用户以为它精确到十分位。
         * 没有数据时给「暂无数据」——写 0 会被读成「有记录但记为 0」，两者含义完全不同。
         */
        fun formatValue(value: Double?, unit: String): String {
            if (value == null) return "暂无数据"
            val shown = if (value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                String.format("%.1f", value)
            }
            return if (unit.isBlank()) shown else "$shown $unit"
        }
    }
}

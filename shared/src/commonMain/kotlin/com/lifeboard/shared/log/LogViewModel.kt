package com.lifeboard.shared.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.shared.platform.AppInfo
import com.lifeboard.shared.platform.TextFileExporter
import com.lifeboard.shared.repository.LogStore
import com.lifeboard.shared.repository.model.AppLogRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日志级别筛选项。[level] 为 null 表示不过滤 */
enum class LogLevelFilter(val level: String?, val label: String) {
    ALL(null, "全部"),
    DEBUG("DEBUG", "调试"),
    INFO("INFO", "信息"),
    WARN("WARN", "警告"),
    ERROR("ERROR", "错误"),
}

/**
 * 日志页的状态（M3-3.2d 迁入 commonMain）。
 *
 * @param records 当前页的记录，最新在前
 * @param total 符合当前筛选条件的总条数，用来算「还有没有下一页」
 * @param loading 首屏加载中——与 [loadingMore] 分开，避免翻页时整页转圈
 */
data class LogUiState(
    val records: List<AppLogRecord> = emptyList(),
    val total: Int = 0,
    val level: LogLevelFilter = LogLevelFilter.ALL,
    val query: String = "",
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
) {
    val hasMore: Boolean get() = records.size < total
}

/**
 * 运行日志页的 ViewModel（M3-3.2d 迁入 commonMain）。
 *
 * 搜索做了防抖：每敲一个字都查一次库，一万条记录会明显卡手；
 * 停手 [SEARCH_DEBOUNCE_MS] 毫秒才真正查。
 *
 * 分页用 offset 游标。**不用**「加载更多时追加到已有序列表尾部」的做法——
 * 新日志随时可能在头部插入，翻到第 3 页时前面的数据已经错位，
 * 所以翻页后按 id 去重再拼接。
 */
class LogViewModel(
    private val store: LogStore,
    private val appInfo: AppInfo,
    private val exporter: TextFileExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        reload()
    }

    fun setLevel(level: LogLevelFilter) {
        if (_state.value.level == level) return
        _state.update { it.copy(level = level) }
        reload()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reload()
        }
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val current = _state.value
            val records = store.pageAppLogs(
                level = current.level.level.orEmpty(),
                query = current.query.trim(),
                limit = PAGE_SIZE,
                offset = 0,
            )
            val total = store.countAppLogs()
            _state.update { it.copy(records = records, total = total, loading = false) }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val next = store.pageAppLogs(
                level = current.level.level.orEmpty(),
                query = current.query.trim(),
                limit = PAGE_SIZE,
                offset = current.records.size,
            )
            // 翻页期间可能有新日志插到头部，导致下一页与已加载的重叠，按 id 去重
            val seen = current.records.mapTo(HashSet()) { it.id }
            val merged = current.records + next.filterNot { it.id in seen }
            _state.update { it.copy(records = merged, loadingMore = false) }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            store.clearAppLogs()
            // 连带清插件快照：插件详情页读的就是它，
            // 只清日志表会让用户以为没清干净
            store.clearPluginSnapshots()
            _state.update { it.copy(records = emptyList(), total = 0, message = "已清空日志与插件快照") }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 导出当前筛选条件下的**全部**日志（不只是已加载的那几页）。
     *
     * 用户点导出是想把完整情况发给别人看，只导出眼前 100 条
     * 会让对方看到的正是「刚好没出错的那一段」。
     */
    fun export() {
        if (!exporter.available) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val current = _state.value
            val all = store.pageAppLogs(
                level = current.level.level.orEmpty(),
                query = current.query.trim(),
                limit = EXPORT_LIMIT,
                offset = 0,
            )
            val text = buildExportText(all)
            val stamp = LocalDate.now().toString()
            val ok = exporter.export(
                fileName = "LifeBoard_日志_$stamp.log",
                mimeType = "text/plain",
                content = text,
            )
            _state.update {
                it.copy(exporting = false, message = if (ok) "日志已导出" else null)
            }
        }
    }

    /** 导出文本头部带上环境信息：脱离应用之后，看日志的人只有这一段能判断现场 */
    private fun buildExportText(records: List<AppLogRecord>): String = buildString {
        appendLine("# LifeBoard 运行日志")
        appendLine("# 版本: ${appInfo.versionName} (${appInfo.packageId})")
        appendLine("# 系统: ${appInfo.osDescription}")
        appendLine("# 设备: ${appInfo.deviceDescription}")
        appendLine("# 导出时间: ${appInfo.nowForExport()}")
        appendLine("# 筛选: 级别=${_state.value.level.label} 关键字=${_state.value.query.ifBlank { "无" }}")
        appendLine("# 条数: ${records.size}")
        appendLine()
        val formatter = EXPORT_TIME_FORMAT
        records.forEach { r ->
            appendLine("${formatter.format(Instant.ofEpochMilli(r.time).atZone(ZoneId.systemDefault()))} ${r.level.padEnd(5)} ${r.tag}: ${r.message}")
            if (r.location.isNotBlank()) appendLine("    位置: ${r.location}")
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val EXPORT_LIMIT = 5_000
        const val SEARCH_DEBOUNCE_MS = 300L

        /** 毫秒是排障必需——同一秒内连续几条日志，没有毫秒看不出先后 */
        private val EXPORT_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}

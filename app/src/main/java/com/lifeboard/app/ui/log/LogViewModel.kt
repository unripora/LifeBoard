package com.lifeboard.app.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.server.db.AppLogDao
import com.lifeboard.server.db.AppLogEntity
import com.lifeboard.server.db.LifeBoardDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志页的可筛选条件 */
enum class LogLevelFilter(val label: String, val value: String) {
    ALL("全部", ""),
    DEBUG("调试", "DEBUG"),
    INFO("信息", "INFO"),
    WARN("警告", "WARN"),
    ERROR("错误", "ERROR"),
}

/**
 * 日志页状态。
 *
 * 日志量可能到一万条，所以分页加载而不是一次性全查：
 * 打开页面只看最近一页，用户往下滑再取。
 */
data class LogUiState(
    val logs: List<AppLogEntity> = emptyList(),
    val level: LogLevelFilter = LogLevelFilter.ALL,
    val query: String = "",
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val total: Int = 0,
    val message: String? = null,
)

/**
 * 日志页。
 *
 * 读的是 [AppLogDao]，与 `AppLogger` 写入的是同一张表，
 * 所以进程被杀、重启后历史日志仍在，这也是它比 `adb logcat` 更适合排查的原因。
 */
class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = LifeBoardDatabase.get(app).appLogDao()

    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新加载第一页，并按当前筛选条件重置 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val current = _state.value
            val rows = withContext(Dispatchers.IO) {
                dao.page(current.level.value, current.query, PAGE_SIZE, 0)
            }
            val total = withContext(Dispatchers.IO) { dao.count() }
            _state.value = current.copy(
                logs = rows,
                loading = false,
                hasMore = rows.size == PAGE_SIZE,
                total = total,
            )
        }
    }

    /** 滑到底部时加载下一页 */
    fun loadMore() {
        val current = _state.value
        if (current.loading || !current.hasMore) return
        viewModelScope.launch {
            _state.value = current.copy(loading = true)
            val rows = withContext(Dispatchers.IO) {
                dao.page(current.level.value, current.query, PAGE_SIZE, current.logs.size)
            }
            _state.value = _state.value.copy(
                logs = _state.value.logs + rows,
                loading = false,
                hasMore = rows.size == PAGE_SIZE,
            )
        }
    }

    fun setLevel(level: LogLevelFilter) {
        _state.value = _state.value.copy(level = level, logs = emptyList(), hasMore = true)
        refresh()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /** 搜索按回车/按钮触发，避免每敲一个字都查一次库 */
    fun search() {
        _state.value = _state.value.copy(logs = emptyList(), hasMore = true)
        refresh()
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.clearAll() }
            _state.value = _state.value.copy(logs = emptyList(), hasMore = false, total = 0)
            _state.value = _state.value.copy(message = "日志已清空")
        }
    }

    /**
     * 导出全部日志为纯文本。
     *
     * 头部带上版本与设备信息：用户把文件发过来时，不需要再问一遍「你装的是哪一版」，
     * 而这个信息往往正是排查的起点。
     */
    suspend fun export(): String = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val header = buildString {
            appendLine("LifeBoard 日志导出")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
            appendLine("应用：${app.packageName}")
            runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                appendLine("版本：${info.versionName}")
            }
            appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("机型：${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            appendLine("日志条数：${dao.count()}")
            appendLine("=".repeat(60))
        }

        val all = dao.latest(AppLogDao.KEEP_LIMIT)
        val body = all.asReversed().joinToString("\n") { row ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA)
                .format(java.util.Date(row.time))
            "[$time] ${row.level}/${row.tag} (${row.location}) ${row.message}"
        }
        header + body
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

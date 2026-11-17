package com.lifeboard.app.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.core.util.Dates
import com.lifeboard.shared.platform.ScreenTimeBackend
import com.lifeboard.shared.repository.model.AppUsageRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 屏幕时长面板状态（M1.4）。
 *
 * 周曲线用 `date` 字符串而不是 `LocalDate`：共享层的接口不能引用 `java.time`，
 * 而界面只需要「今天/星期一…」这类展示，格式化交给界面自己做。
 */
data class ScreenTimeUiState(
    val loading: Boolean = true,
    val hasPermission: Boolean = false,
    val todayMinutes: Double = 0.0,
    val weekMinutes: Double = 0.0,
    val weekDaily: List<ScreenTimeBackend.DailyPoint> = emptyList(),
    /** 今日各应用用量，已按分钟降序 */
    val appUsage: List<AppUsageRecord> = emptyList(),
    val lastMessage: String = "",
)

/**
 * 屏幕时长面板 ViewModel。
 *
 * 数据读取与采集都在 [ScreenTimeBackend] 内部切到 IO，
 * UI 侧只消费 [state]。采集后不做本地缓存，直接重读，
 * 保证面板显示的永远是库里的定稿值。
 */
class ScreenTimeViewModel(
    private val backend: ScreenTimeBackend,
) : ViewModel() {

    private val _state = MutableStateFlow(ScreenTimeUiState())
    val state: StateFlow<ScreenTimeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新读取权限状态与已入库数据 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            loadStats(backend.hasPermission(), "")
        }
    }

    /**
     * 立即采集并入库，然后刷新面板。
     * 用于用户刚授权完回到应用、或想立刻看到今天的最新数字。
     */
    fun collectNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val granted = backend.hasPermission()
            if (!granted) {
                _state.value = _state.value.copy(
                    loading = false,
                    hasPermission = false,
                    lastMessage = "未授予「使用情况访问权」，无法统计",
                )
                return@launch
            }
            // 必须等采集真正写完再读库：只入队不等，下面读到的还是上一次的旧值，
            // 用户会以为「点一次没反应」，需要再点一次才出数
            val ok = backend.collectNow()
            loadStats(granted, if (ok) "已采集完成" else "采集失败，请检查授权后重试")
        }
    }

    private suspend fun loadStats(granted: Boolean, message: String) {
        _state.value = ScreenTimeUiState(
            loading = false,
            hasPermission = granted,
            todayMinutes = backend.todayMinutes(),
            weekMinutes = backend.thisWeekMinutes(),
            weekDaily = backend.thisWeekDaily(),
            appUsage = backend.todayAppUsage(),
            lastMessage = message,
        )
    }

    companion object {
        /** 分钟数格式化为「x 小时 y 分」，不足 1 小时只显示分钟 */
        fun formatMinutes(minutes: Double): String {
            if (minutes <= 0.0) return "0 分钟"
            val total = minutes.toInt()
            val h = total / 60
            val m = total % 60
            return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
        }

        /** 今日日期串，供界面副标题使用 */
        fun todayLabel(): String = Dates.todayString()
    }
}

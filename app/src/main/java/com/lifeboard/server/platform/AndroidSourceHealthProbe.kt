package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.server.health.SourceHealthChecker
import com.lifeboard.shared.panel.SourceHealthEntry
import com.lifeboard.shared.panel.SourceHealthState
import com.lifeboard.shared.platform.SourceHealthProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SourceHealthProbe] 的 Android 实现，委托给 [SourceHealthChecker]。
 *
 * 判断依据分散在 `ScreenTimeDataSource.hasPermission`、`NoticeListenerService.isEnabled`、
 * 插件登录态三处，这里不复制这套判断，只做转发——
 * 健康度显示错了会直接误导用户去改错设置，比不显示更糟。
 *
 * 全量扫描虽然比单源查询贵，但源数量是个位数，可以接受；
 * 若将来源数量上百，应在 [SourceHealthChecker] 内部加缓存，而不是在这里另写一套单源判断。
 */
class AndroidSourceHealthProbe(private val context: Context) : SourceHealthProbe {

    private val appContext = context.applicationContext

    /**
     * 只返回**需要用户处理**的项。
     *
     * `SourceHealthChecker.checkAll` 返回全部源（含健康的），
     * 但面板只在出问题时才提示，所以这里先过滤掉健康的。
     * 过滤放在实现里而不是界面里：这是「什么算需要提示」的业务口径，
     * 两端应该一致，不该各写一份。
     */
    override suspend fun probeAll(): List<SourceHealthEntry> = withContext(Dispatchers.IO) {
        SourceHealthChecker.checkAll(appContext).filter {
            it.state == SourceHealthState.NO_PERMISSION ||
                it.state == SourceHealthState.NOT_IMPLEMENTED
        }
    }

    override fun openSettings(actionKey: String): Boolean =
        SourceHealthChecker.openSettings(appContext, actionKey)
}

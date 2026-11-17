package com.lifeboard.server.platform

import com.lifeboard.shared.dashboard.DashboardLayout
import com.lifeboard.shared.platform.DashboardBackend
import com.lifeboard.shared.repository.DashboardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [DashboardBackend] 的 Android 实现，委托 `DashboardLayout` 写入默认布局。 */
class AndroidDashboardBackend(
    private val store: DashboardStore,
) : DashboardBackend {

    override suspend fun ensureDefaults() {
        withContext(Dispatchers.IO) {
            runCatching { DashboardLayout.ensureInitialized(store) }
        }
    }

    override suspend fun isLayoutEmpty(): Boolean = withContext(Dispatchers.IO) {
        store.getTabs().isEmpty()
    }
}

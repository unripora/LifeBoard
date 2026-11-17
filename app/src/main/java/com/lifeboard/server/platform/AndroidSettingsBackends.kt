package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.server.collect.CollectionPrefs
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.notice.CapturePrefs
import com.lifeboard.server.notice.ScreenCaptureAccessibilityService
import com.lifeboard.shared.platform.CaptureBackend
import com.lifeboard.shared.platform.CollectionBackend
import com.lifeboard.shared.platform.RefreshInterval

/**
 * Android 端的数据源刷新周期（M3-3.3b）。
 *
 * 只是把 `CollectionPrefs` + `DailyAggregateWorker.schedule` 这两步收进一个动作。
 * 收的理由见 [CollectionBackend] 的注释：拆成两个调用，「存了但忘了重排」
 * 这种漏调不会报错，只会让设置看起来生效了实际没有。
 */
internal class AndroidCollectionBackend(
    private val context: Context,
) : CollectionBackend {

    override fun currentInterval(): RefreshInterval = CollectionPrefs.interval(context)

    override suspend fun setInterval(interval: RefreshInterval) {
        CollectionPrefs.setInterval(context, interval)
        // 立即生效：改完不必等下次启动，也不必重启应用。
        // schedule 内部按 interval.enabled 决定注册还是取消
        DailyAggregateWorker.schedule(context)
    }

    // Android 端有 WorkManager，自动刷新是真能跑的
    override val supportsAutoRefresh: Boolean = true
}

/**
 * Android 端的读屏采集偏好（M3-3.3b）。
 *
 * 写完之后必须调 [ScreenCaptureAccessibilityService.notifyPrefsChanged]，
 * 否则正在运行的无障碍服务还按旧配置跑——开关拨了但行为要等下次服务重启才变。
 */
internal class AndroidCaptureBackend(
    private val context: Context,
) : CaptureBackend {

    override val available: Boolean = true

    override fun isPauseOnScreenOff(): Boolean = CapturePrefs.isPauseOnScreenOff(context)

    override suspend fun setPauseOnScreenOff(value: Boolean) {
        CapturePrefs.setPauseOnScreenOff(context, value)
        ScreenCaptureAccessibilityService.notifyPrefsChanged()
    }
}

/**
 * 应用图标**不在这里**。
 *
 * `androidx.compose.ui.graphics.ImageBitmap` 需要 Compose 运行时，而 `:server`
 * 模块没有 Compose 依赖（它是后台服务模块，界面都在 `:app`）。
 * 所以 `AndroidAppIconProvider` 放在 `:app` 的 `AndroidAppIconProvider.kt`，
 * 装配时由 `MainActivity` 注进去。
 */

package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PluginDetailStore
import com.lifeboard.shared.platform.PluginRawGrouping
import com.lifeboard.shared.platform.PluginRawPage

/**
 * Android 端读「详情页原始条目」的实现。
 *
 * 与桌面端的 `DesktopPluginDetailStore` 同构，差别只在指标清单的来源：
 * 这里问 [PluginManager] 要已加载插件的 `metrics` 声明。
 *
 * 不调插件、不发网络请求：详情页会被反复打开、时间段会被反复切换，
 * 每次都去拉一次接口既慢又浪费。
 */
class AndroidPluginDetailStore(private val context: Context) : PluginDetailStore {

    override suspend fun rawRecords(pluginId: String, from: String?, to: String): PluginRawPage {
        val store = DatabaseProviders.pluginStore(context)

        val specs = runCatching {
            PluginManager.findPlugin(context, pluginId)?.plugin?.metrics
                .orEmpty()
                .associateBy { it.metricKey }
        }.onFailure {
            // 插件读不出来不该让「全部数据」整块消失：库里的条目是真实存在的，
            // 用 metricKey 当展示名也要给用户看到
            AppLogger.warn("读取插件 $pluginId 的指标声明失败：${it.message ?: it::class.simpleName}")
        }.getOrElse { emptyMap() }

        // All 分支传进来的 from 是 null，这里按库里最早的日期决定窗口起点
        val start = from ?: PluginRawGrouping.earliestDate(store, pluginId) ?: return PluginRawPage()
        return PluginRawGrouping.read(store, pluginId, start, to, specs)
    }
}

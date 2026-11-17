package com.lifeboard.desktop.plugin

import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.platform.PluginDetailStore
import com.lifeboard.shared.platform.PluginRawGrouping
import com.lifeboard.shared.platform.PluginRawPage
import com.lifeboard.shared.repository.PluginStore

/**
 * 桌面端读「详情页原始条目」的实现。
 *
 * 完全走宿主自己的库：读 [PluginStore] 的 `plugin_raw_record` 与
 * `plugin_metric_query` 两张表，不碰 QuickJS 运行时、不发网络请求。
 *
 * 指标清单从 [repository] 现取而不是缓存：插件可能在被停用、被覆盖安装、
 * 被卸载的任意时刻改掉自己的指标声明，缓存一份就会让详情页拿旧名字去配新数据。
 */
class DesktopPluginDetailStore(
    private val pluginStore: PluginStore,
    private val repository: () -> DesktopPluginRepository,
) : PluginDetailStore {

    override suspend fun rawRecords(pluginId: String, from: String?, to: String): PluginRawPage {
        val specs = runCatching {
            repository().find(pluginId)?.metrics.orEmpty()
        }.onFailure {
            // 插件读不出来（运行时启动失败等）不该让「全部数据」整块消失：
            // 库里的条目是真实存在的，用 metricKey 当展示名也要给用户看到
            AppLogger.warn("读取插件 $pluginId 的指标声明失败：${it.message ?: it::class.simpleName}")
        }.getOrElse { emptyMap() }

        // All 分支传进来的 from 是 null，这里按库里最早的日期决定窗口起点
        val start = from ?: PluginRawGrouping.earliestDate(pluginStore, pluginId) ?: return PluginRawPage()
        return PluginRawGrouping.read(pluginStore, pluginId, start, to, specs)
    }
}

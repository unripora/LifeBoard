package com.lifeboard.shared.repository

import com.lifeboard.shared.log.AppLogger

/**
 * 「清除某个插件产生的全部数据」的编排，两端共用。
 *
 * ## 为什么需要这一层
 *
 * 一次彻底清除要动这些地方：插件私有目录（平台相关）、凭据、`metric`、
 * `source_health`、`metric_meta`、`plugin_raw_record` 与 `plugin_metric_query`。
 * 桌面端与 Android 端各写一份的话，「少删一张表」这种遗漏几乎必然发生，
 * 表现是「清完数据，详情页还列着查过的指标」——而用户已经看不到插件了，
 * 只会以为清除失败。
 *
 * 平台差异只剩「私有目录在哪」，收成一个 [deleteDataDir] 回调。
 *
 * ## 为什么每一步单独兜底
 *
 * 清理是**尽力而为**的动作：目录里的文件可能正被别的进程占用，
 * 某张表也可能本来就没有该插件的行。任何一步失败都不该让后面的步骤跳过——
 * 尤其是「凭据没删掉」这种后果，比「历史数据多留了几行」严重得多。
 *
 * @param metricKeys 保留旧签名供调用方兼容；删除指标与健康度现按插件 ID 前缀处理，
 *   因此不依赖当前 manifest 声明，历史上已移除的指标也会清理
 */
class PluginDataPurger(
    private val pluginStore: PluginStore,
    private val metricStore: MetricStore,
    private val clearCredentials: suspend (pluginId: String) -> Unit,
    private val deleteDataDir: (pluginId: String) -> Unit,
) {

    @Suppress("UNUSED_PARAMETER")
    suspend fun purge(pluginId: String, metricKeys: Collection<String>) {
        step("删除私有目录") { deleteDataDir(pluginId) }
        step("清除凭据") { clearCredentials(pluginId) }

        // metric / source_health 没有 pluginId 列；按固定前缀删除可覆盖已从 manifest 移除的指标
        step("删除插件全部指标") { metricStore.deleteMetricsByPlugin(pluginId) }
        step("删除插件全部健康度") { metricStore.deleteSourceHealthByPlugin(pluginId) }
        step("删除指标元数据") { metricStore.deleteMetricMetaByPlugin(pluginId) }
        step("删除原始条目") { pluginStore.deleteRawRecordsByPlugin(pluginId) }
        step("删除查询痕迹") { pluginStore.deleteMetricQueriesByPlugin(pluginId) }
    }

    private inline fun step(what: String, block: () -> Unit) {
        runCatching(block).onFailure {
            AppLogger.warn("清除插件数据时「$what」失败：${it.message ?: it::class.simpleName}")
        }
    }
}

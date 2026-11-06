package com.lifeboard.server.plugin

import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourcePlugin

/**
 * 一个被成功加载的插件及其运行期状态。
 *
 * 为什么 [trusted] 是可变标记而不是「撤销时重建列表」：
 * 重建列表会把**所有**插件的实例一起换掉，正在采集的插件会被打断。
 * 撤销只影响单个插件，调度前检查这个标记就够了。
 */
class LoadedPlugin(
    val archive: PluginArchiveInfo,
    val archiveName: String,
    val plugin: SourcePlugin,
) {
    /** 插件自己声明的 id，可能与包名不同，落库 key 用它 */
    val pluginId: String get() = plugin.id

    /** 声明过的指标，按 key 索引，落库时按 key 反查量纲 */
    val metrics: Map<String, MetricSpec> = plugin.metrics.associateBy { it.metricKey }

    /**
     * 是否仍被信任。
     *
     * 用 `@Volatile`：撤销信任发生在 UI 线程，读取发生在 Worker 线程。
     * 这里只需要「迟早能看到新值」，不需要原子复合操作，因此不上锁。
     */
    @Volatile
    var trusted: Boolean = true

    override fun toString(): String = "LoadedPlugin($pluginId v${archive.versionCode})"
}

package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.source.DataSource
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.PluginMetrics
import com.lifeboard.plugin.api.SourceRef

/**
 * 把一个 [SourceRef.Plugin] 指标适配成宿主能调度的 [DataSource]。
 *
 * ## 为什么一个指标一个适配器
 *
 * 现有 Worker 按「源 → 采集器 → 该源的记录」工作，而插件一次 `fetch` 会同时返回
 * 多个量纲（小米健康同时给步数与睡眠）。若让一个适配器代表整个插件，
 * 一个插件失败就会连带它所有指标一起报错，`source_health` 里也无法按量纲记录。
 *
 * 所以按 `(pluginId, metricKey)` 建适配器：每个指标各自独立成败，
 * 与内置源里「小米健康的步数与睡眠各自一条健康记录」保持一致。
 *
 * ## 失败不抛异常
 *
 * `collect` 与 `health` 都必须兜住插件抛出的任何异常——插件是第三方代码，
 * 让它把整个聚合任务打挂是最糟的结果。异常统一翻译成不健康 + 空数据。
 */
class PluginDataSource(
    private val plugin: LoadedPlugin,
    private val spec: MetricSpec,
    context: Context,
) : DataSource {

    private val appContext = context.applicationContext

    override val source: SourceRef = SourceRef.Plugin(
        pluginId = plugin.pluginId,
        metricKey = spec.metricKey,
        displayName = "${plugin.plugin.displayName}·${spec.displayName}",
        bucket = spec.bucket,
    )

    override val requiresInjection: Boolean = false

    override suspend fun collect(date: String): List<MetricRecord> {
        if (!plugin.trusted) return emptyList()

        val produced = runCatching {
            plugin.plugin.fetch(pluginContext(), date)
        }.getOrElse { error ->
            log("fetch 失败", error)
            return emptyList()
        }

        // 量纲只能来自声明：未声明的 metricKey 不猜，直接丢
        val resolved = PluginMetrics.resolve(listOf(spec), produced)
        if (resolved.rejected.isNotEmpty()) {
            log("产出了未声明的指标：${resolved.rejected.map { it.metricKey }}")
        }

        // resolve 已保证每个 accepted 的 spec 就是本适配器声明的那个量纲，
        // 因此这里直接用 source.bucket（= spec.bucket）落库即可
        return resolved.accepted.map { (_, metric) ->
            MetricRecord(source = source, date = date, value = metric.value)
        }
    }

    override suspend fun health(): SourceHealth {
        if (!plugin.trusted) {
            return SourceHealth(
                source = source,
                healthy = false,
                message = "插件信任已撤销，已停止调度",
            )
        }

        return runCatching { plugin.plugin.health(pluginContext()) }
            .getOrElse { error ->
                log("health 失败", error)
                PluginHealth.Broken("健康检查异常：${error.message ?: error.javaClass.simpleName}")
            }
            .toSourceHealth()
    }

    /**
     * 插件登录态映射到健康度。
     *
     * [AuthState.Expired] 单独一档：凭据过期但可续期，此时报「不健康」会误导用户去重新登录，
     * 实际下次采集大概率就自动恢复了。
     */
    private suspend fun PluginHealth.toSourceHealth(): SourceHealth {
        val auth = runCatching { plugin.plugin.authState(pluginContext()) }
            .getOrElse { AuthState.LoggedOut }

        return when (this) {
            is PluginHealth.Broken -> SourceHealth(source, false, message = message)
            is PluginHealth.Degraded -> SourceHealth(
                source,
                healthy = auth !is AuthState.LoggedOut,
                message = message,
            )
            PluginHealth.Healthy -> when (auth) {
                is AuthState.LoggedIn -> SourceHealth(source, true, message = "已登录")
                is AuthState.Expired -> SourceHealth(
                    source,
                    healthy = true,
                    message = auth.message ?: "登录已过期，将在下次采集时自动续期",
                )
                AuthState.LoggedOut -> SourceHealth(source, false, message = "未登录，请先在插件页登录")
            }
        }
    }

    private fun pluginContext() = HostPluginContext(plugin.pluginId, appContext)

    private fun log(message: String, error: Throwable? = null) {
        android.util.Log.w(
            "LifeBoardPluginSource",
            "${plugin.pluginId}/${spec.metricKey}: $message",
            error,
        )
    }
}

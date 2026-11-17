package com.lifeboard.desktop.plugin

import com.lifeboard.core.model.MetricRecord
import com.lifeboard.core.model.SourceHealth
import com.lifeboard.core.util.Dates
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.FetchResult
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.host.HostPluginContext
import com.lifeboard.shared.repository.MetricStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面端的插件采集器（M5-5.8）。
 *
 * ## 为什么单独一个类
 *
 * 采集要处理 [FetchResult] 的五个分支，每个分支对应不同的健康度与重试策略。
 * 把它们摊进 `DesktopCollectionBackend` 会让「自动刷新」那个类从
 * 「按周期判定任务」变成「一个什么都干的调度中心」。
 *
 * ## 与 Android `DailyAggregateWorker` 的对齐点
 *
 * 判据完全照抄，不另立一套：
 *
 * - [FetchResult.Success] → 健康，写库
 * - [FetchResult.Empty] → 健康，不写库（「确实没有」不是失败）
 * - [FetchResult.TemporaryFailure] → 不健康但**不算坏**，下次再试
 * - [FetchResult.AuthenticationRequired] → 不健康，提示重新登录
 * - [FetchResult.Failure] → 不健康，明确失败
 *
 * 这些分类存在的意义就是「让用户看到该怎么处理」。把所有非 Success 都
 * 记成「采集失败」，用户就永远不知道该去登录、还是该等一会儿、还是该卸载插件。
 *
 * ## 量纲从哪来
 *
 * 从插件自己声明的 [com.lifeboard.plugin.api.MetricSpec] 取，不从返回值猜。
 * `metric` 表主键是 `(sourceType, date, bucket)`，猜错量纲写进去的数据
 * 与查询侧对不上——查不出来，也删不掉。
 */
class DesktopPluginCollector(
    private val repository: DesktopPluginRepository,
    private val metricStore: MetricStore,
    private val credentialStoreFactory: (String) -> com.lifeboard.plugin.api.CredentialStore,
    private val rawRecordSink: com.lifeboard.shared.plugin.host.RawRecordSink,
) {

    /**
     * 采集全部已信任插件。
     *
     * @return 是否至少有一个插件成功（或明确无数据）。全失败时返回 false，
     *   让调用方能区分「同步跑过了」与「同步压根没成」，
     *   但**单个插件的失败不会中断其它插件**——一个插件的网络问题
     *   不该让用户其它数据也拿不到。
     */
    suspend fun collectAll(): Boolean = withContext(Dispatchers.IO) {
        val entries = repository.plugins().filter { it.trusted }
        if (entries.isEmpty()) return@withContext false

        var anySucceeded = false
        for (entry in entries) {
            if (collectOne(entry)) anySucceeded = true
        }
        anySucceeded
    }

    /**
     * 只采指定插件。找不到或未信任时返回 false。
     *
     * 详情页的「立即同步」用这个：采全部插件会把「别的插件成功」
     * 当成「这个插件也同步好了」。
     */
    suspend fun collectOne(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val entry = repository.plugins().firstOrNull { it.pluginId == pluginId && it.trusted }
            ?: return@withContext false
        collectOne(entry)
    }

    /** 采集单个插件。返回它是否「成功」（含明确无数据） */
    suspend fun collectOne(entry: LoadedEntry): Boolean = withContext(Dispatchers.IO) {
        // 需要登录但没登录：不跑插件，只记一条健康度。
        // 跑一遍只会白耗一次请求，然后留下一条「采集失败」——
        // 用户看到的是「插件坏了」，实际只要去登录
        if (entry.needsLogin) {
            val auth = runCatching { entry.plugin.authState(buildContext(entry.pluginId)) }.getOrNull()
            if (auth !is AuthState.LoggedIn) {
                markHealth(entry, healthy = false, message = "需要登录")
                return@withContext false
            }
        }

        val today = Dates.todayString()
        val context = buildContext(entry.pluginId)
        val result = try {
            // withCollectDate 把日期绑到上下文上：插件上报裸记录时要用它，
            // 而插件自己拿不到「宿主的今天」——各端时区可能不同
            HostPluginContext.withCollectDate(context, today) {
                entry.plugin.fetch(context, today)
            }
        } catch (cancel: CancellationException) {
            // 取消是控制流，不是采集失败。
            //
            // 这里必须单独接住并**原样抛出**：采集跨多个 suspend 点（网络、文件、JS 桥），
            // 页面切走或任务被取消时 `fetch` 会抛 CancellationException。
            // 若让它落进下面的 catch-all，就会被翻译成
            // 「插件采集时出错：Job was cancelled」——用户看到一条假的故障，
            // 而实际上数据可能已经拉回来了。更糟的是它会被记成健康度异常，
            // 插件明明能用却显示成坏了。
            throw cancel
        } catch (error: Throwable) {
            AppLogger.error("插件采集抛异常：${entry.pluginId}", error)
            FetchResult.Failure("插件采集时出错：${error.message ?: error::class.simpleName}")
        }

        when (result) {
            is FetchResult.Success -> {
                var written = 0
                for (metric in result.metrics) {
                    val spec = entry.metrics[metric.metricKey]
                    if (spec == null) {
                        // 插件吐了没声明过的指标：不写库。写进去会变成
                        // 「有数据但界面上没有这个指标」，用户永远查不到它
                        AppLogger.warn("插件 ${entry.pluginId} 返回了未声明的指标：${metric.metricKey}")
                        continue
                    }
                    metricStore.saveMetric(
                        MetricRecord(
                            source = SourceRef.Plugin(
                                pluginId = entry.pluginId,
                                metricKey = metric.metricKey,
                                displayName = spec.displayName,
                                bucket = spec.bucket,
                            ),
                            date = today,
                            value = metric.value,
                        ),
                    )
                    written++
                }
                markHealth(entry, healthy = true, message = "")
                AppLogger.info("插件采集完成：${entry.pluginId} 写入 $written 条")
                true
            }

            is FetchResult.Empty -> {
                // 「确实没有数据」不是失败：把它记成不健康会让用户
                // 在一个一切正常的插件上看到红灯，然后去卸载重装
                markHealth(entry, healthy = true, message = result.reason)
                true
            }

            is FetchResult.TemporaryFailure -> {
                markHealth(entry, healthy = false, message = "暂时取不到：${result.message}")
                false
            }

            is FetchResult.AuthenticationRequired -> {
                markHealth(entry, healthy = false, message = "登录已失效，请重新登录")
                false
            }

            is FetchResult.Failure -> {
                markHealth(entry, healthy = false, message = result.message.ifBlank { "采集失败" })
                false
            }
        }
    }

    /**
     * 每个指标的采集状态分别记一条。
     *
     * 为什么不是插件一条：详情页要按指标显示「步数正常、睡眠断了」，
     * 合并成一条就只能给一个整体结论，用户不知道是哪个指标的问题。
     */
    private suspend fun markHealth(entry: LoadedEntry, healthy: Boolean, message: String) {
        val now = System.currentTimeMillis()
        for ((metricKey, spec) in entry.metrics) {
            metricStore.saveSourceHealth(
                SourceHealth(
                    source = SourceRef.Plugin(
                        pluginId = entry.pluginId,
                        metricKey = metricKey,
                        displayName = spec.displayName,
                        bucket = spec.bucket,
                    ),
                    healthy = healthy,
                    // 失败时保留上次成功时间：它能告诉用户「数据停在三天前」
                    lastSuccessAt = if (healthy) now else previousSuccessAt(entry.pluginId, metricKey),
                    message = message,
                ),
            )
        }
    }

    private suspend fun previousSuccessAt(pluginId: String, metricKey: String): Long {
        val key = SourceRef.pluginKey(pluginId, metricKey)
        return runCatching {
            metricStore.getAllSourceHealth().firstOrNull { it.source.key == key }?.lastSuccessAt ?: 0L
        }.getOrDefault(0L)
    }

    private fun buildContext(pluginId: String): HostPluginContext = HostPluginContext(
        pluginId = pluginId,
        filesDirPathProvider = { pluginDataDir(pluginId) },
        credentialStoreFactory = credentialStoreFactory,
        metricStore = metricStore,
        rawRecordSink = rawRecordSink,
    )

    /** 插件私有数据目录，与 `DesktopPlatformDependencies` 里保持一致 */
    private fun pluginDataDir(pluginId: String): String {
        require(HostPluginContext.isSafePluginId(pluginId)) { "非法 pluginId：$pluginId" }
        return java.io.File(System.getProperty("user.home"), ".lifeboard/plugins/$pluginId")
            .apply { mkdirs() }
            .absolutePath
    }
}

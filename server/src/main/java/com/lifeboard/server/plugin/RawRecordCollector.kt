package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.PluginMetricQueryEntity
import com.lifeboard.server.db.PluginRawRecordEntity
import com.lifeboard.server.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 插件上报的原始条目落库口径。
 *
 * ## 为什么不再做「暂存到这次采集结束再统一写」
 *
 * 早先的实现想解决一个真实问题：一个插件的多个指标各是一个 [PluginDataSource] 适配器，
 * 每个适配器都会独立调一次 `plugin.fetch`，上报即写库会互相覆盖。
 *
 * 但暂存方案换来了两个更严重的问题：
 * 1. **回补永远不会落库**。回补走 `onAction` → 插件的 `backfillHistory`，只上报不采集；
 *    而写库发生在 `DailyAggregateWorker` 里，回补后要么等下一个周期，要么被
 *    [begin] 清掉，用户点了「回补」却什么都看不到。
 * 2. **Worker 中途失败会丢整批**。[begin] 在采集开头清暂存，Worker 被杀或超时后
 *    `commit` 跑不到，这批条目全部丢失，且没有任何补偿。
 *
 * 现在改成**上报即写库**，并用 `(pluginId, date, metricKey)` 三级粒度替换。
 * 覆盖问题天然不存在：不同指标适配器的 `metricKey` 不同，替换各自那一格；
 * 同一指标在一次 `fetch` 内只上报一次。
 *
 * ## 替换粒度为什么必须是「某天的某个指标」
 *
 * 粒度不能再粗：
 * - 按插件替换：一次只采到步数的同步会把库里的睡眠条目全部抹掉
 * - 按天替换：步数拿到了、睡眠为空的那天，睡眠的历史条目会消失
 *
 * 这两个场景在小米健康上是常态（睡眠靠手环上报，经常只有步数），
 * 所以「没收到」一律视为「这次没更新」，保留上一次的条目。
 *
 * 反过来说，**收到一组新的就整组换掉**：同一天重新同步后旧条目必然过期，
 * 留着重叠会让人看到两份互相矛盾的数据。
 */
object RawRecordCollector {

    private val mutex = Mutex()

    /**
     * 上报一批条目并立即落库。
     *
     * @param date 条目所属日期 `yyyy-MM-dd`；空串表示无法确定归属，直接丢弃——
     *   落到任意一天都是错的，而错位的数据比没有数据更难排查
     */
    suspend fun stage(
        context: Context,
        pluginId: String,
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) = withContext(Dispatchers.IO) {
        AppLogger.info(
            "raw stage 收到：$pluginId/$metricKey ${records.size} 条 date='$date'",
        )
        if (metricKey.isBlank() || records.isEmpty() || date.isBlank()) {
            AppLogger.warn(
                "raw stage 丢弃：$pluginId/$metricKey date='$date' " +
                    "records=${records.size}（date 为空说明没有采集日期可归属）",
            )
            return@withContext
        }

        // 串行化写库：同一个指标的两次上报如果并发执行，
        // delete 与 insert 会交错，可能删掉刚写进去的那一批
        mutex.withLock {
            runCatching {
                val dao = LifeBoardDatabase.get(context.applicationContext).pluginRawRecordDao()
                dao.deleteByDateAndMetric(pluginId, date, metricKey)
                dao.insertAll(
                    records.mapIndexed { index, record ->
                        PluginRawRecordEntity(
                            pluginId = pluginId,
                            date = date,
                            metricKey = metricKey,
                            timestamp = record.timestamp,
                            value = record.value,
                            detail = record.detail,
                            seq = index,
                        )
                    },
                )
            }.onFailure {
                AppLogger.warn("写入 $pluginId/$metricKey 的原始数据失败：${it.message.orEmpty()}")
            }
        }
    }

    /**
     * 记录「这个指标在某天被查询过」，与 [stage] 分开调用。
     *
     * 为什么单独一个方法而不是在 [stage] 里顺手写：两者要回答的问题不同。
     * [stage] 是「我拿到了这些条目」，这里是「我查了但可能是空的」。
     * 有条目时也会补记一次查询痕迹，这样时间段筛选里「查过哪些指标」
     * 与「有哪些条目」始终对得上。
     */
    suspend fun markQueried(
        context: Context,
        pluginId: String,
        metricKey: String,
        date: String,
    ) = withContext(Dispatchers.IO) {
        if (pluginId.isBlank() || metricKey.isBlank() || date.isBlank()) return@withContext
        runCatching {
            LifeBoardDatabase.get(context.applicationContext)
                .pluginMetricQueryDao()
                .insertAll(listOf(PluginMetricQueryEntity(pluginId, date, metricKey)))
        }.onFailure {
            AppLogger.warn("记录 $pluginId/$metricKey 的查询痕迹失败：${it.message.orEmpty()}")
        }
    }
}

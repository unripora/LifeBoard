package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.MetricSample
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.server.db.LifeBoardDatabase
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * [PluginContext] 的宿主实现。
 *
 * 关键在于 [pluginId] 是构造时绑定的 `val`，且没有对外暴露可修改它的方法：
 * 插件从 [credentials] 走的每一次存取都会带上这个 pluginId，
 * 接口里没有「传别人的 id」这条路径。
 */
class HostPluginContext(
    override val pluginId: String,
    context: Context,
) : PluginContext {

    private val appContext = context.applicationContext

    override val credentials: CredentialStore = VaultCredentialStore(pluginId, appContext)

    /**
     * 目录在首次访问时才创建。
     *
     * 一次采集会依次调 `fetch` / `health` / `authState`，每次都新建本对象；
     * 若在构造函数里做 [File.mkdirs]，就变成每次采集都查一遍目录存在性。
     * 插件不碰 [filesDir] 时（演示插件就是这样）这一步完全是白费。
     */
    override val filesDir: File by lazy {
        pluginDataDir(appContext, pluginId).also { if (!it.exists()) it.mkdirs() }
    }

    /**
     * 读本插件自己的历史指标。
     *
     * 过滤条件里的 `sourceType` 固定为 `PLUGIN_{pluginId}:{metricKey}`，
     * 用的是**绑定在构造参数里的** [pluginId]，插件没有机会换成别人的——
     * 与凭据隔离同一思路，在接口层面不给出越权路径。
     *
     * 日期窗口按本地日历算：指标表里 `date` 是 `yyyy-MM-dd` 字符串，
     * 直接做字符串比较即可，不需要转成时间戳。
     */
    override suspend fun recentMetrics(metricKey: String, days: Int): List<MetricSample> {
        if (days <= 0) return emptyList()
        val (from, to) = window(days)
        val source = SourceRef.pluginKey(pluginId, metricKey)

        return LifeBoardDatabase.get(appContext).metricDao()
            .getRange(from, to)
            .filter { it.sourceType == source }
            .map { MetricSample(it.date, it.value) }
    }

    /**
     * 一次读完本插件全部指标。
     *
     * 复写默认实现是为了**只查一次库**：默认实现按指标逐个调 [recentMetrics]，
     * 每个指标都是一次 `date BETWEEN ?` 的范围扫描，指标一多就是 N 次全量过滤。
     * 这里取一次窗口内的所有行，再按 `sourceType` 前缀分到各指标上。
     *
     * 只保留 [metrics] 里声明过的 key：库里可能留着插件新版本已经删掉的指标行，
     * 那些行没人能解释它的量纲，不该出现在页面上。
     */
    override suspend fun allRecentMetrics(
        days: Int,
        metrics: List<String>,
    ): Map<String, List<MetricSample>> {
        if (days <= 0 || metrics.isEmpty()) return emptyMap()
        val (from, to) = window(days)
        // 先把「metricKey → 落库 sourceType」算好，避免在行循环里反复拼字符串
        val keyBySource = metrics.associateBy { SourceRef.pluginKey(pluginId, it) }

        val buckets = mutableMapOf<String, MutableList<MetricSample>>()
        LifeBoardDatabase.get(appContext).metricDao()
            .getRange(from, to)
            .forEach { row ->
                val key = keyBySource[row.sourceType] ?: return@forEach
                buckets.getOrPut(key) { mutableListOf() } += MetricSample(row.date, row.value)
            }

        // 按插件声明的顺序返回：Map 的迭代顺序直接影响详情页里指标的排列，
        // 用库里行的顺序会让每次刷新都换一遍位置
        return metrics.mapNotNull { key ->
            buckets[key]?.let { key to it.sortedBy { s -> s.date } }
        }.toMap()
    }

    /** 最近 [days] 天（含今天）的日期窗口，按本地日历算 */
    private fun window(days: Int): Pair<String, String> {
        val today = LocalDate.now(ZoneId.systemDefault())
        return today.minusDays((days - 1).toLong()).toString() to today.toString()
    }

    /**
     * 插件上报本次 `fetch` 拿到的原始条目。
     *
     * 立即落库，不做暂存：回补走的是 `onAction`，不经过采集链路，
     * 暂存方案下它的数据永远等不到写库那一刻（见 [RawRecordCollector] 的说明）。
     *
     * [date] 由插件显式给出（一次 fetch 内回补多天时必需）；
     * 留空时用本上下文记录下的「当前采集日期」，
     * 由 [PluginDataSource] 在调 `fetch` 前通过 [bindCollectDate] 设置。
     *
     * [pluginId] 来自构造参数，插件无法伪造归属——与 [recentMetrics] 同一思路。
     */
    override suspend fun reportRawRecords(
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) {
        if (metricKey.isBlank() || records.isEmpty()) return
        val effective = date.ifBlank { collectDate }
        RawRecordCollector.stage(appContext, pluginId, metricKey, records, effective)
    }

    /**
     * 记录「这次查过这个指标」。
     *
     * 只写查询痕迹，不写条目：详情页据此把没有数据的指标也列出来并显示「暂无」。
     * 有条目时 [reportRawRecords] 已隐含了「查过」，插件不调这个方法也不影响展示。
     */
    override suspend fun reportMetricQueried(metricKey: String, date: String) {
        if (metricKey.isBlank()) return
        val effective = date.ifBlank { collectDate }
        if (effective.isBlank()) return
        RawRecordCollector.markQueried(appContext, pluginId, metricKey, effective)
    }

    /**
     * 当前正在采集的日期，供 [reportRawRecords] 在插件没传日期时兜底。
     *
     * 由 [PluginDataSource] 在调 `fetch` 前设置：插件只知道「我拉到了什么」，
     * 未必知道自己是被哪一天调起来的，宿主比它更清楚。
     */
    @Volatile
    private var collectDate: String = ""

    fun bindCollectDate(date: String) {
        collectDate = date
    }

    companion object {
        /**
         * 插件私有目录：`filesDir/plugins/{pluginId}/data`。
         *
         * 用 pluginId 拼路径前必须校验：pluginId 来自 `.ext` 的 manifest，
         * 属于外部输入，含 `../` 就能写到别的插件或宿主目录里去。
         */
        fun pluginDataDir(context: Context, pluginId: String): File {
            require(isSafePluginId(pluginId)) { "非法 pluginId：$pluginId" }
            return File(File(File(context.applicationContext.filesDir, "plugins"), pluginId), "data")
        }

        /**
         * pluginId 只允许反向域名式的安全字符。
         *
         * 拒绝空串、`..`、路径分隔符与冒号（冒号是插件 key 的分隔符）：
         * 前两者防目录穿越，后两者防 key 解析歧义。
         */
        fun isSafePluginId(pluginId: String): Boolean =
            pluginId.isNotBlank() &&
                pluginId != "." &&
                pluginId != ".." &&
                pluginId.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
    }
}

/** 把 [CredentialVault] 收窄成「只认本插件」的存取口 */
private class VaultCredentialStore(
    private val pluginId: String,
    private val context: Context,
) : CredentialStore {

    override suspend fun get(slot: String): String? =
        CredentialVault.read(context, pluginId, slot)

    override suspend fun put(slot: String, value: String) {
        CredentialVault.write(context, pluginId, slot, value)
    }

    override suspend fun remove(slot: String) {
        CredentialVault.clearSlot(context, pluginId, slot)
    }

    override suspend fun clear() {
        CredentialVault.clearPlugin(context, pluginId)
    }
}

package com.lifeboard.server.health

import android.content.Context
import android.util.Log
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.server.collect.SettingsNavigator
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.db.SourceHealthDao
import com.lifeboard.server.db.SourceHealthEntity
import com.lifeboard.server.notice.NoticeListenerService
import com.lifeboard.server.plugin.PluginDataSource
import com.lifeboard.server.plugin.PluginLoader
import com.lifeboard.server.source.ScreenTimeDataSource
import com.lifeboard.server.source.toRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据源健康度汇总（M4.8）。
 *
 * 每个源有三种状态，界面用不同颜色区分：
 * 1. **可用**：权限齐全
 * 2. **缺权限**：权限没开，数据一定为 0，需要引导用户去设置
 * 3. **二期未接入**：代码还没写，明确告知而不是让它默默为 0
 *
 * 第 3 种最容易被忽略——用户建了小米健康的任务，看着永远是 0，
 * 会以为是采集出错，来回折腾。所以这里显式标出来。
 */
object SourceHealthChecker {

    /** 健康度的展示态，比裸 boolean 多了"未接入"这一档 */
    enum class State { OK, NO_PERMISSION, NOT_IMPLEMENTED, RULE_ONLY }

    data class Entry(
        val source: SourceRef,
        val state: State,
        val message: String,
        /** 点击后可跳转的设置页；null 表示无可用跳转 */
        val actionKey: String?,
        /** 自建源/插件源没有对应的 SourceType 枚举，这里存它的展示名 */
        val customName: String = "",
    ) {
        val healthy: Boolean get() = state == State.OK

        val displayName: String
            get() = customName.ifBlank { source.displayName }

        /** 写进 `source_health.sourceType` 的键 */
        val key: String get() = source.key
    }

    private const val TAG = "LifeBoardSourceHealth"

    private const val ACTION_USAGE_ACCESS = "usage_access"
    private const val ACTION_NOTICE_LISTENER = "notice_listener"
    private const val ACTION_MI_HEALTH_LOGIN = "mi_health_login"

    /** 一期的全部数据源状态。二期源固定返回 NOT_IMPLEMENTED */
    fun checkAll(context: Context): List<Entry> {
        val screenOk = ScreenTimeDataSource(context).hasPermission()
        val noticeOk = NoticeListenerService.isEnabled(context)

        return listOf(
            Entry(
                source = builtinRef(SourceType.SCREEN_TIME),
                state = if (screenOk) State.OK else State.NO_PERMISSION,
                message = if (screenOk) "已授权，可统计屏幕使用时长" else "未授予「使用情况访问权」",
                actionKey = if (screenOk) null else ACTION_USAGE_ACCESS,
            ),
            Entry(
                source = builtinRef(SourceType.FEISHU_ATTENDANCE),
                state = if (noticeOk) State.OK else State.NO_PERMISSION,
                message = if (noticeOk) {
                    "已授权，识别到打卡通知时计数"
                } else {
                    "未授予「通知使用权」，无法识别打卡通知"
                },
                actionKey = if (noticeOk) null else ACTION_NOTICE_LISTENER,
            ),
            Entry(
                source = builtinRef(SourceType.DUOLINGO),
                state = if (noticeOk) State.OK else State.NO_PERMISSION,
                message = if (noticeOk) {
                    "已授权，识别到目标达成通知时计数"
                } else {
                    "未授予「通知使用权」，无法识别签到"
                },
                actionKey = if (noticeOk) null else ACTION_NOTICE_LISTENER,
            ),
            miHealthEntry(context),
            notImplemented(SourceType.LEGADO, "需解包 Legado 读取阅读记录，二期接入"),
            notImplemented(SourceType.READEST, "需读取 Readest 本地存储，二期接入"),
            notImplemented(SourceType.QC_BOOK_LOG, "待提供数据表结构，二期接入"),
            Entry(
                source = builtinRef(SourceType.MANUAL),
                state = State.OK,
                message = "手动打卡，无需权限",
                actionKey = null,
            ),
        ) + customEntries(context) + pluginEntries(context)
    }

    /**
     * 插件源单独成组。
     *
     * 与内置源的区别在于「不健康的原因」完全由插件决定（登录过期、接口变了、契约不匹配），
     * 宿主不猜，直接展示插件返回的 message。加载失败的插件以 BROKEN 呈现，
     * 让用户看得到「为什么装了这个插件却用不了」。
     */
    private fun pluginEntries(context: Context): List<Entry> {
        val loaded = PluginLoader.loadedPlugins().flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                val ref = SourceRef.Plugin(
                    pluginId = plugin.pluginId,
                    metricKey = spec.metricKey,
                    displayName = "${plugin.plugin.displayName}·${spec.displayName}",
                    bucket = spec.bucket,
                )
                if (!plugin.trusted) {
                    Entry(ref, State.NOT_IMPLEMENTED, "信任已撤销，已停止调度", actionKey = null)
                } else {
                    // 这里只给静态结论：插件没撤销信任就算「可尝试采集」。
                    // 真实健康度（登录过期、接口变更）由 persistPluginHealth 调插件的
                    // suspend health() 后写进 source_health 表，面板以表里的为准。
                    Entry(ref, State.OK, "插件源，健康度见最近一次采集结果", actionKey = null)
                }
            }
        }
        val broken = PluginLoader.brokenPlugins().map { broken ->
            Entry(
                source = SourceRef.Builtin(
                    key = "PLUGIN_BROKEN_${broken.archiveName}",
                    displayName = broken.archiveName,
                    bucket = MetricBucket.FLAG,
                ),
                state = State.NOT_IMPLEMENTED,
                message = "插件加载失败：${broken.reason}",
                actionKey = null,
            )
        }
        return loaded + broken
    }

    /**
     * 自建源单独成组。
     *
     * 它们没有采集器，数据只能靠采集规则命中写入，所以状态是第四态 [State.RULE_ONLY]，
     * 而不是"缺权限"或"未接入"——否则用户会去找一个根本不存在的授权开关。
     */
    private fun customEntries(context: Context): List<Entry> =
        LifeBoardDatabase.get(context).customSourceDao().let { dao ->
            runCatching {
                kotlinx.coroutines.runBlocking { dao.getAll() }
            }.getOrDefault(emptyList())
        }.map { entity ->
            Entry(
                // 自建源没有枚举，用一个量纲已按实体还原的引用承载它的 key 与展示名；
                // MANUAL 只是占位，实际 key 与名称都来自 customName 与引用本身
                source = entity.toRef(),
                state = State.RULE_ONLY,
                message = "自建源，仅由采集规则命中计数",
                actionKey = null,
                customName = entity.displayName,
            )
        }

    /**
     * 小米健康的健康度。
     *
     * 它不依赖系统权限，而是依赖「有没有登录小米账号」：
     * 未登录时提示去登录，而不是显示缺失一个并不存在的授权开关。
     */
    private fun miHealthEntry(context: Context): Entry {
        val loggedIn = com.lifeboard.server.mihealth.MiHealthCredentialStore.isLoggedIn(context)
        return Entry(
            source = builtinRef(SourceType.MI_HEALTH),
            state = if (loggedIn) State.OK else State.NO_PERMISSION,
            message = if (loggedIn) {
                "已登录，可读取步数与睡眠"
            } else {
                "未登录小米账号，无法读取步数与睡眠"
            },
            actionKey = if (loggedIn) null else ACTION_MI_HEALTH_LOGIN,
        )
    }

    /** 打开某个源对应的设置页 */
    fun openSettings(context: Context, actionKey: String): Boolean = when (actionKey) {
        ACTION_USAGE_ACCESS -> SettingsNavigator.openUsageAccess(context)
        ACTION_NOTICE_LISTENER -> SettingsNavigator.openNotificationListenerSettings(context)
        // 小米健康没有系统授权页，唯一入口是应用内的登录页，交给 UI 层处理
        else -> false
    }

    /** 是否需要由应用内页面处理（而非打开系统设置页） */
    fun isInAppAction(actionKey: String): Boolean = actionKey == ACTION_MI_HEALTH_LOGIN

    /**
     * 把最新检查结果写回 source_health 表。
     *
     * 聚合 Worker 每天调一次，历史上"某源哪天开始失效"就有据可查，
     * 不用只靠用户截图反馈。
     *
     * 直连 Room 而不走 MetricWriter：那套 Provider + JSONL 兜底是给
     * hook 进程用的，Worker 在主进程，直连即可且不会丢兜底。
     */
    suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        val dao = LifeBoardDatabase.get(context).sourceHealthDao()
        val now = System.currentTimeMillis()
        val previous = dao.getAll().associateBy { it.sourceType }
        checkAll(context).forEach { entry ->
            val key = entry.key
            dao.upsert(
                SourceHealthEntity(
                    sourceType = key,
                    healthy = entry.healthy,
                    // 保持上次成功时间：本次健康就刷新，否则沿用旧值
                    lastSuccessAt = if (entry.healthy) now else (previous[key]?.lastSuccessAt ?: 0L),
                    message = entry.message,
                ),
            )
        }
        // 插件健康度只能在这里取：它要调插件的 suspend `health()`，
        // 而 checkAll 是同步的权限快照，塞不进去
        persistPluginHealth(context, dao, now, previous)
    }

    /**
     * 单独写插件源的健康度。
     *
     * 为什么不并进 [checkAll]：`checkAll` 是同步的「权限是否齐全」快照，几微秒完成；
     * 而插件的 `health()` 可能要发网络请求，一慢就会拖住整个列表。
     * 这里在 suspend 上下文里逐个问插件，结果写进同一张 `source_health` 表，
     * 面板照常读表，不需要额外的 UI 逻辑。
     *
     * 单个插件抛异常不影响其他插件，也不影响整次持久化。
     */
    private suspend fun persistPluginHealth(
        context: Context,
        dao: SourceHealthDao,
        now: Long,
        previous: Map<String, SourceHealthEntity>,
    ) {
        PluginLoader.loadedPlugins().flatMap { plugin ->
            plugin.metrics.values.map { spec ->
                PluginDataSource(plugin, spec, context)
            }
        }.forEach { source ->
            val health = runCatching { source.health() }.getOrElse { error ->
                Log.w(TAG, "plugin health failed: ${source.source.key}", error)
                return@forEach
            }
            val key = health.source.key
            runCatching {
                dao.upsert(
                    SourceHealthEntity(
                        sourceType = key,
                        healthy = health.healthy,
                        lastSuccessAt = if (health.healthy) {
                            now
                        } else {
                            previous[key]?.lastSuccessAt ?: 0L
                        },
                        message = health.message,
                    ),
                )
            }.onFailure { Log.w(TAG, "persist plugin health failed: $key", it) }
        }
    }

    private fun notImplemented(source: SourceType, message: String) = Entry(
        source = builtinRef(source),
        state = State.NOT_IMPLEMENTED,
        message = message,
        actionKey = null,
    )
}

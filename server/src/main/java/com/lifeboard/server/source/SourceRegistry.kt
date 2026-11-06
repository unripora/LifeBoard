package com.lifeboard.server.source

import android.content.Context
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.SourceRef
import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.toSourceTypeOrNull
import com.lifeboard.server.db.CustomSourceEntity
import com.lifeboard.server.db.LifeBoardDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据源的统一解析入口。
 *
 * 存在的理由：`metric.sourceType` 是字符串，用户自建源之后，
 * 「按名字找数据源」这件事就不再是 `SourceType.valueOf` 了。
 * 过去那种 `firstOrNull ?: MANUAL` 的写法会把自建源静默降级成手动打卡，
 * 面板看起来正常、数字永远是 0，且没有任何地方能发现问题。
 *
 * 所有需要按 key 解析数据源的地方（面板、任务编辑、判定、健康度）都必须走这里。
 */
object SourceRegistry {

    /** 内置源列表（不含 MANUAL，MANUAL 只在兜底时出现） */
    val builtinSources: List<SourceRef.Builtin> =
        SourceType.entries.map { builtinRef(it) }

    /** 规则/任务可以选择的数据源：内置去掉屏幕时长与手动，再并上自建源 */
    suspend fun selectable(context: Context): List<SourceRef> = withContext(Dispatchers.IO) {
        val builtin = builtinSources.filter {
            val type = it.toSourceTypeOrNull()
            type != SourceType.SCREEN_TIME && type != SourceType.MANUAL
        }
        builtin + customSources(context)
    }

    /** 全部自建源 */
    suspend fun customSources(context: Context): List<SourceRef.Custom> =
        withContext(Dispatchers.IO) {
            LifeBoardDatabase.get(context).customSourceDao().getAll().map { it.toRef() }
        }

    /** 按 key 解析；内置优先，其次自建源，再次插件源，都不中则回落到手动打卡 */
    suspend fun resolve(context: Context, key: String): SourceRef = withContext(Dispatchers.IO) {
        resolveSyncOrNull(context, key)?.let { return@withContext it }

        val customId = SourceRef.parseCustomId(key)
        if (customId != null) {
            val entity = LifeBoardDatabase.get(context).customSourceDao().getById(customId)
            if (entity != null) return@withContext entity.toRef()
        }

        // 插件源：插件是运行时装的，宿主这里拿不到它声明的展示名与量纲，
        // 只能用 key 反解出的 pluginId / metricKey 兜一个最小引用，
        // 真正的元数据在 M2 的插件注册表接入后再补齐。
        SourceRef.parsePluginKey(key)?.let { (pluginId, metricKey) ->
            val bucket = MetricSpec.bucketOf(metricKey) ?: MetricBucket.COUNT
            return@withContext SourceRef.Plugin(
                pluginId = pluginId,
                metricKey = metricKey,
                displayName = "$pluginId·$metricKey",
                bucket = bucket,
            )
        }

        // 旧数据里可能存着已经被删掉的自建源，或历史上写死过的未知键，
        // 统一回落到手动打卡：至少语义是"只记录、不采集"，不会假装有数据。
        builtinRef(SourceType.MANUAL)
    }

    /**
     * 批量解析。
     *
     * 自建源表一次全读，避免 N 个任务查 N 次库——面板首页每帧都可能重建任务列表，
     * 逐个查询会把主线程拖慢到肉眼可见。
     */
    suspend fun resolveAll(context: Context, keys: Collection<String>): Map<String, SourceRef> =
        withContext(Dispatchers.IO) {
            val customs = LifeBoardDatabase.get(context).customSourceDao().getAll()
                .associateBy { SourceRef.customKey(it.id) }
            keys.distinct().associateWith { key ->
                SourceType.fromKey(key)?.let { return@associateWith builtinRef(it) }
                customs[key]?.toRef()?.let { return@associateWith it }
                SourceRef.parsePluginKey(key)?.let { (pluginId, metricKey) ->
                    return@associateWith SourceRef.Plugin(
                        pluginId = pluginId,
                        metricKey = metricKey,
                        displayName = "$pluginId·$metricKey",
                        bucket = MetricSpec.bucketOf(metricKey) ?: MetricBucket.COUNT,
                    )
                }
                builtinRef(SourceType.MANUAL)
            }
        }

    /** 删除自建源前的引用检查，返回引用它的规则数 */
    suspend fun referenceCount(context: Context, key: String): Int = withContext(Dispatchers.IO) {
        LifeBoardDatabase.get(context).captureRuleDao().countBySource(key)
    }

    /**
     * 只解析内置源，不查库。
     *
     * 给 UI 层「选了规则后立刻回填数据源」这种需要即时反馈的场景用：
     * 自建源需要查库，走 suspend 版本；这里返回 null 时调用方保持原值即可。
     */
    fun resolveSyncOrNull(context: Context, key: String): SourceRef? =
        SourceType.fromKey(key)?.let { builtinRef(it) }
}

/** 自建源实体 → 领域引用 */
fun CustomSourceEntity.toRef(): SourceRef.Custom = SourceRef.Custom(
    id = id,
    displayName = displayName,
    bucket = MetricBucket.entries.firstOrNull { it.name == bucket } ?: MetricBucket.COUNT,
)

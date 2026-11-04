package com.lifeboard.server.source

import android.content.Context
import com.lifeboard.core.model.MetricBucket
import com.lifeboard.core.model.SourceRef
import com.lifeboard.core.model.SourceType
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
        SourceType.entries.map { SourceRef.Builtin(it) }

    /** 规则/任务可以选择的数据源：内置去掉屏幕时长与手动，再并上自建源 */
    suspend fun selectable(context: Context): List<SourceRef> = withContext(Dispatchers.IO) {
        val builtin = builtinSources.filter {
            it.type != SourceType.SCREEN_TIME && it.type != SourceType.MANUAL
        }
        builtin + customSources(context)
    }

    /** 全部自建源 */
    suspend fun customSources(context: Context): List<SourceRef.Custom> =
        withContext(Dispatchers.IO) {
            LifeBoardDatabase.get(context).customSourceDao().getAll().map { it.toRef() }
        }

    /** 按 key 解析；内置优先，其次自建源，都不中则回落到手动打卡 */
    suspend fun resolve(context: Context, key: String): SourceRef = withContext(Dispatchers.IO) {
        SourceType.entries.firstOrNull { it.name == key }?.let { return@withContext SourceRef.Builtin(it) }

        val customId = SourceRef.parseCustomId(key)
        if (customId != null) {
            val entity = LifeBoardDatabase.get(context).customSourceDao().getById(customId)
            if (entity != null) return@withContext entity.toRef()
        }

        // 旧数据里可能存着已经被删掉的自建源，或历史上写死过的未知键，
        // 统一回落到手动打卡：至少语义是"只记录、不采集"，不会假装有数据。
        SourceRef.Builtin(SourceType.MANUAL)
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
                SourceType.entries.firstOrNull { it.name == key }?.let { return@associateWith SourceRef.Builtin(it) }
                customs[key]?.toRef() ?: SourceRef.Builtin(SourceType.MANUAL)
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
        SourceType.entries.firstOrNull { it.name == key }?.let { SourceRef.Builtin(it) }
}

/** 自建源实体 → 领域引用 */
fun CustomSourceEntity.toRef(): SourceRef.Custom = SourceRef.Custom(
    id = id,
    displayName = displayName,
    bucket = MetricBucket.entries.firstOrNull { it.name == bucket } ?: MetricBucket.COUNT,
)

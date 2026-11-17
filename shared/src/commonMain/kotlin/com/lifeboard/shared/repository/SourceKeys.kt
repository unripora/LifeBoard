package com.lifeboard.shared.repository

import com.lifeboard.core.model.SourceType
import com.lifeboard.core.model.builtinRef
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef

/**
 * 数据源 key → [SourceRef] 的解析。
 *
 * ## 为什么要有这一层
 *
 * `metric.sourceType` / `task.sourceType` 存的是字符串 key。内置源能直接按枚举名反解，
 * 但用户自建源（`custom:<id>`）和插件源（`plugin:<pluginId>:<metricKey>`）不行——
 * 自建源的展示名与量纲在 `custom_source` 表里，插件源的元数据在插件注册表里。
 *
 * 过去那种 `firstOrNull ?: MANUAL` 的写法会把查不到的 key 静默降级成手动打卡，
 * 表现是面板看起来正常、数字永远是 0，且没有任何地方能发现问题。
 * 所以解析失败**必须能区分出来**：[Resolved.Ok] 与 [Resolved.Fallback] 是两种结果。
 *
 * ## 为什么把查库这一侧抽成参数
 *
 * 解析自建源要读 `custom_source`。如果这里直接依赖某个具体数据库实现，
 * 这段逻辑就被钉死在那个模块里，桌面端无法复用。
 * 传入 [CustomSourceLookup] 之后，本文件是纯逻辑，两端共用同一份判定顺序。
 */
object SourceKeys {

    /**
     * 解析结果。
     *
     * 分两种而不是返回可空值：调用方有时需要「解析不出来就报错」（写路径），
     * 有时需要「尽量给个能显示的」（读路径）。返回 null 会让两者二选一，
     * 而把 fallback 显式标注出来，读路径一眼能看出「这条数据其实没对上源」。
     */
    sealed interface Resolved {
        data class Ok(val ref: SourceRef) : Resolved

        /**
         * 落到了兜底值。
         *
         * @param reason 为什么没法解析，用于日志与排障——不写出来的话，
         *   「数字一直是 0」这类问题在用户机器上永远查不出原因
         */
        data class Fallback(val ref: SourceRef, val reason: String) : Resolved
    }

    /** 查自建源的入口。返回 null 表示「没有这个 id」。 */
    fun interface CustomSourceLookup {
        suspend fun find(id: Long): SourceRef.Custom?
    }

    /** 内置源列表（不含 MANUAL，MANUAL 只在兜底时出现） */
    val builtinSources: List<SourceRef.Builtin> =
        SourceType.entries.map { builtinRef(it) }

    /**
     * 规则/任务可以选择的数据源：原生能力去掉屏幕时长，再并上自建源。
     *
     * 屏幕时长为什么不在这里：它是**被动采集**的指标，不是一件「做了就算完成」
     * 的事，拿它当任务的完成判定没有语义。
     *
     * 第三方来源（飞书打卡、小米健康等）已从 [SourceType] 中删除：
     * 它们的采集全部由 JS 插件承担，内置枚举里不再有对应条目。
     */
    fun selectable(customs: List<SourceRef.Custom>): List<SourceRef> {
        val builtin = SourceType.entries
            .filter { it != SourceType.SCREEN_TIME && it != SourceType.MANUAL }
            .map { builtinRef(it) }
        return builtin + customs
    }

    /**
     * 只解析内置源，不查库。
     *
     * 给「选了规则后立刻回填数据源」这种需要即时反馈的场景用：
     * 自建源要查库，走 suspend 版本；这里返回 null 时调用方保持原值即可。
     */
    fun resolveBuiltin(key: String): SourceRef? =
        SourceType.fromKey(key)?.let { builtinRef(it) }

    /**
     * 完整解析：内置 → 自建 → 插件 → 兜底。
     *
     * 顺序不能乱：内置优先是因为它的 key 最短最不可能撞；
     * 自建必须在插件之前，因为两者前缀不同但历史数据里可能出现过交叉。
     */
    suspend fun resolve(key: String, lookup: CustomSourceLookup): Resolved {
        resolveBuiltin(key)?.let { return Resolved.Ok(it) }

        SourceRef.parseCustomId(key)?.let { id ->
            lookup.find(id)?.let { return Resolved.Ok(it) }
            return Resolved.Fallback(
                ref = builtinRef(SourceType.MANUAL),
                reason = "自建源 custom:$id 在 custom_source 里不存在（可能已被删除）",
            )
        }

        SourceRef.parsePluginKey(key)?.let { (pluginId, metricKey) ->
            return Resolved.Ok(pluginRef(pluginId, metricKey))
        }

        // 旧数据里可能存着历史上写死过的未知键。
        // 回落到手动打卡：至少语义是「只记录、不采集」，不会假装有数据。
        return Resolved.Fallback(
            ref = builtinRef(SourceType.MANUAL),
            reason = "无法识别的数据源 key：$key",
        )
    }

    /**
     * 批量解析。
     *
     * 自建源表一次全读再配 key，避免 N 个任务查 N 次库——
     * 面板首页每帧都可能重建任务列表，逐个查询会把主线程拖慢到肉眼可见。
     */
    suspend fun resolveAll(keys: Collection<String>, customs: List<SourceRef.Custom>): Map<String, SourceRef> {
        val byKey = customs.associateBy { it.key }
        return keys.distinct().associateWith { key ->
            resolveBuiltin(key)
                ?: byKey[key]
                ?: SourceRef.parsePluginKey(key)?.let { (pluginId, metricKey) -> pluginRef(pluginId, metricKey) }
                ?: builtinRef(SourceType.MANUAL)
        }
    }

    /**
     * 插件源引用。
     *
     * 插件是运行时装的，宿主这里拿不到它声明的展示名与量纲，
     * 只能用 key 反解出的 pluginId / metricKey 兜一个最小引用；
     * 真正的元数据在插件注册表接入后再补齐。
     */
    private fun pluginRef(pluginId: String, metricKey: String): SourceRef.Plugin =
        SourceRef.Plugin(
            pluginId = pluginId,
            metricKey = metricKey,
            displayName = "$pluginId·$metricKey",
            bucket = MetricSpec.bucketOf(metricKey) ?: MetricBucket.COUNT,
        )
}

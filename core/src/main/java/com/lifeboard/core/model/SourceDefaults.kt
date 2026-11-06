package com.lifeboard.core.model

import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.SourceRef

/**
 * 内置源的默认量纲。
 *
 * 契约层的 [SourceRef.Builtin] 不知道自己对应哪个 `SourceType`
 * （那属于宿主内部信息），所以默认量纲由宿主在这里补齐。
 */
fun defaultBucketOf(type: SourceType): MetricBucket = when (type) {
    SourceType.SCREEN_TIME -> MetricBucket.DURATION_MINUTES
    SourceType.DUOLINGO -> MetricBucket.COUNT
    SourceType.MI_HEALTH -> MetricBucket.STEPS
    SourceType.LEGADO -> MetricBucket.DURATION_MINUTES
    SourceType.READEST -> MetricBucket.DURATION_MINUTES
    SourceType.QC_BOOK_LOG -> MetricBucket.DURATION_MINUTES
    SourceType.FEISHU_ATTENDANCE -> MetricBucket.FLAG
    SourceType.MANUAL -> MetricBucket.FLAG
}

/** 用宿主枚举造一个内置源引用，量纲默认取该源的默认量纲 */
fun builtinRef(type: SourceType, bucket: MetricBucket? = null): SourceRef.Builtin =
    SourceRef.Builtin(
        key = type.name,
        displayName = type.displayName,
        bucket = bucket ?: defaultBucketOf(type),
    )

/** 按 key（枚举名）找默认量纲；不是内置源返回 null */
fun defaultBucketOfKey(key: String): MetricBucket? =
    SourceType.fromKey(key)?.let { defaultBucketOf(it) }

/** 把契约层的引用还原成宿主枚举；自建源与插件源返回 null */
fun SourceRef.Builtin.toSourceTypeOrNull(): SourceType? = SourceType.fromKey(key)

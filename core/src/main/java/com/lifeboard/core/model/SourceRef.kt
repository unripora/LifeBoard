package com.lifeboard.core.model

/**
 * 数据源的统一引用。
 *
 * 引入它的原因：`metric.sourceType` 一直是**字符串**，但业务代码过去用
 * `SourceType.entries.firstOrNull { it.name == ... } ?: MANUAL` 反解，
 * 一旦用户自建数据源，就会被静默降级成「手动打卡」——
 * 面板显示正常、数字永远是 0，且没有任何报错可查。
 *
 * 现在所有"按名字找数据源"的地方统一走 [SourceRegistry.resolve]，
 * 内置源与自定义源都能拿到正确的名称、量纲与单位。
 */
sealed interface SourceRef {

    /** 写进 `metric.sourceType` 的键，内置源是枚举名，自定义源是 `CUSTOM_{id}` */
    val key: String

    /** 展示名称 */
    val displayName: String

    /** 默认量纲 */
    val bucket: MetricBucket

    /** 展示单位 */
    val unit: String

    /** 是否为用户自建源（自建源没有采集器，数据只能来自规则命中） */
    val isCustom: Boolean

    /** 内置源 */
    data class Builtin(val type: SourceType) : SourceRef {
        override val key: String get() = type.name
        override val displayName: String get() = type.displayName
        override val bucket: MetricBucket get() = defaultBucket(type)
        override val unit: String get() = bucket.unit
        override val isCustom: Boolean get() = false
    }

    /** 用户自建源 */
    data class Custom(
        val id: Long,
        override val displayName: String,
        override val bucket: MetricBucket,
    ) : SourceRef {
        override val key: String get() = customKey(id)
        override val unit: String get() = bucket.unit
        override val isCustom: Boolean get() = true
    }

    companion object {
        /** 内置源的默认量纲，与任务编辑页的可选量纲保持一致的思路 */
        fun defaultBucket(type: SourceType): MetricBucket = when (type) {
            SourceType.SCREEN_TIME -> MetricBucket.DURATION_MINUTES
            SourceType.DUOLINGO -> MetricBucket.COUNT
            SourceType.MI_HEALTH -> MetricBucket.STEPS
            SourceType.LEGADO -> MetricBucket.DURATION_MINUTES
            SourceType.READEST -> MetricBucket.DURATION_MINUTES
            SourceType.QC_BOOK_LOG -> MetricBucket.DURATION_MINUTES
            SourceType.FEISHU_ATTENDANCE -> MetricBucket.FLAG
            SourceType.MANUAL -> MetricBucket.FLAG
        }

        fun customKey(id: Long): String = "CUSTOM_$id"

        /** 从 `CUSTOM_12` 里取出 12，不是自建源返回 null */
        fun parseCustomId(key: String): Long? {
            val suffix = key.removePrefix(PREFIX)
            if (suffix == key) return null
            return suffix.toLongOrNull()
        }

        private const val PREFIX = "CUSTOM_"
    }
}

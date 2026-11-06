package com.lifeboard.plugin.api

/**
 * 指标分桶，用于区分同一数据源下的不同量纲。
 *
 * 枚举名同时就是**内置源的 metricKey**：`metric.bucket` 列存的一直是枚举名，
 * 复用它可以保证历史数据零迁移（见 [MetricSpec]）。
 *
 * 本类型位于 `:plugin-api` 而不是 `:core`：插件声明自己的指标时必须能引用它，
 * 而插件不能依赖宿主内部的 `:core`。
 */
enum class MetricBucket {
    /** 时长，单位分钟 */
    DURATION_MINUTES,

    /** 步数 */
    STEPS,

    /** 睡眠时长，单位分钟 */
    SLEEP_MINUTES,

    /** 计数型，如连胜天数、打卡次数 */
    COUNT,

    /** 布尔型，1 表示完成、0 表示未完成 */
    FLAG,
    ;

    /** 该分桶的展示单位 */
    val unit: String
        get() = when (this) {
            DURATION_MINUTES, SLEEP_MINUTES -> "分钟"
            STEPS -> "步"
            COUNT -> "次"
            FLAG -> ""
        }
}

/**
 * 指标元数据：一个数据源能产出的一个具体指标。
 *
 * 引入它的原因：`MetricBucket` 只说明"量纲"，不说明"是哪个指标"。
 * 小米健康一个源同时给步数与睡眠，两者都是真实指标、都要进 `metric` 表，
 * 而表主键是 `(sourceType, date, bucket)` —— 光有量纲不足以区分同一量纲下的多个指标。
 * [metricKey] 补上这一层，并且与 `metric.bucket` 列保持同一取值，
 * 从而既不新增列、也不迁移历史数据。
 *
 * 插件用它声明自己的指标，宿主据此确定该插件指标的量纲；
 * **同一 metricKey 不允许声明成两个不同量纲**，否则同一行数据会被两种口径解读。
 *
 * @property metricKey 指标键，数据源内唯一；内置源直接用 [MetricBucket] 枚举名
 * @property bucket 该指标的量纲
 * @property displayName 展示名称
 * @property unit 展示单位，默认取量纲的单位
 */
data class MetricSpec(
    val metricKey: String,
    val bucket: MetricBucket,
    val displayName: String,
    val unit: String = bucket.unit,
) {
    companion object {
        /**
         * 由 [MetricBucket] 造一个内置指标规格。
         *
         * metricKey 就是枚举名，与历史 `metric.bucket` 列取值完全一致，
         * 因此老数据不需要任何迁移就能被读出来。
         */
        fun of(bucket: MetricBucket, displayName: String = bucket.name): MetricSpec =
            MetricSpec(
                metricKey = bucket.name,
                bucket = bucket,
                displayName = displayName,
            )

        /** 按 metricKey 反解量纲；只认 [MetricBucket] 枚举名，认不出返回 null */
        fun bucketOf(metricKey: String): MetricBucket? =
            MetricBucket.entries.firstOrNull { it.name == metricKey }
    }
}

package com.lifeboard.server.metric

import com.lifeboard.core.model.SourceType
import com.lifeboard.plugin.api.MetricBucket
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.plugin.api.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 指标卡读取时「该读哪一桶」的推理（M6b2b）。
 *
 * [MetricQueryService] 本身需要 Android Context 与 Room，跑不了 JVM 单测；
 * 但真正容易错的是**量纲枚举与 metricKey 的换算**，那部分是可以单独验的纯逻辑：
 * 卡片上存的是 metricKey，`metric` 表主键里存的是量纲枚举名，
 * 两者错配的结果是「卡能加、读出来永远是空」，而且不报任何错。
 */
class MetricBucketResolutionTest {

    @Test
    fun metricKeyEqualsBucketNameForBuiltinSources() {
        // 指标契约的核心约定（见 MetricBucket 的类注释）：
        // 内置源的 metricKey 就是枚举名，所以这张表可以零迁移地复用 bucket 列
        MetricBucket.entries.forEach { bucket ->
            assertEquals(bucket, MetricSpec.bucketOf(bucket.name))
        }
    }

    @Test
    fun unknownMetricKeyResolvesToNullNotDefault() {
        // 认不出必须返回 null。悄悄回落成 COUNT 会让一张绑错指标的卡
        // 去读「计数」那一行，显示出一个毫不相干的数字
        assertNull(MetricSpec.bucketOf("not_a_bucket"))
        assertNull(MetricSpec.bucketOf(""))
    }

    @Test
    fun pluginMetricKeysAreRegisteredNames() {
        // 插件源的 key 形如 PLUGIN_dev.foo:steps，其中 metricKey 那半截
        // 由插件自己的 MetricSpec 声明；宿主只认得量纲枚举名，
        // 所以插件必须用「枚举名即为 metricKey」的写法才能被读到
        val spec = MetricSpec(metricKey = "STEPS", bucket = MetricBucket.STEPS, displayName = "步数")
        assertEquals(MetricBucket.STEPS, MetricSpec.bucketOf(spec.metricKey))
    }

    @Test
    fun flagBucketHasEmptyUnit() {
        // FLAG 卡要显示「完成/未完成」，带一个「次」反而会渲染成「完成 次」
        assertEquals("", MetricBucket.FLAG.unit)
    }

    @Test
    fun valueBucketsHaveUnits() {
        assertNotEquals("", MetricBucket.STEPS.unit)
        assertNotEquals("", MetricBucket.DURATION_MINUTES.unit)
        assertNotEquals("", MetricBucket.COUNT.unit)
    }

    @Test
    fun everyBucketHasChineseLabel() {
        // 缺一个分支会让卡片标题上出现空字符串，Kotlin 的 when 穷尽性
        // 能挡住漏写，但挡不住写成空白串
        MetricBucket.entries.forEach { bucket ->
            assertEquals(true, bucketLabelOf(bucket).isNotBlank())
        }
    }

    @Test
    fun pluginCandidateResolvesSourceKeyPerMetric() {
        // 一个插件声明两个指标时，选哪个指标就该存哪个指标的 sourceKey。
        // 存错的结果是「卡上写着睡眠、显示的是步数」——而且不报任何错
        val steps = MetricOption(metricKey = "steps", label = "步数", unit = "步")
        val sleep = MetricOption(metricKey = "sleep", label = "睡眠", unit = "分钟")
        // 候选上带的 sourceKey 是占位（用第一个指标拼的），选谁由 sourceKeyFor 决定
        val candidate = MetricCandidate(
            sourceKey = SourceRef.pluginKey("dev.foo.mihealth", "steps"),
            displayName = "小米健康",
            metrics = listOf(steps, sleep),
        )

        assertEquals(
            SourceRef.pluginKey("dev.foo.mihealth", "sleep"),
            candidate.sourceKeyFor(sleep),
        )
        assertEquals(
            SourceRef.pluginKey("dev.foo.mihealth", "steps"),
            candidate.sourceKeyFor(steps),
        )
    }

    @Test
    fun builtinCandidateIgnoresMetricKeyForSourceKey() {
        // 内置源的 key 与指标无关：它就是数据源枚举名，
        // 拼上 metricKey 会得到一个解析不出来的键
        val option = MetricOption(metricKey = null, label = "步数", unit = "步")
        val candidate = MetricCandidate(
            sourceKey = SourceType.MI_HEALTH.name,
            displayName = "小米健康",
            metrics = listOf(option),
        )
        assertEquals(SourceType.MI_HEALTH.name, candidate.sourceKeyFor(option))
    }
}

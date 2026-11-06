package com.lifeboard.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.1 / M1.2：`MetricRecord` 与 `SourceHealth` 改用 `SourceRef` 后的行为锁定。
 *
 * 最要紧的一条：`metric` 表主键是 `(sourceType, date, bucket)`，
 * 一个源要同时存两个量纲（小米健康：步数 + 睡眠），两条记录的 bucket 必须不同，
 * 否则后写的会把先写的覆盖掉。这里把这个能力钉死。
 */
class MetricRecordSourceRefTest {

    @Test
    fun `量纲取自 source 而不是单独存一份`() {
        val ref = SourceRef.Builtin(SourceType.SCREEN_TIME)
        val record = MetricRecord(source = ref, date = "2026-11-05", value = 128.0)
        assertEquals(MetricBucket.DURATION_MINUTES, record.bucket)
    }

    @Test
    fun `小米健康同源双量纲可以共存`() {
        val steps = MetricRecord(
            source = SourceRef.Builtin(SourceType.MI_HEALTH, MetricBucket.STEPS),
            date = "2026-11-05",
            value = 8432.0,
        )
        val sleep = MetricRecord(
            source = SourceRef.Builtin(SourceType.MI_HEALTH, MetricBucket.SLEEP_MINUTES),
            date = "2026-11-05",
            value = 421.0,
        )

        // 落库键相同（同一个源），但 bucket 不同——这正是 metric 表主键能区分它们的原因
        assertEquals(steps.source.key, sleep.source.key)
        assertEquals("MI_HEALTH", steps.source.key)
        assertTrue(steps.bucket != sleep.bucket)
    }

    @Test
    fun `未指定量纲时内置源用默认量纲`() {
        // 小米健康的默认量纲是步数，与改造前 SourceRef.defaultBucket 的行为一致
        val ref = SourceRef.Builtin(SourceType.MI_HEALTH)
        assertEquals(MetricBucket.STEPS, ref.bucket)
    }

    @Test
    fun `内置源指定量纲不改变落库键`() {
        // 这条是"表零迁移"的防线：两个量纲都必须写成同一个 sourceType
        val steps = SourceRef.Builtin(SourceType.MI_HEALTH, MetricBucket.STEPS)
        val sleep = SourceRef.Builtin(SourceType.MI_HEALTH, MetricBucket.SLEEP_MINUTES)
        assertEquals("MI_HEALTH", steps.key)
        assertEquals("MI_HEALTH", sleep.key)
    }

    @Test
    fun `插件源记录的量纲与单位来自引用`() {
        val ref = SourceRef.Plugin(
            pluginId = "dev.foo.mihealth",
            metricKey = "sleep_minutes",
            displayName = "小米健康·睡眠",
            bucket = MetricBucket.SLEEP_MINUTES,
        )
        val record = MetricRecord(source = ref, date = "2026-11-05", value = 421.0)

        assertEquals("PLUGIN_dev.foo.mihealth:sleep_minutes", record.source.key)
        assertEquals(MetricBucket.SLEEP_MINUTES, record.bucket)
        assertEquals("分钟", record.source.unit)
        assertEquals("小米健康·睡眠", record.source.displayName)
    }

    @Test
    fun `健康度用 SourceRef 承载源标识`() {
        val health = SourceHealth(
            source = SourceRef.Builtin(SourceType.SCREEN_TIME),
            healthy = false,
            message = "未授予「使用情况访问权」",
        )
        assertEquals("SCREEN_TIME", health.source.key)
        assertEquals(false, health.healthy)
    }

    @Test
    fun `插件健康度不会退化成内置枚举`() {
        val ref = SourceRef.Plugin(
            pluginId = "dev.foo",
            metricKey = "steps",
            displayName = "Foo",
            bucket = MetricBucket.STEPS,
        )
        val health = SourceHealth(source = ref, healthy = true, lastSuccessAt = 1_000L)
        assertEquals("PLUGIN_dev.foo:steps", health.source.key)
        assertTrue(health.source.isPlugin)
    }

    // ---------- MetricSpec ----------

    @Test
    fun `内置指标规格的 metricKey 就是量纲枚举名`() {
        // 与历史 metric.bucket 列取值一致，是"历史数据零迁移"的依据
        MetricBucket.entries.forEach { bucket ->
            assertEquals(bucket.name, MetricSpec.of(bucket).metricKey)
            assertEquals(bucket, MetricSpec.of(bucket).bucket)
        }
    }

    @Test
    fun `metricKey 可以反解回量纲`() {
        assertEquals(MetricBucket.STEPS, MetricSpec.bucketOf("STEPS"))
        assertEquals(MetricBucket.SLEEP_MINUTES, MetricSpec.bucketOf("SLEEP_MINUTES"))
        assertEquals(MetricBucket.DURATION_MINUTES, MetricSpec.bucketOf("DURATION_MINUTES"))
    }

    @Test
    fun `未知 metricKey 返回 null 而不是默认量纲`() {
        // 静默给个默认量纲会让数据落到错误的桶里，比报错更难查
        assertNull(MetricSpec.bucketOf("sleep_minutes"))
        assertNull(MetricSpec.bucketOf(""))
        assertNull(MetricSpec.bucketOf("UNKNOWN"))
    }

    @Test
    fun `指标规格的单位默认取量纲单位`() {
        assertEquals("步", MetricSpec.of(MetricBucket.STEPS).unit)
        assertEquals("分钟", MetricSpec.of(MetricBucket.SLEEP_MINUTES).unit)
        assertEquals("次", MetricSpec.of(MetricBucket.COUNT).unit)
        assertEquals("", MetricSpec.of(MetricBucket.FLAG).unit)
    }
}

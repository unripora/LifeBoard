package com.lifeboard.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 改造前的基线锁定。
 *
 * 改造 `SourceRef`（新增 Plugin 分支、key 生成集中化）时必须保证：
 * 写入 `metric.sourceType` 的键值与历史数据**逐字一致**，
 * 否则老数据在读的时候会解析不到、面板静默显示 0。
 *
 * 这个文件里的期望值全部是"改造前代码实际会产出什么"的快照，
 * 后续任何改动让它变红，都意味着破坏了对历史数据的兼容。
 */
class SourceRefBaselineTest {

    // ---------- 内置源 key = 枚举名（历史写入值） ----------

    @Test
    fun `内置源 key 等于枚举名`() {
        SourceType.entries.forEach { type ->
            assertEquals(type.name, SourceRef.Builtin(type).key)
        }
    }

    @Test
    fun `内置源 key 的关键取值固定`() {
        // 这几个是历史库里真实出现过的 sourceType 值，一个都不能变
        assertEquals("SCREEN_TIME", SourceRef.Builtin(SourceType.SCREEN_TIME).key)
        assertEquals("MI_HEALTH", SourceRef.Builtin(SourceType.MI_HEALTH).key)
        assertEquals("FEISHU_ATTENDANCE", SourceRef.Builtin(SourceType.FEISHU_ATTENDANCE).key)
        assertEquals("DUOLINGO", SourceRef.Builtin(SourceType.DUOLINGO).key)
        assertEquals("MANUAL", SourceRef.Builtin(SourceType.MANUAL).key)
    }

    // ---------- 自建源 key = CUSTOM_{id}（历史写入值） ----------

    @Test
    fun `自建源 key 为 CUSTOM_ 加 id`() {
        assertEquals("CUSTOM_1", SourceRef.Custom(1L, "每日背单词", MetricBucket.COUNT).key)
        assertEquals("CUSTOM_42", SourceRef.Custom(42L, "喝水", MetricBucket.COUNT).key)
    }

    @Test
    fun `自建源 key 可以反解回 id`() {
        assertEquals(12L, SourceRef.parseCustomId("CUSTOM_12"))
        assertEquals(0L, SourceRef.parseCustomId("CUSTOM_0"))
    }

    @Test
    fun `自建源 key 的 round-trip 稳定`() {
        val ref = SourceRef.Custom(7L, "阅读", MetricBucket.DURATION_MINUTES)
        assertEquals(ref.id, SourceRef.parseCustomId(ref.key))
    }

    @Test
    fun `非自建源 key 解析为 null 而不是降级成 0`() {
        assertNull(SourceRef.parseCustomId("SCREEN_TIME"))
        assertNull(SourceRef.parseCustomId("MI_HEALTH"))
        assertNull(SourceRef.parseCustomId(""))
    }

    // ---------- 默认量纲（决定 metric 表 bucket 列的取值） ----------

    @Test
    fun `内置源默认量纲与历史一致`() {
        assertEquals(MetricBucket.DURATION_MINUTES, SourceRef.defaultBucket(SourceType.SCREEN_TIME))
        assertEquals(MetricBucket.STEPS, SourceRef.defaultBucket(SourceType.MI_HEALTH))
        assertEquals(MetricBucket.COUNT, SourceRef.defaultBucket(SourceType.DUOLINGO))
        assertEquals(MetricBucket.FLAG, SourceRef.defaultBucket(SourceType.FEISHU_ATTENDANCE))
        assertEquals(MetricBucket.FLAG, SourceRef.defaultBucket(SourceType.MANUAL))
    }

    @Test
    fun `量纲名就是写入 metric 表 bucket 列的值`() {
        // 历史数据里 bucket 列存的是枚举名，改造后必须继续存枚举名
        assertEquals("STEPS", MetricBucket.STEPS.name)
        assertEquals("SLEEP_MINUTES", MetricBucket.SLEEP_MINUTES.name)
        assertEquals("DURATION_MINUTES", MetricBucket.DURATION_MINUTES.name)
        assertEquals("FLAG", MetricBucket.FLAG.name)
        assertEquals("COUNT", MetricBucket.COUNT.name)
    }

    @Test
    fun `全部量纲都有单位文案`() {
        assertTrue(MetricBucket.DURATION_MINUTES.unit.isNotEmpty())
        assertTrue(MetricBucket.SLEEP_MINUTES.unit.isNotEmpty())
        assertTrue(MetricBucket.STEPS.unit.isNotEmpty())
        assertTrue(MetricBucket.COUNT.unit.isNotEmpty())
        // FLAG 是布尔型，没有单位是历史既定行为
        assertEquals("", MetricBucket.FLAG.unit)
    }
}

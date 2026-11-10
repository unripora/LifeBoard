package com.lifeboard.plugin.mihealth

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采样点解析规则的测试。
 *
 * 放在 androidTest 而不是 JVM 单测：解析依赖 `org.json`，
 * 而 Android 提供的这份实现在本地 JVM 里只是空壳（会抛 "not mocked"），
 * 换到仪器测试就能用真实实现验证，不必为一个纯逻辑引入额外依赖。
 *
 * 只覆盖「字段怎么从响应里读出来」，不需要账号或网络。
 */
@RunWith(AndroidJUnit4::class)
class MiHealthParsingTest {

    @Test
    fun stepsFromInnerValueJson() {
        val point = JSONObject()
            .put("time", 1_700_000_000_000L)
            .put("value", """{"steps":123,"calories":45.5}""")

        assertEquals(123.0, MiHealthParser.number(point, "steps", "value")!!, 0.0)
    }

    @Test
    fun fallbackToOuterFieldWhenValueIsNotJson() {
        val point = JSONObject()
            .put("time", 1_700_000_000_000L)
            .put("value", "not-json")
            .put("steps", 88)

        assertEquals(88.0, MiHealthParser.number(point, "steps")!!, 0.0)
    }

    @Test
    fun missingNumberReturnsNullInsteadOfZero() {
        val point = JSONObject().put("time", 1_700_000_000_000L).put("value", """{"foo":1}""")

        assertNull(MiHealthParser.number(point, "steps", "step", "count"))
    }

    @Test
    fun sleepRangeFromInnerSnakeCaseFields() {
        val point = JSONObject().put(
            "value",
            """{"bedtime":1700000000000,"wake_up_time":1700028800000}""",
        )

        assertEquals(
            1_700_000_000_000L,
            MiHealthParser.timestamp(point, "bedtime", "start_time")!!,
        )
        assertEquals(
            1_700_028_800_000L,
            MiHealthParser.timestamp(point, "wake_up_time", "end_time")!!,
        )
    }

    @Test
    fun secondTimestampsAreNormalizedToMillis() {
        val point = JSONObject().put("value", """{"start_time":1700000000,"end_time":1700028800}""")

        assertEquals(1_700_000_000_000L, MiHealthParser.timestamp(point, "start_time")!!)
    }

    @Test
    fun numericStringIsParsed() {
        val point = JSONObject().put("value", """{"steps":"42"}""")

        assertEquals(42.0, MiHealthParser.number(point, "steps")!!, 0.0)
    }

    @Test
    fun blankValueFallsBackToOuterObject() {
        val point = JSONObject().put("startTime", 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, MiHealthParser.timestamp(point, "startTime")!!)
    }
}

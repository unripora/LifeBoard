package com.lifeboard.server.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动刷新周期的取值规则。
 *
 * 纯逻辑：只验证枚举本身的映射与回退，不碰 WorkManager。
 * 「偏好写入后任务是否真的重排/取消」依赖 WorkManager，属于真机验证范围。
 */
class RefreshIntervalTest {

    @Test
    fun default_isDaily() {
        // 保持改造前的行为：默认仍然一天一次
        assertEquals(RefreshInterval.DAILY, RefreshInterval.DEFAULT)
        assertEquals(RefreshInterval.DAILY, RefreshInterval.fromName(null))
    }

    @Test
    fun unknownValue_fallsBackToDefault() {
        // 降级回旧版本后可能留下不认识的字符串，不能因此让调度直接崩掉
        assertEquals(RefreshInterval.DAILY, RefreshInterval.fromName("FORTNIGHTLY"))
        assertEquals(RefreshInterval.DAILY, RefreshInterval.fromName(""))
    }

    @Test
    fun knownValue_isParsed() {
        RefreshInterval.entries.forEach {
            assertEquals(it, RefreshInterval.fromName(it.name))
        }
    }

    @Test
    fun everyEnabledInterval_meetsWorkManagerMinimum() {
        // WorkManager 周期任务下限是 15 分钟，低于它的设置会被系统直接拒绝
        RefreshInterval.entries.filter { it.enabled }.forEach {
            assertTrue("${it.name} 的周期短于 15 分钟", it.minutes >= 15)
        }
    }

    @Test
    fun off_isDisabled() {
        assertFalse(RefreshInterval.OFF.enabled)
        assertEquals(0L, RefreshInterval.OFF.minutes)

        // 其余选项都必须能正常调度，否则用户选了却没有任何效果
        RefreshInterval.entries.filter { it != RefreshInterval.OFF }.forEach {
            assertTrue(it.enabled)
        }
    }
}

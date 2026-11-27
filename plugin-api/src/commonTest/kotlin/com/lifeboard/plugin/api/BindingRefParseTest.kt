package com.lifeboard.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绑定引用的解析规则。
 *
 * 三段式是设计核心：不允许表达式语法，只允许「取某个数据源的某个具名字段」。
 * 这里钉的是「哪些写法算格式错、哪些不算」，以及「无实例 key 的源怎么表达」。
 *
 * 用 JUnit 而非 kotlin-test 断言：本模块的 `commonTest` 只声明了 `libs.junit`，
 * 契约模块「零第三方依赖」的约束不允许为了一条测试去加依赖。
 */
class BindingRefParseTest {

    @Test
    fun parsesThreeSegmentRef() {
        val ref = BindingRef.parse("session:main:messages")
        assertNotNull(ref)
        requireNotNull(ref)
        assertEquals("session", ref.source)
        assertEquals("main", ref.key)
        assertEquals("messages", ref.field)
        assertFalse(ref.isKeyless)
    }

    /**
     * **两段式必须是格式错。**
     *
     * 既有契约一直如此，且 `submitTo` 已经占用了「两段」这个形态（第二段是
     * 实例 key，不是字段名）。若在 `parse` 里放宽成两段，`session:only-two`
     * 这种拼错会静默变成「会话 only-two 的字段 two」，排查方向全错。
     */
    @Test
    fun rejectsTwoSegmentRef() {
        assertNull(BindingRef.parse("sessionList:items"))
        assertNull(BindingRef.parse("session:only-two"))
    }

    /**
     * 没有实例 key 的源用**空 key 的三段式**表达，由 [BindingRef.keyless] 构造。
     *
     * 为什么不写 `sessionList:*:items`：`*` 看起来像通配符，会让人以为它有
     * 匹配语义，而这里表达的是「不存在这个概念」。
     */
    @Test
    fun keylessRefMarksItselfAsKeyless() {
        val ref = BindingRef.keyless("sessionList", "items")
        assertEquals("sessionList", ref.source)
        assertEquals("", ref.key)
        assertEquals("items", ref.field)
        assertTrue(ref.isKeyless)
    }

    /**
     * `canonical()` 与 [BindingRef.parse] 之间要保持「能原地重建」。
     *
     * 求值链路是「解析 → 规范化 → 再解析」。无 key 的引用规范化后必须仍能被
     * [BindingRef.keyless] 之外的方式识别出「这是无 key 的」——这里断言它
     * 输出 `source::field`（中间为空），而不是塌成两段式。
     */
    @Test
    fun canonicalOfKeylessRefKeepsThreeSegments() {
        assertEquals("sessionList::items", BindingRef.keyless("sessionList", "items").canonical())
    }

    @Test
    fun canonicalOfKeyedRefIsUnchanged() {
        val ref = requireNotNull(BindingRef.parse("session:main:messages"))
        assertEquals("session:main:messages", ref.canonical())
        assertEquals(ref, BindingRef.parse(ref.canonical()))
    }

    @Test
    fun rejectsTooManySegments() {
        assertNull(BindingRef.parse("session:main:messages:extra"))
    }

    @Test
    fun rejectsEmptySegments() {
        // source 与 field 不能为空
        assertNull(BindingRef.parse(":main:messages"))
        assertNull(BindingRef.parse("session:main:"))
        assertNull(BindingRef.parse("session:"))
        assertNull(BindingRef.parse(":items"))
    }

    /**
     * 中间段为空是**合法**的：它表示「这个源没有实例 key」。
     *
     * 这条与上一条的差别就在中间那一段：两端为空是写法错误（少写了东西），
     * 中间为空是一个有意义的状态（清单源不属于任何实例）。
     */
    @Test
    fun allowsEmptyKeyAsKeylessMarker() {
        val ref = BindingRef.parse("sessionList::items")
        assertNotNull(ref)
        requireNotNull(ref)
        assertEquals("sessionList", ref.source)
        assertTrue(ref.isKeyless)
        assertEquals("items", ref.field)
    }

    @Test
    fun rejectsSingleSegment() {
        assertNull(BindingRef.parse("session"))
    }

    /** `submitTo` 的两段式语义不同：那里的第二段是**实例 key**，不是字段名 */
    @Test
    fun parseTargetKeepsSecondSegmentAsKey() {
        val target = BindingRef.parseTarget("session:main")
        assertNotNull(target)
        requireNotNull(target)
        assertEquals("session", target.source)
        assertEquals("main", target.key)
        assertEquals(BindingRef.FIELD_SUBMIT, target.field)
        assertFalse(target.isKeyless)
    }
}

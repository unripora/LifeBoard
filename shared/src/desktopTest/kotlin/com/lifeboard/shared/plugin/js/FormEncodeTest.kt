package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 守住 [JsHttpBridge.encodeForm] 的表单编码。
 *
 * 这组断言来自一个反复出现的坑：每个写插件的作者都要自己抄一份 `formEncode`，
 * 而抄错的版本在服务端看来只是「参数不对」——HTTP 200、业务码含糊，
 * 排查成本极高。把编码收回宿主，就必须把规则钉死在这里。
 *
 * 放在 `shared` 的测试源集而不是 `app`：`encodeForm` 是 `internal`，
 * 它在共享层的可见性就把测试位置定死了。
 */
class FormEncodeTest {

    /** 普通对象按键值转成 a=1&b=2 */
    @Test
    fun encodesObject() {
        val out = JsHttpBridge.encodeForm(
            PluginJsonObject().apply {
                put("user", "abc")
                put("sid", "demo")
            },
        )

        // 不假设键顺序：PluginJsonObject 的底层实现两端不同，顺序都不保证
        assertEquals(setOf("user=abc", "sid=demo"), out!!.split("&").toSet())
    }

    /** 空格按 HTML 表单规则编成 `+`，不是 `%20` */
    @Test
    fun spaceBecomesPlus() {
        val out = JsHttpBridge.encodeForm(PluginJsonObject("""{"q":"hello world"}"""))

        assertEquals("q=hello+world", out)
    }

    /** 保留字以外的字符必须百分号编码，中文按 UTF-8 */
    @Test
    fun encodesReservedAndNonAscii() {
        val out = JsHttpBridge.encodeForm(PluginJsonObject("""{"k":"a&b=c"}"""))

        assertEquals("k=a%26b%3Dc", out)

        val cn = JsHttpBridge.encodeForm(PluginJsonObject("""{"k":"中"}"""))
        assertEquals("k=%E4%B8%AD", cn)
    }

    /** `+` 本身要被编码，否则服务端会把它解成空格 */
    @Test
    fun plusIsEncoded() {
        val out = JsHttpBridge.encodeForm(PluginJsonObject("""{"k":"1+1"}"""))

        assertEquals("k=1%2B1", out)
    }

    /** null 发空串，不能发字面量 "null" */
    @Test
    fun nullValueBecomesEmptyString() {
        val params = PluginJsonObject().apply { putNull("k") }

        assertEquals("k=", JsHttpBridge.encodeForm(params))
    }

    /** 整数值不带小数点：`1.0` 要发成 `1`，很多服务端按整数校验 */
    @Test
    fun integralNumbersDropDecimalPoint() {
        val out = JsHttpBridge.encodeForm(PluginJsonObject("""{"n":42.0,"m":1.5}"""))

        val parts = out!!.split("&").toSet()
        assertEquals(setOf("n=42", "m=1.5"), parts)
    }

    /** 二元组数组支持同名参数，这是对象表达不了的 */
    @Test
    fun supportsRepeatedKeys() {
        val pairs = PluginJsonArray().apply {
            put(PluginJsonArray(listOf("tag", "a")))
            put(PluginJsonArray(listOf("tag", "b")))
        }

        assertEquals("tag=a&tag=b", JsHttpBridge.encodeForm(pairs))
    }

    /** 非法入参返回 null，由调用方转成明确报错 */
    @Test
    fun unsupportedInputReturnsNull() {
        assertNull(JsHttpBridge.encodeForm("not-an-object"))
        assertNull(JsHttpBridge.encodeForm(null))
    }

    /** 空对象编成空串，得到合法的空表单体而不是 "null" */
    @Test
    fun emptyObjectEncodesToEmptyString() {
        assertEquals("", JsHttpBridge.encodeForm(PluginJsonObject()))
    }
}

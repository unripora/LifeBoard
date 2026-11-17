package com.lifeboard.shared.plugin.json

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 侧实现：**直接委托** `org.json`。
 *
 * 这是刻意的零行为变化——现有插件链路（小米健康）已在 `org.json` 上跑通，
 * 共享层改造不该顺带改动它的解析语义。
 */
actual class PluginJsonObject internal constructor(internal val delegate: JSONObject) {

    actual constructor() : this(JSONObject())

    actual constructor(json: String) : this(JSONObject(json))

    actual fun has(key: String): Boolean = delegate.has(key)

    actual fun isNull(key: String): Boolean = delegate.isNull(key)

    actual fun optString(key: String): String = delegate.optString(key)

    actual fun optString(key: String, fallback: String): String = delegate.optString(key, fallback)

    actual fun optInt(key: String): Int = delegate.optInt(key)

    actual fun optInt(key: String, fallback: Int): Int = delegate.optInt(key, fallback)

    actual fun optLong(key: String): Long = delegate.optLong(key)

    actual fun optLong(key: String, fallback: Long): Long = delegate.optLong(key, fallback)

    actual fun optDouble(key: String): Double = delegate.optDouble(key)

    actual fun optDouble(key: String, fallback: Double): Double = delegate.optDouble(key, fallback)

    actual fun optBoolean(key: String, fallback: Boolean): Boolean =
        delegate.optBoolean(key, fallback)

    actual fun optJSONObject(key: String): PluginJsonObject? =
        delegate.optJSONObject(key)?.let(::PluginJsonObject)

    actual fun optJSONArray(key: String): PluginJsonArray? =
        delegate.optJSONArray(key)?.let(::PluginJsonArray)

    actual fun keys(): Set<String> = delegate.keys().asSequence().toSet()

    actual fun put(key: String, value: String) {
        delegate.put(key, value)
    }

    actual fun put(key: String, value: PluginJsonObject) {
        delegate.put(key, value.delegate)
    }

    actual fun put(key: String, value: PluginJsonArray) {
        delegate.put(key, value.delegate)
    }

    actual fun putNull(key: String) {
        delegate.put(key, JSONObject.NULL)
    }

    actual fun opt(key: String): Any? = delegate.opt(key).let(::unwrapValue)

    actual override fun toString(): String = delegate.toString()

    actual companion object {
        actual val NULL: Any = JSONObject.NULL
    }
}

actual class PluginJsonArray internal constructor(internal val delegate: JSONArray) {

    actual constructor() : this(JSONArray())

    actual constructor(json: String) : this(JSONArray(json))

    actual constructor(strings: List<String>) : this(JSONArray(strings))

    actual fun length(): Int = delegate.length()

    actual fun optJSONObject(index: Int): PluginJsonObject? =
        delegate.optJSONObject(index)?.let(::PluginJsonObject)

    actual fun optJSONArray(index: Int): PluginJsonArray? =
        delegate.optJSONArray(index)?.let(::PluginJsonArray)

    actual fun optStringOrNull(index: Int): String? =
        delegate.opt(index) as? String

    actual fun optDouble(index: Int, fallback: Double): Double =
        delegate.optDouble(index, fallback)

    actual fun optInt(index: Int, fallback: Int): Int = delegate.optInt(index, fallback)

    actual fun optBoolean(index: Int, fallback: Boolean): Boolean =
        delegate.optBoolean(index, fallback)

    actual fun isNull(index: Int): Boolean = delegate.isNull(index)

    actual fun opt(index: Int): Any? = delegate.opt(index).let(::unwrapValue)

    actual fun put(value: PluginJsonObject) {
        delegate.put(value.delegate)
    }

    actual fun put(value: PluginJsonArray) {
        delegate.put(value.delegate)
    }

    actual fun put(value: String) {
        delegate.put(value)
    }

    actual override fun toString(): String = delegate.toString()
}

actual object PluginJson {
    actual fun quote(value: String): String = JSONObject.quote(value)
}

/**
 * 把 `org.json` 的原始值翻成契约里约定的那几种类型。
 *
 * 嵌套对象/数组要包成 [PluginJsonObject]/[PluginJsonArray]，
 * 否则调用方拿到裸的 `JSONObject` 就绕过了抽象层。
 */
private fun unwrapValue(value: Any?): Any? = when (value) {
    null -> null
    is JSONObject -> PluginJsonObject(value)
    is JSONArray -> PluginJsonArray(value)
    else -> value
}

package com.lifeboard.shared.plugin.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 桌面侧实现：基于 kotlinx.serialization 模拟 `org.json` 的 `opt*` 语义。
 *
 * ## 为什么要「模拟」而不是直接用 JsonElement
 *
 * 共享层的转换代码是从 `org.json` 版本原样搬过来的，语义必须逐条对齐，
 * 否则同一个插件在两端表现不同。`opt*` 家族的容错规则（缺字段给空串、
 * 非数字给 NaN、类型不符给 null）是这个插件契约的一部分，不是实现细节。
 *
 * ## 可变性
 *
 * `org.json` 的 `JSONObject` / `JSONArray` 是可变的，现有代码依赖这一点
 * （先 `JSONObject()` 再逐个 `put`）。kotlinx 的 `JsonObject` 不可变，
 * 所以内部用 `LinkedHashMap` / `ArrayList` 自己维护，仅在 `toString` 时转成 `JsonElement`。
 */
private val pluginJson = Json {
    // 插件可能返回带尾随逗号或注释的 JSON（手拼字符串时很常见）。
    // 宽松一点，宁可多解析成功一批，也不要让用户面对「非法结果」而无从下手
    isLenient = true
    ignoreUnknownKeys = true
}

actual class PluginJsonObject actual constructor() {

    private val map: MutableMap<String, JsonElement> = LinkedHashMap()

    actual constructor(json: String) : this() {
        val parsed = pluginJson.parseToJsonElement(json) as? JsonObject
            ?: error("不是 JSON 对象")
        map.putAll(parsed)
    }

    /** 从已有 map 构造；供嵌套解析复用，避免二次序列化 */
    internal constructor(source: Map<String, JsonElement>) : this() {
        map.putAll(source)
    }

    actual fun has(key: String): Boolean = map.containsKey(key)

    actual fun isNull(key: String): Boolean = map[key] is JsonNull

    actual fun optString(key: String): String = optString(key, "")

    actual fun optString(key: String, fallback: String): String {
        val primitive = map[key] as? JsonPrimitive ?: return fallback
        return primitive.content
    }

    actual fun optInt(key: String): Int = optInt(key, 0)

    actual fun optInt(key: String, fallback: Int): Int {
        val primitive = map[key] as? JsonPrimitive ?: return fallback
        primitive.intOrNull?.let { return it }
        // `org.json` 接受字符串形态的数字；JS 侧手拼 JSON 时确实会出现 "5000"
        return primitive.content.toIntOrNull() ?: fallback
    }

    actual fun optLong(key: String): Long = optLong(key, 0L)

    actual fun optLong(key: String, fallback: Long): Long {
        val primitive = map[key] as? JsonPrimitive ?: return fallback
        primitive.longOrNull?.let { return it }
        return primitive.content.toLongOrNull() ?: fallback
    }

    actual fun optDouble(key: String): Double = optDouble(key, Double.NaN)

    actual fun optDouble(key: String, fallback: Double): Double {
        val primitive = map[key] as? JsonPrimitive ?: return fallback
        primitive.doubleOrNull?.let { return it }
        return primitive.content.toDoubleOrNull() ?: fallback
    }

    actual fun optBoolean(key: String, fallback: Boolean): Boolean {
        val primitive = map[key] as? JsonPrimitive ?: return fallback
        primitive.booleanOrNull?.let { return it }
        // `org.json` 会把 "true"/"false" 字符串也认成布尔
        return when (primitive.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> fallback
        }
    }

    actual fun optJSONObject(key: String): PluginJsonObject? =
        (map[key] as? JsonObject)?.let { PluginJsonObject(it) }

    actual fun optJSONArray(key: String): PluginJsonArray? =
        (map[key] as? JsonArray)?.let { PluginJsonArray(it, Unit) }

    actual fun keys(): Set<String> = map.keys

    actual fun put(key: String, value: String) {
        map[key] = JsonPrimitive(value)
    }

    actual fun put(key: String, value: Int) {
        map[key] = JsonPrimitive(value)
    }

    actual fun put(key: String, value: Double) {
        map[key] = JsonPrimitive(value)
    }

    actual fun put(key: String, value: Boolean) {
        map[key] = JsonPrimitive(value)
    }

    actual fun put(key: String, value: PluginJsonObject) {
        map[key] = value.toJsonElement()
    }

    actual fun put(key: String, value: PluginJsonArray) {
        map[key] = value.toJsonElement()
    }

    actual fun putNull(key: String) {
        map[key] = JsonNull
    }

    /**
     * 取出原始值。
     *
     * 返回值形态要和 Android 侧严格一致，否则桥接层的分支判断会在两端走向不同路径：
     * 数字统一给 `Double`（`org.json` 也把整数读成数字）、布尔给 `Boolean`、
     * 字符串给 `String`、嵌套结构包成插件层类型、显式 null 给 [PluginJsonObject.NULL]。
     */
    actual fun opt(key: String): Any? = when (val value = map[key]) {
        null -> null
        is JsonNull -> PluginJsonObject.NULL
        is JsonObject -> PluginJsonObject(value)
        is JsonArray -> PluginJsonArray(value, Unit)
        is JsonPrimitive -> when {
            value.booleanOrNull != null -> value.booleanOrNull
            value.doubleOrNull != null -> value.doubleOrNull
            else -> value.content
        }
        else -> value.toString()
    }

    actual override fun toString(): String = toJsonElement().toString()

    internal fun toJsonElement(): JsonObject = JsonObject(map.toMap())

    actual companion object {
        actual val NULL: Any = JsonNull
    }
}

actual class PluginJsonArray actual constructor() {

    private val list: MutableList<JsonElement> = ArrayList()

    actual constructor(json: String) : this() {
        val parsed = pluginJson.parseToJsonElement(json) as? JsonArray
            ?: error("不是 JSON 数组")
        list.addAll(parsed)
    }

    actual constructor(strings: List<String>) : this() {
        strings.forEach { list.add(JsonPrimitive(it)) }
    }

    internal constructor(source: List<JsonElement>, marker: Unit) : this() {
        list.addAll(source)
    }

    actual fun length(): Int = list.size

    actual fun optJSONObject(index: Int): PluginJsonObject? =
        (list.getOrNull(index) as? JsonObject)?.let { PluginJsonObject(it) }

    actual fun optJSONArray(index: Int): PluginJsonArray? =
        (list.getOrNull(index) as? JsonArray)?.let { PluginJsonArray(it, Unit) }

    // 只有 JSON 字符串才算字符串。这里不能只看 JsonPrimitive：
    // JsonNull 也是 JsonPrimitive（content 是 "null"），数字/布尔也有 content，
    // 早期写法会把 null 读成 "null"、把 123 读成 "123"，与 Android 侧
    // `opt(index) as? String` 以及本类的契约都不一致
    actual fun optStringOrNull(index: Int): String? {
        val primitive = list.getOrNull(index) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    actual fun optDouble(index: Int, fallback: Double): Double {
        val primitive = list.getOrNull(index) as? JsonPrimitive ?: return fallback
        primitive.doubleOrNull?.let { return it }
        return primitive.content.toDoubleOrNull() ?: fallback
    }

    actual fun optInt(index: Int, fallback: Int): Int {
        val primitive = list.getOrNull(index) as? JsonPrimitive ?: return fallback
        primitive.intOrNull?.let { return it }
        primitive.content.toIntOrNull()?.let { return it }
        return primitive.content.toDoubleOrNull()?.toInt() ?: fallback
    }

    actual fun optBoolean(index: Int, fallback: Boolean): Boolean {
        val primitive = list.getOrNull(index) as? JsonPrimitive ?: return fallback
        primitive.booleanOrNull?.let { return it }
        return when (primitive.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> fallback
        }
    }

    actual fun isNull(index: Int): Boolean =
        index !in list.indices || list[index] is JsonNull

    actual fun opt(index: Int): Any? = when (val value = list.getOrNull(index)) {
        null -> null
        is JsonNull -> PluginJsonObject.NULL
        is JsonObject -> PluginJsonObject(value)
        is JsonArray -> PluginJsonArray(value, Unit)
        is JsonPrimitive -> when {
            value.booleanOrNull != null -> value.booleanOrNull
            value.doubleOrNull != null -> value.doubleOrNull
            else -> value.content
        }
        else -> value.toString()
    }

    actual fun put(value: PluginJsonObject) {
        list.add(value.toJsonElement())
    }

    actual fun put(value: PluginJsonArray) {
        list.add(value.toJsonElement())
    }

    actual fun put(value: String) {
        list.add(JsonPrimitive(value))
    }

    actual fun put(value: Double) {
        list.add(JsonPrimitive(value))
    }

    actual fun put(value: Int) {
        list.add(JsonPrimitive(value))
    }

    actual fun put(value: Boolean) {
        list.add(JsonPrimitive(value))
    }

    actual override fun toString(): String = toJsonElement().toString()

    internal fun toJsonElement(): JsonArray = JsonArray(list.toList())
}

actual object PluginJson {
    actual fun quote(value: String): String = JsonPrimitive(value).toString()
}

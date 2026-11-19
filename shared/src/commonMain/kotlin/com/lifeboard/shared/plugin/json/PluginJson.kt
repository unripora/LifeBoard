package com.lifeboard.shared.plugin.json

/**
 * 插件层使用的最小 JSON 抽象。
 *
 * ## 为什么要有这一层
 *
 * JS 插件运行时要跨 Android 与桌面共用，而现有实现大量使用 Android 框架自带的
 * `org.json`。桌面 JVM 上没有这个类，所以必须收口。
 *
 * ## 为什么不直接换成 kotlinx.serialization
 *
 * `JsTypeConverter` 有五百多行逐字段兜底逻辑：`optString` 缺字段给空串、
 * `optDouble` 类型不对给 NaN、`optJSONArray` 不是数组给 null……这些行为是插件的
 * **容错边界**，插件是外部写的，不能假设它给出合法结构。整体重写成 `JsonElement`
 * 风格等于把已跑通的容错推倒重来，收益只是少一层抽象，代价是线上插件要重新排查一轮。
 *
 * 所以做法是「保留语义、换掉实现」：接口刻意贴近 `org.json` 的 `opt*` 家族，
 * Android 侧委托 `org.json`（零行为变化），桌面侧用 kotlinx.serialization 模拟同样的语义。
 *
 * ## 语义约定（两端必须一致）
 *
 * - `optString(key)` 缺字段 / 值为 null → 空串
 * - `optString(key, fallback)` 同理 → `fallback`
 * - `optDouble(key)` 非数字 → `NaN`
 * - `optInt` / `optLong` 非数字 → `0`
 * - `optBoolean(key, fallback)` 非布尔 → `fallback`
 * - `optJSONObject` / `optJSONArray` 类型不符 → `null`
 * - `has(key)` 只看键是否存在，值为 null 也算存在
 *
 * 这些约定由两端的 `PluginJsonTest` 共同保证，不靠人工对照。
 */
expect class PluginJsonObject() {

    /** 从 JSON 文本解析；不是合法对象时抛异常，调用方用 `runCatching` 兜住 */
    constructor(json: String)

    fun has(key: String): Boolean

    /** 值是否为 JSON null（键不存在时 `false`，与 `org.json` 一致） */
    fun isNull(key: String): Boolean

    fun optString(key: String): String

    fun optString(key: String, fallback: String): String

    fun optInt(key: String): Int

    fun optInt(key: String, fallback: Int): Int

    fun optLong(key: String): Long

    fun optLong(key: String, fallback: Long): Long

    fun optDouble(key: String): Double

    fun optDouble(key: String, fallback: Double): Double

    fun optBoolean(key: String, fallback: Boolean): Boolean

    fun optJSONObject(key: String): PluginJsonObject?

    fun optJSONArray(key: String): PluginJsonArray?

    /** 所有键。顺序不保证 */
    fun keys(): Set<String>

    /** 写入一个字符串值 */
    fun put(key: String, value: String)

    /**
     * 写入一个整数值。
     *
     * **必须与 [put] 的字符串重载并存**：HTTP 桥接把响应状态码交给插件的
     * `res.status`，若写成字符串 `"401"`，插件里 `res.status === 401`
     * 恒为假、`res.status >= 300` 却为真，同一份代码在不同判断下给出矛盾结果。
     */
    fun put(key: String, value: Int)

    /** 写入一个浮点值（指标数值实际都是 `Double`）。 */
    fun put(key: String, value: Double)

    /**
     * 写入一个布尔值。
     *
     * **不能用字符串代替**：`res.ok` 若下发 `"false"`，插件里 `!res.ok`
     * 求值为假（非空字符串是真值），于是 401 被当成成功继续解析响应体，
     * 最终报「JSON 格式错误」而不是「凭据失效」——错误信息与真实原因完全脱节。
     */
    fun put(key: String, value: Boolean)

    /** 写入一个对象值 */
    fun put(key: String, value: PluginJsonObject)

    /** 写入一个数组值 */
    fun put(key: String, value: PluginJsonArray)

    /**
     * 显式写入 JSON null。
     *
     * 与「不写这个键」是两回事：桥接层给插件返回响应时，
     * `bodyBase64` / `json` 在不需要时是 null，插件那边 `'json' in resp`
     * 仍应为真，否则插件无从区分「字段没给」与「解析失败」。
     */
    fun putNull(key: String)

    /**
     * 取出原始值（不做类型转换）。
     *
     * 用于 HTTP 桥接层把插件的调用参数原样转交给表单编码：
     * 那里需要区分「值是字符串」与「值是数字」，`optString` 会把两者都变字符串。
     *
     * 返回类型约定：`String` / `Double` / `Boolean` / [PluginJsonObject] /
     * [PluginJsonArray] / `null`（键不存在） / [NULL]（显式 JSON null）。
     */
    fun opt(key: String): Any?

    override fun toString(): String

    companion object {
        /** JSON 的 `null` 字面量。用于「显式写入 null」与「判断值是否为 null」 */
        val NULL: Any
    }
}

expect class PluginJsonArray() {

    constructor(json: String)

    /** 用一组字符串构造 */
    constructor(strings: List<String>)

    fun length(): Int

    fun optJSONObject(index: Int): PluginJsonObject?

    fun optJSONArray(index: Int): PluginJsonArray?

    /** 索引处若不是字符串，返回 null（与 `org.json` 的 `optString(0)` 返回空串不同，这里更严格） */
    fun optStringOrNull(index: Int): String?

    fun optDouble(index: Int, fallback: Double): Double

    /** 索引处取整数；非数字给 [fallback]（`org.json` 的 `optInt` 同理） */
    fun optInt(index: Int, fallback: Int): Int

    /** 索引处取布尔；非布尔给 [fallback]（`org.json` 的 `optBoolean` 同理） */
    fun optBoolean(index: Int, fallback: Boolean): Boolean

    /** 索引处是否为 JSON null（索引越界返回 true，与 `org.json` 一致） */
    fun isNull(index: Int): Boolean

    /** 取出原始值，语义同 [PluginJsonObject.opt] */
    fun opt(index: Int): Any?

    fun put(value: PluginJsonObject)

    fun put(value: PluginJsonArray)

    fun put(value: String)

    /**
     * 写入一个数值。
     *
     * 与 [PluginJsonObject.put] 的 Int/Boolean 重载同理：数组里的数值
     * 若以字符串形态下发，插件侧的求和、均值、比较会静默算错。
     */
    fun put(value: Double)

    fun put(value: Int)

    fun put(value: Boolean)

    override fun toString(): String
}

/**
 * 把字符串编码成 JSON 字符串字面量（带引号、转义内部特殊字符）。
 *
 * 拼装 JS 调用参数时到处要用：`"[${PluginJson.quote(s)}]"`。
 */
expect object PluginJson {
    fun quote(value: String): String
}

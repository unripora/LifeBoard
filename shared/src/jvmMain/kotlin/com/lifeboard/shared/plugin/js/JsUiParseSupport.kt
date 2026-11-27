package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.Tone
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject

/**
 * 节点树解析共用的几个小工具：JSON 解析兜底、字段截断、限额判定、枚举解析。
 *
 * ## 为什么单独一个文件
 *
 * 这几个函数既不「属于节点树」（登录/采集解析也要用 JSON 兜底），也不该在
 * [JsTypeConverter] 与 [JsUiNodeParser] 里各写一份——两份同名工具最容易在
 * 一次修改中悄悄分叉，而分叉的表现是「同一段超长文本在两个入口被截成不同长度」，
 * 很难联想到原因。
 *
 * 全部 `internal`：只在插件解析层内部使用，不构成对外契约。
 */

/** 字符串的 UTF-8 字节数（用于限额判定，按字节而非字符） */
internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/**
 * 按 UTF-8 边界截断展示文本。
 *
 * 不能直接 `take(n)` 字符：中文一个字符三字节，按字符截断会让字节数不可控；
 * 也不能按字节硬切：会切坏多字节序列，渲染出替换字符。
 */
internal fun truncateDisplay(text: String, maxBytes: Int): String {
    if (text.utf8Size() <= maxBytes) return text
    var end = text.length
    while (end > 0 && text.substring(0, end).utf8Size() > maxBytes) {
        end--
    }
    AppLogger.warn("展示文本超过 $maxBytes 字节，已按字符边界截断")
    return text.substring(0, end)
}

/**
 * 可选的机器字段：超限返回 null（由调用方丢弃该节点/交互）。
 *
 * 机器字段（id / actionId / binding / submitTo）**绝不截断**：截断后的 id
 * 会指向一个不存在的目标，比丢弃更难排查。
 */
internal fun PluginJsonObject.optMachineString(key: String): String? {
    val value = optStringOrNull(key) ?: return null
    if (value.utf8Size() > JsTypeConverter.MAX_MACHINE_FIELD_BYTES) {
        AppLogger.warn(
            "机器字段 $key 超过 ${JsTypeConverter.MAX_MACHINE_FIELD_BYTES} 字节，已丢弃该节点",
        )
        return null
    }
    return value
}

/** 空串视为「没给」：插件常用 `""` 表达缺省，两者在这里统一 */
internal fun PluginJsonObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).ifBlank { null }
}

internal fun jsonObjectOrNull(json: String): PluginJsonObject? =
    runCatching { PluginJsonObject(json) }.getOrNull()

internal fun jsonArrayOrNull(json: String): PluginJsonArray? =
    runCatching { PluginJsonArray(json) }.getOrNull()

/** 色调解析：认不出按中性处理，绝不因为一个拼错让整块不显示 */
internal fun String.toTone(): Tone =
    Tone.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: Tone.NEUTRAL

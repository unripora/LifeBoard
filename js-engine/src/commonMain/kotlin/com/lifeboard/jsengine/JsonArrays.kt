package com.lifeboard.jsengine

/**
 * 字符串数组的 JSON 编解码。
 *
 * 只处理「字符串数组」这一种形态，刻意不引入 kotlinx.serialization：
 * 用途仅限 native 边界上的模块名/源码数组与导出名列表，
 * 引入完整 JSON 库对这点需求来说代价过大。
 */
internal object JsonArrays {

    fun encode(items: List<String>): String = items.joinToString(",", "[", "]") { quote(it) }

    fun decode(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed == "[]" || trimmed.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        var i = 1 // 跳过 '['
        val end = trimmed.length - 1
        val sb = StringBuilder()
        while (i < end) {
            val c = trimmed[i]
            when {
                c == '"' -> {
                    sb.setLength(0)
                    i++
                    while (i < end && trimmed[i] != '"') {
                        if (trimmed[i] == '\\' && i + 1 < end) {
                            i++
                            when (trimmed[i]) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'u' -> {
                                    // \uXXXX 需要连续读 4 位十六进制
                                    if (i + 4 < end) {
                                        val hex = trimmed.substring(i + 1, i + 5)
                                        sb.append(hex.toInt(16).toChar())
                                        i += 4
                                    }
                                }
                                else -> sb.append(trimmed[i])
                            }
                        } else {
                            sb.append(trimmed[i])
                        }
                        i++
                    }
                    out.add(sb.toString())
                    i++
                }
                else -> i++
            }
        }
        return out
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

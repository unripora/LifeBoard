package com.lifeboard.app.ui.mihealth

import com.lifeboard.server.mihealth.MiHealthCredentials
import org.json.JSONObject

/**
 * 解析用户粘贴的小米健康凭据。
 *
 * 存在的理由：小米对密码登录有风控，短信验证码还有每日次数上限
 * （超了返回 `70022 验证码发送过多，请明天再试`）。用户如果已经通过
 * 其它途径拿到可用凭据，不应该被卡死在登录页。
 *
 * 支持两种文本格式：
 *
 * 1. JSON（`mihealth-api` 的 `config.json` 那种）：
 * ```json
 * {"ssecurity":"...","service_token":"...","cuser_id":"...","pass_token":"..."}
 * ```
 * 2. 每行一个值，顺序固定 `ssecurity / serviceToken / cUserId / passToken`
 */
object MiHealthImport {

    /**
     * @param displayName 用户自己填的显示名称。凭据本身不含任何身份信息
     *   （cUserId 是不透明随机串，反查不出账号），所以界面上要显示「登的是谁」
     *   只能靠用户自己写一个。它优先于 JSON 里的 user_id 等字段。
     */
    fun parse(raw: String, displayName: String = ""): MiHealthCredentials? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val parsed = if (text.startsWith("{")) parseJson(text) else parseLines(text)
        if (parsed == null || displayName.isBlank()) return parsed
        return parsed.copy(account = displayName.trim())
    }

    private fun parseJson(text: String): MiHealthCredentials? = runCatching {
        val o = JSONObject(text)
        val ssecurity = o.firstNonBlank("ssecurity")
        val token = o.firstNonBlank("service_token", "serviceToken", "servicetoken")
        if (ssecurity.isBlank() || token.isBlank()) return null
        MiHealthCredentials(
            ssecurity = ssecurity,
            serviceToken = token,
            cUserId = o.firstNonBlank("cuser_id", "cUserId", "cUserid"),
            passToken = o.firstNonBlank("pass_token", "passToken"),
            // 配置里常带 user_id 或账号，能显示就显示；都没有就留空
            account = o.firstNonBlank("account", "user", "user_name", "user_id", "userId"),
        )
    }.getOrNull()

    private fun parseLines(text: String): MiHealthCredentials? {
        val parts = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return MiHealthCredentials(
            ssecurity = parts[0],
            serviceToken = parts[1],
            cUserId = parts.getOrElse(2) { "" },
            passToken = parts.getOrElse(3) { "" },
            account = parts.getOrElse(4) { "" },
        )
    }

    /** 依次尝试多个字段名，取第一个非空值（各家导出工具的命名不一致） */
    private fun JSONObject.firstNonBlank(vararg names: String): String {
        names.forEach { n ->
            val v = optString(n)
            if (v.isNotBlank()) return v
        }
        return ""
    }
}

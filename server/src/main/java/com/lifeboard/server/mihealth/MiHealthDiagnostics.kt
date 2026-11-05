package com.lifeboard.server.mihealth

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小米健康接口诊断。
 *
 * 存在的理由：这台设备上 App 的 logcat 是空的（`logcat | grep` 只能看到 adb 自己），
 * 接口失败时无法从日志定位。所以把关键信息拼成一段文字显示到界面上：
 * 凭据各字段长度、真实请求的 HTTP 状态码、解密后的业务 code 与条数。
 */
object MiHealthDiagnostics {

    private const val TAG = "LifeBoardMiHealth"

    suspend fun run(context: Context): String {
        val cred = MiHealthCredentialStore.load(context)
            ?: return "诊断：本地无凭据（需要先登录一次）"

        val sb = StringBuilder()
        sb.appendLine("ssecurity=${cred.ssecurity.ifBlank { "空" }.let { if (it == "空") it else "${it.length} 位" }}")
        sb.appendLine("serviceToken=${cred.serviceToken.length} 位")
        sb.appendLine("cUserId=${cred.cUserId.length} 位")
        sb.appendLine("passToken=${cred.passToken.length} 位")

        // 未加密的健康检查端点：能通说明网络没问题，问题在凭据或加解密
        sb.appendLine("网络：" + ping("https://hlth.io.mi.com/app/v1/data/get_max_watermark?type=0"))

        val now = System.currentTimeMillis()
        val data = JSONObject()
            .put("key", "steps")
            .put("startTime", now - 24 * 3600 * 1000L)
            .put("endTime", now)
            .put("reverse", true)
            .put("next_key", JSONObject.NULL)
            .toString()

        val result = runCatching {
            MiHealthClient(
                credentialsProvider = { MiHealthCredentialStore.load(context) },
            ).call(
                method = "GET",
                path = "/app/v1/data/get_fitness_data_by_time",
                params = mapOf("data" to data),
            )
        }.onFailure { Log.w(TAG, "diagnostics request failed", it) }

        sb.appendLine(
            result.fold(
                onSuccess = { j ->
                    if (j == null) {
                        "步数请求：失败（网络不通 / 401 / 响应无法解密）"
                    } else {
                        // 列表在 result 里，不在顶层
                        val body = j.optJSONObject("result") ?: j
                        "步数请求：code=${j.optInt("code", -999)} " +
                            "条数=${body.optJSONArray("data_list")?.length() ?: -1} " +
                            "msg=${j.optString("message", "")}"
                    }
                },
                onFailure = { "步数请求：异常 ${it.javaClass.simpleName} ${it.message}" },
            ),
        )
        return sb.toString().trim()
    }

    /** 明文 GET，只用来判断网络是否通 */
    private fun ping(url: String): String = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        "HTTP $code"
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
}

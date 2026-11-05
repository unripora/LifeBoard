package com.lifeboard.server.mihealth

import android.util.Log
import com.lifeboard.core.mihealth.MiHealthCrypto
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 小米运动健康云端接口客户端。
 *
 * 协议来自 `external/mihealth-api`（Python 参考实现）的复刻，
 * 加解密与签名在 [MiHealthCrypto] 里，本类只负责：
 * 组请求 → 加密参数 → 发送 → 解密响应 → 分页。
 *
 * ## 401 自愈
 * serviceToken 会过期。过期时接口返回 401，此时用 passToken 换一套新凭据
 * 再重试一次。[onCredentialsExpired] 由调用方（[MiHealthAuth]）注入，
 * 避免客户端反向依赖登录逻辑。
 */
class MiHealthClient private constructor(
    private val http: OkHttpClient,
    private val credentialsProvider: () -> MiHealthCredentials?,
    private val onCredentialsExpired: suspend () -> MiHealthCredentials?,
) {
    /**
     * 公开构造：不暴露 OkHttp 类型（它属本模块 implementation 依赖，
     * 出现在跨模块签名上会让 app 侧编译失败）。
     */
    constructor(
        credentialsProvider: () -> MiHealthCredentials?,
        onCredentialsExpired: suspend () -> MiHealthCredentials? = { null },
    ) : this(defaultHttpClient(), credentialsProvider, onCredentialsExpired)

    /** 服务端与客户端的时钟偏差（毫秒），首次 401 时按响应头校正 */
    @Volatile
    private var timeDiff: Long = 0L

    /**
     * 调用一个云端接口并返回解密后的 JSON。
     *
     * @param method GET / POST
     * @param path 形如 `/app/v1/data/get_fitness_data_by_time`
     * @param params 明文参数（Map 会被序列化为表单）
     * @param host 目标域名，默认健康数据域
     * @return 解密后的 JSONObject；401 且续期失败时返回 null
     */
    suspend fun call(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        host: String = HLTH_HOST,
        retryOnAuth: Boolean = true,
    ): JSONObject? {
        val cred = credentialsProvider() ?: run {
            Log.w(TAG, "call($path) skipped: no credentials")
            return null
        }

        val nonce = MiHealthCrypto.genNonce(timeDiff)
        val enc = MiHealthCrypto.signedEncrypted(
            method = method,
            path = path,
            params = params,
            nonce = nonce,
            ssecurity = cred.ssecurity,
        )

        val url = buildUrl(host, path, enc) ?: return null
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", cookieOf(cred))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")

        when (method.uppercase()) {
            "GET" -> builder.get()
            // 加密后的参数已在 URL 上，body 仍需是合法表单
            "POST" -> builder.post(ByteArray(0).toRequestBody(FORM_MEDIA))
            else -> builder.method(method.uppercase(), null)
        }

        val resp = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            Log.w(TAG, "call($path) network error", e)
            return null
        }

        resp.use {
            val code = it.code
            if (code == 401) {
                Log.i(TAG, "call($path) 401, trying to refresh credentials")
                if (!retryOnAuth) return null
                val refreshed = onCredentialsExpired()
                if (refreshed == null) {
                    Log.w(TAG, "call($path) credential refresh failed")
                    return null
                }
                return call(method, path, params, host, retryOnAuth = false)
            }
            if (!it.isSuccessful) {
                Log.w(TAG, "call($path) http $code")
                return null
            }
            val body = it.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "call($path) empty body")
                return null
            }
            return runCatching {
                JSONObject(MiHealthCrypto.decryptResponse(body, nonce, cred.ssecurity))
            }.getOrElse { e ->
                // 解密失败通常意味着 ssecurity 与 serviceToken 不配套（已过期）
                Log.w(TAG, "call($path) decrypt failed, body=${body.take(80)}", e)
                null
            }
        }
    }

    /**
     * 取某类健康数据在指定时间窗内的采样点。
     *
     * ## 分页陷阱（参考实现实测）
     * - 游标参数名必须是 snake_case `next_key`，camelCase 会被服务端忽略
     * - 游标会**向后遍历全部历史**，与 startTime 无关；
     *   把 startTime 传成 1 会让游标卡死，永远返回同一页
     * - 所以这里永远传一个较近的 startTime（默认回看 24 小时），
     *   并在客户端过滤掉窗口外的点
     *
     * @param key 数据键，如 `steps` / `sleep` / `heart_rate`
     * @param startTimeMillis 开始时间（毫秒）
     * @param endTimeMillis 结束时间（毫秒）
     * @param maxPages 分页上限，防止异常情况下无限翻页
     * @return 采样点列表，字段因 key 而异，以 JSONObject 原样返回
     */
    suspend fun getFitnessDataByTime(
        key: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        maxPages: Int = 10,
    ): List<JSONObject> {
        // startTime 钳制：太早会让游标向后翻全部历史，性能极差且容易卡死
        val apiStart = maxOf(startTimeMillis, endTimeMillis - MAX_LOOKBACK_MILLIS)

        val out = mutableListOf<JSONObject>()
        var nextKey: String? = null
        var page = 0

        while (page < maxPages) {
            val data = JSONObject()
                .put("key", key)
                .put("startTime", apiStart)
                .put("endTime", endTimeMillis)
                .put("reverse", true)
                .put("next_key", nextKey ?: JSONObject.NULL)

            val resp = call(
                method = "GET",
                path = "/app/v1/data/get_fitness_data_by_time",
                params = mapOf("data" to data.toString()),
            ) ?: break

            // 外层统一是 {code, message, result}，真正的数据和分页字段都在 result 里。
            // 早期直接读顶层 data_list，结果永远拿不到列表（表现为登录成功但全 0 条）。
            val body = resp.optJSONObject("result") ?: resp
            val items = body.optJSONArray("data_list")
            if (items == null) {
                // 服务端偶尔用 dataPoint 另一套命名，兼容一下
                body.optJSONArray("dataPoints")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(out::add)
                }
                break
            }
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let(out::add)
            }

            val more = body.optBoolean("has_more", false) || body.optBoolean("hasMore", false)
            nextKey = body.optString("next_key").takeIf { it.isNotBlank() }
            if (!more || nextKey == null) break
            page++
        }

        if (page >= maxPages) Log.w(TAG, "getFitnessDataByTime($key) hit page cap $maxPages")
        Log.i(TAG, "getFitnessDataByTime($key) -> ${out.size} points, pages=${page + 1}")
        return out
    }

    /**
     * 通用分页查询，供 fitness 之外的各数据族使用。
     *
     * 各族只有三处差异，都做成参数：
     * - 端点路径（`/app/v1/data/get_sport_records_by_time` 等）
     * - 列表字段名（`data_list` / `sport_records` / `diet_records`）
     * - 额外的固定参数（tag、key、dining…）
     *
     * 时间戳单位也在参数里区分：fitness 族用毫秒，diet 族用秒。
     */
    suspend fun getPaged(
        path: String,
        params: Map<String, Any>,
        listField: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        maxPages: Int = 5,
        timeInSeconds: Boolean = false,
        pageSize: Int? = null,
    ): List<JSONObject> {
        // 与 fitness 相同的钳制理由：startTime 太早会让游标向后翻全部历史
        val apiStart = maxOf(startTimeMillis, endTimeMillis - MAX_LOOKBACK_MILLIS)
        val (s, e) = if (timeInSeconds) apiStart / 1000 to endTimeMillis / 1000
        else apiStart to endTimeMillis
        val startField = if (timeInSeconds) "start_time" else "startTime"
        val endField = if (timeInSeconds) "end_time" else "endTime"

        val out = mutableListOf<JSONObject>()
        var nextKey: String? = null
        var page = 0

        while (page < maxPages) {
            val data = JSONObject()
            params.forEach { (k, v) -> data.put(k, v) }
            data.put(startField, s)
            data.put(endField, e)
            data.put("reverse", true)
            data.put("next_key", nextKey ?: JSONObject.NULL)
            if (pageSize != null) data.put("limit", pageSize)

            val resp = call(
                method = "GET",
                path = path,
                params = mapOf("data" to data.toString()),
            ) ?: break

            // 同 getFitnessDataByTime：列表与分页字段都在 result 里
            val body = resp.optJSONObject("result") ?: resp
            val items = body.optJSONArray(listField)
            if (items == null) break
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let(out::add)
            }

            val more = body.optBoolean("has_more", false) || body.optBoolean("hasMore", false)
            nextKey = body.optString("next_key").takeIf { it.isNotBlank() }
            if (!more || nextKey == null) break
            page++
        }

        Log.i(TAG, "getPaged($path/$listField) -> ${out.size} items, pages=${page + 1}")
        return out
    }

    /**
     * 组装请求 URL。
     *
     * 加密参数一律走 query string（GET 与 POST 都是），这是参考实现的做法：
     * 签名是对「参数名+密文」按序拼接后算的，放 query 最不容易和签名错位。
     */
    private fun buildUrl(
        host: String,
        path: String,
        enc: Map<String, String>,
    ): HttpUrl? {
        val base = "https://$host$path".toHttpUrlOrNull() ?: return null
        val b = base.newBuilder()
        enc.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** 注意：不带 userId cookie，带了会 401 */
    private fun cookieOf(cred: MiHealthCredentials): String = buildString {
        append("cUserId=").append(cred.cUserId)
        append("; serviceToken=").append(cred.serviceToken)
        append("; locale=zh_cn")
    }

    companion object {
        private const val TAG = "LifeBoardMiHealth"

        /** 健康数据域；登录走 account.xiaomi.com，由 MiHealthAuth 处理 */
        const val HLTH_HOST = "hlth.io.mi.com"

        /** 单页最多回看的时长：再早就让游标向后翻全部历史，很慢 */
        private const val MAX_LOOKBACK_MILLIS = 24 * 60 * 60 * 1000L

        /**
         * 伪装成小米运动 App。
         * 服务端按 UA/Cookie 判断客户端类型，缺失或过于简陋会被风控拦。
         */
        private const val USER_AGENT =
            "MiFit/3.59.0 (Android 14; en_US) Resolution/1080x2400 Version/3.59.0 " +
                "Stage/Production DeviceId/mihealth_lifeboard UserId/0"

        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        /** 私有：返回 OkHttp 类型，不能出现在跨模块可见的签名上 */
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}

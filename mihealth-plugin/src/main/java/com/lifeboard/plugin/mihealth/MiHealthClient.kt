package com.lifeboard.plugin.mihealth

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
     * - 所以窗口宽度有上限（[MAX_WINDOW_MILLIS]），**并在客户端真正过滤**窗口外的点：
     *   服务端并不保证只返回窗口内的数据
     *
     * ## 历史数据怎么取
     *
     * 服务端只按「窗口宽度」限制，不按「离今天多远」限制：传 2026-10-01 那天的窗口，
     * 一样能拿到那天的数据。所以拉历史不是把窗口开得很大，而是**按天分段**各调一次——
     * 每段仍然只有一个自然日的宽度，游标不会退化成遍历全部历史。
     *
     * 早先这里把 startTime 钳到「距 endTime 不超过 24 小时」，本意是防上面的病态分页，
     * 副作用是把「拉更早的日期」也一并禁掉了（传什么日期都只返回最近一天）。
     * 现在改成按窗口宽度钳制：超宽的窗口裁到上限，但不会把起点抬到最近 24 小时。
     *
     * ## 过滤口径
     * - 普通采样点（步数等）：按采样时间 `time` 落在窗口内
     * - 区间记录（睡眠）：按起止时间与窗口**有重叠**即保留，
     *   跨夜睡眠必须留给上层按自然日裁剪，这里提前丢掉会把整段睡眠弄没
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
        maxPages: Int = DEFAULT_MAX_PAGES,
    ): List<JSONObject> {
        // 按**窗口宽度**钳制而不是按「离现在多远」：超宽窗口裁到上限，
        // 但传一个较早的日期不会被抬到最近 24 小时
        val apiStart = maxOf(startTimeMillis, endTimeMillis - MAX_WINDOW_MILLIS)

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

            // 整页都已早于窗口 → 后面只会更早，立即停。
            //
            // 这一步不能省：稀疏数据类型（`valid_stand`、`weight`、`blood_pressure`）
            // 一页可以跨好几周，一个 2 天窗口的数据可能在十几页之后，
            // 而窗口之前的历史还有几十页。不按时间提前停，翻满 60 页
            // 也只能拿到 3 条有效点（实测日志：`3/1200 points, pages=61`），
            // 十几个指标加起来就是几百次请求，同步会卡住好几分钟。
            //
            // 用 reverse=true 拿到的页内是按时间倒序，所以「最后一条」就是本页最老的；
            // 它比窗口起点还早，说明这一页已经翻过了窗口，可以收工。
            if (pageHasOnlyOlderItems(items, startTimeMillis)) {
                Log.i(TAG, "getFitnessDataByTime($key) 第 ${page + 1} 页已早于窗口，停止翻页")
                break
            }

            val more = body.optBoolean("has_more", false) || body.optBoolean("hasMore", false)
            val cursor = body.optString("next_key").takeIf { it.isNotBlank() }
            // 游标不前移时必须停：服务端在窗口边缘偶尔重复返回同一个 next_key，
            // 不回退判断会一直翻到页数上限，白跑十次网络请求
            if (!more || cursor == null || cursor == nextKey) break
            nextKey = cursor
            page++
        }

        if (page >= maxPages) Log.w(TAG, "getFitnessDataByTime($key) hit page cap $maxPages")

        val filtered = out.filter { inWindow(it, startTimeMillis, endTimeMillis) }
        Log.i(
            TAG,
            "getFitnessDataByTime($key) -> ${filtered.size}/${out.size} points in window, " +
                "pages=${page + 1}",
        )
        return filtered
    }

    /**
     * 这一页是否所有点都早于窗口起点。
     *
     * 取**最晚**的那个时间比较：只要页内还有一条落在窗口内或之后，就还得继续翻。
     * 页内没有任何可解析时间的点（结构不认识）时返回 false，
     * 宁可多翻几页也不要因为解析不了就提前截断数据。
     */
    private fun pageHasOnlyOlderItems(items: JSONArray, windowStart: Long): Boolean {
        if (items.length() == 0) return false
        var latest = Long.MIN_VALUE
        for (i in 0 until items.length()) {
            val point = items.optJSONObject(i) ?: continue
            val at = MiHealthParser.timestamp(point, "time", "timestamp", "bedtime")
                ?: return false
            if (at > latest) latest = at
        }
        return latest != Long.MIN_VALUE && latest < windowStart
    }

    /**
     * 采样点是否落在窗口内。
     *
     * 时间字段的候选顺序参考宿主的 `MiHealthOverviewReader`：
     * 睡眠用 `bedtime/wake_up_time` 一族，其余用 `time`。
     */
    private fun inWindow(point: JSONObject, start: Long, end: Long): Boolean {
        val rangeStart = MiHealthParser.timestamp(
            point,
            "bedtime",
            "device_bedtime",
            "start_time",
            "startTime",
        )
        if (rangeStart != null) {
            val rangeEnd = MiHealthParser.timestamp(
                point,
                "device_wake_up_time",
                "wake_up_time",
                "end_time",
                "endTime",
            ) ?: rangeStart
            // 区间记录按重叠判断：跨夜睡眠只要碰到窗口就留下
            return rangeEnd > start && rangeStart < end
        }

        val at = MiHealthParser.timestamp(point, "time", "timestamp") ?: return false
        return at in start..end
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
        // 与 fitness 相同的理由：按窗口宽度钳制，不把起点抬到最近一段时间
        val apiStart = maxOf(startTimeMillis, endTimeMillis - MAX_WINDOW_MILLIS)
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

        /**
         * 单次查询的窗口宽度上限。
         *
         * 不是「最多能回看多久」，而是「一次问多长时间的范围」：
         * 窗口越宽，服务端游标越容易退化成遍历全部历史（慢且可能卡死）。
         *
         * 取 2 天而不是 1 天，是因为睡眠是**区间**：查某一天时要把前一晚也算进来，
         * 否则「昨晚 23:00 睡到今早 07:00」这段会被窗口切掉一半。
         * 拉更早的日期靠按天分段多次调用，而不是把这里放大。
         */
        private const val MAX_WINDOW_MILLIS = 2 * 24 * 60 * 60 * 1000L

        /**
         * 单次查询默认翻多少页。
         *
         * 早先写的是 10 页，这就是「原始数据只有半天」的直接原因：步数是**分钟级**
         * 采样，一天有 1400 多个点，而服务端一页约 144 条——10 页只够覆盖其中一小部分，
         * 日志里表现为 `1444 个点只有 77 个落在窗口里`。
         *
         * 这里放到 40 页作为**安全上限**，真正让稀疏数据不白翻的是
         * [pageHasOnlyOlderItems]：整页早于窗口就停，不再靠页数兜底。
         * 分钟级的 `steps` 2 天约 2900 条 ≈ 20 页，仍在范围内。
         */
        private const val DEFAULT_MAX_PAGES = 40

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

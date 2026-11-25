package com.lifeboard.shared.plugin.js

/**
 * 宿主实际注入给 JS 的 `ctx.*` 函数全集。
 *
 * ## 为什么要把清单单独提出来
 *
 * 三处需要知道「宿主到底提供了哪些函数」，此前各写一份：
 * - 各桥接类的 [com.lifeboard.jsengine.HostFunctionDispatcher.functionNames]（注册用，权威）
 * - [JsPluginApi.CAPABILITIES]（文档用，按版本分组）
 * - `js-plugins/types/ctx.d.ts`（TS 类型声明，给插件作者用）
 *
 * 前两份漏写会导致函数注册不上（运行期直接 `is not a function`）；
 * 第三份漏写则相反——宿主管用、TS 却报「属性不存在」，作者只能被迫加
 * `any` 绕过，类型定义就废了。这类不一致是长期维护里最容易积累的债。
 *
 * 于是这里做单一事实源：注册时从这里取（保证不漏），
 * 并用 `ctx.d.ts 与宿主函数集一致` 测试反向锁住 TS 声明。
 *
 * ## 新增能力时要做什么
 *
 * 只在这个文件里加一行 `ctx.http.xxx`，然后：
 * 1. 在对应桥接的 `invoke` 里实现分发
 * 2. 在 `js-plugins/types/ctx.d.ts` 里补声明
 * 3. 在 [JsPluginApi.CAPABILITIES] 记一行「哪一版加的」
 *
 * 第 2 步会被测试挡住，忘了就红。
 */
object JsHostFunctions {

    /** 凭据与文件、历史、日志、id，见 [JsPluginContextBridge] */
    val CONTEXT: List<String> = listOf(
        "ctx.credentials.get",
        "ctx.credentials.set",
        "ctx.credentials.put",
        "ctx.credentials.remove",
        "ctx.credentials.clear",
        "ctx.files.read",
        "ctx.files.write",
        "ctx.files.readBase64",
        "ctx.files.writeBase64",
        "ctx.files.list",
        "ctx.recentMetrics",
        "ctx.reportRawRecords",
        "ctx.reportMetricQueried",
        // 事件型指标的即时累加上报：会话完成一轮、任务失败一次都走这条
        "ctx.report",
        "ctx.log.d",
        "ctx.log.i",
        "ctx.log.w",
        "ctx.log.e",
        "ctx.pluginId",
    )

    /** HTTP 与会话，见 [JsHttpBridge] */
    val HTTP: List<String> = listOf(
        "ctx.http.get",
        "ctx.http.post",
        "ctx.http.request",
        "ctx.http.form",
        "ctx.http.postForm",
        "ctx.http.lastError",
        "ctx.http.session",
        "ctx.http.setHeader",
        "ctx.http.setUserAgent",
        "ctx.http.setFollowRedirects",
        "ctx.http.cookieGet",
        "ctx.http.cookieSet",
        "ctx.http.cookieRemove",
        "ctx.http.cookieClear",
        "ctx.http.cookieAll",
        // 自签证书：取回 CA（放宽校验）→ 比对 manifest 指纹 → 装信任
        "ctx.http.caCertificate",
        "ctx.http.trustCa",
    )

    /** 摘要与编解码，见 [JsCryptoBridge] */
    val CRYPTO: List<String> = listOf(
        "ctx.crypto.md5Hex",
        "ctx.crypto.sha1Base64",
        "ctx.crypto.sha256Base64",
        "ctx.crypto.sha256Hex",
        "ctx.crypto.sha256Bytes",
        "ctx.crypto.randomBytes",
        "ctx.codec.base64Encode",
        "ctx.codec.base64Decode",
        "ctx.codec.base64UrlEncode",
        "ctx.codec.base64UrlDecode",
        "ctx.codec.utf8Encode",
        "ctx.codec.utf8Decode",
    )

    /** WebSocket 长连接，见 [JsWebSocketBridge] */
    val WS: List<String> = listOf(
        "ctx.ws.connect",
        "ctx.ws.recv",
        "ctx.ws.send",
        "ctx.ws.sendBinary",
        "ctx.ws.close",
        "ctx.ws.state",
    )

    /** 增量输出，见 [JsStreamBridge] */
    val STREAM: List<String> = listOf(
        "ctx.stream.emit",
    )

    /** 会话主动推送，见 [JsSessionBridge] */
    val SESSION: List<String> = listOf(
        "ctx.session.emit",
    )

    /** 全部函数，注册时用。 */
    val ALL: List<String> = CONTEXT + HTTP + CRYPTO + WS + STREAM + SESSION

    /**
     * 函数名 → 所属能力组。
     *
     * 用途是让校验失败时的报错直接指出「大概是哪个桥接漏了」，
     * 而不是只说「少了 3 个函数」。
     */
    fun groupOf(name: String): String = when {
        name.startsWith("ctx.http.") -> "HTTP"
        name.startsWith("ctx.ws.") -> "WS"
        name.startsWith("ctx.stream.") -> "STREAM"
        name.startsWith("ctx.session.") -> "SESSION"
        name.startsWith("ctx.crypto.") || name.startsWith("ctx.codec.") -> "CRYPTO"
        else -> "CONTEXT"
    }
}

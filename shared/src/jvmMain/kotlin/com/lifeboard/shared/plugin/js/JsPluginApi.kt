package com.lifeboard.shared.plugin.js

/**
 * JS 插件契约版本。
 *
 * ## 为什么 JS 插件需要独立的一套版本号
 *
 * [com.lifeboard.plugin.api.PluginApi.VERSION] 管的是「插件实现了契约里的哪些
 * 方法」，那是给编译型插件用的。JS 插件不是编译产物，它调用的是宿主注入的
 * `ctx.*` 函数——**函数集就是契约**。宿主少一个函数、或某个函数的参数形态变了，
 * 插件就在运行期以 `undefined is not a function` 的形式炸在业务代码中间，
 * 报错点离真正原因很远。
 *
 * 所以 JS 侧单独维护一个版本号，语义是「宿主提供了哪一代 `ctx.*` 能力」，
 * 加载时显式比对，不匹配就带着原因拒绝加载，绝不让它在采集时才炸。
 *
 * ## 兼容策略：向上不兼容，向下兼容
 *
 * - 插件声明的版本 **高于** 宿主支持的最高版本 → 拒绝加载。
 *   它可能用了宿主还没有的函数，跑起来必然在半路失败。
 * - 插件声明的版本 **低于** 宿主 → 正常加载。
 *   本类的历史只在「加函数」，从未删改过已有函数，旧插件不受影响。
 * - 插件 **没声明** 版本（`apiVersion: 0` 或缺字段）→ 按 [LEGACY] 处理。
 *   早期插件都没写这个字段，它们能跑说明只用到了 [LEGACY] 那代能力。
 *
 * ## 递增规则
 *
 * **只有破坏性变更才递增** [CURRENT]：删函数、改已有函数的参数顺序/含义、
 * 改返回结构。新增函数一律不递增——旧插件不知道新函数的存在，也就不会用到它，
 * 升级后行为不变。这一条必须守住，否则每次加能力都要所有插件跟着改版本号。
 *
 * 新增函数时，在 [CAPABILITIES] 里记一行「哪一版加的哪些函数」，
 * 插件作者据此判断自己能依赖什么。
 */
object JsPluginApi {

    /**
     * 当前宿主支持的契约版本。
     *
     * 1：初版。`ctx.credentials` / `ctx.files` / `ctx.log` / `ctx.http` /
     *    `ctx.crypto` / `ctx.recentMetrics` / `ctx.reportRawRecords` /
     *    `ctx.reportMetricQueried` / `ctx.pluginId`
     *
     * 2：补上「写插件时一定会撞到」的四处空白，全部是新增，不破坏 v1：
     *    `ctx.http.form`、`ctx.http.postForm`（表单编码）、
     *    `options.timeoutMs` / `options.retry`、
     *    `ctx.files.readBase64` / `writeBase64`（二进制文件）、
     *    `ctx.http.lastError`（区分超时与其它网络错误）
     *
     * 注意：[com.lifeboard.plugin.api.PluginApi.VERSION] 与这里的 [CURRENT] 是两套，
     * 不要拿来互相比较——前者描述编译期契约，后者描述宿主注入的函数集。
     */
    const val CURRENT: Int = 2

    /**
     * 未声明 `apiVersion` 时按这一版处理。
     *
     * 取 1 而不是 0：早期 JS 插件确实存在且能跑，它们只用到了 v1 的能力。
     * 当成 0 会让所有老包在升级宿主后被判成「版本过低」而拒绝加载。
     */
    const val LEGACY: Int = 1

    /** 各版本新增的能力，供文档与排查时对照 */
    val CAPABILITIES: Map<Int, List<String>> = linkedMapOf(
        1 to listOf(
            "ctx.credentials.get/put/remove/clear",
            "ctx.files.read/write/list",
            "ctx.http.get/post/request",
            "ctx.http.session/setHeader/setUserAgent/setFollowRedirects",
            "ctx.http.cookieGet/cookieSet/cookieRemove/cookieClear/cookieAll",
            "ctx.crypto.md5Hex/sha1Base64/sha256Base64/sha256Hex",
            "ctx.codec.base64Encode/base64Decode",
            "ctx.recentMetrics/reportRawRecords/reportMetricQueried",
            "ctx.log.d/i/w/e",
            "ctx.pluginId",
        ),
        2 to listOf(
            "ctx.http.form/postForm",
            "ctx.http.lastError",
            "options.timeoutMs/retry",
            "ctx.files.readBase64/writeBase64",
        ),
    )

    /** 一次版本判定结果。 */
    sealed interface Verdict {
        /** 可以加载。[declared] 是插件声明的版本，[assumed] 表示是否为推断值 */
        data class Compatible(val declared: Int, val assumed: Boolean) : Verdict

        /**
         * 插件要求的版本高于宿主支持。
         *
         * [reason] 直接展示给用户，必须说清「升级宿主」这个可执行动作。
         */
        data class TooNew(val declared: Int, val reason: String) : Verdict
    }

    /**
     * 判定插件的 `apiVersion` 能否在本宿主上运行。
     *
     * 纯函数：不读文件、不碰运行时，方便单测覆盖各种边界。
     *
     * @param declared manifest 里声明的版本，缺省由调用方传 0
     * @param hostVersion 宿主支持的最高版本，测试时可注入
     */
    fun evaluate(declared: Int, hostVersion: Int = CURRENT): Verdict {
        // 非法值（负数、缺字段）按老插件处理：宁可能跑起来再报错，
        // 也不要因为作者少写一个字段就整包拒绝
        val effective = if (declared <= 0) LEGACY else declared
        val assumed = declared <= 0

        if (effective > hostVersion) {
            val newest = CAPABILITIES.keys.maxOrNull() ?: hostVersion
            return Verdict.TooNew(
                declared = effective,
                reason = "插件要求接口版本 $effective，本机 LifeBoard 只支持到 $hostVersion，" +
                    "请升级 App 后再使用（最新接口版本为 $newest）",
            )
        }
        return Verdict.Compatible(declared = effective, assumed = assumed)
    }
}

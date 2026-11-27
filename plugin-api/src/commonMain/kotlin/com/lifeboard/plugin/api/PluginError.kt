package com.lifeboard.plugin.api

/**
 * 插件错误码（13 种）与统一错误信封。
 *
 * ## 为什么要结构化错误而不是裸字符串
 *
 * `ctx.*` 失败原先只抛文本，宿主无法分类，调用方只能靠消息前缀猜测。
 * 引入枚举后，每个失败都能映射到明确的「默认处置 + 是否可重试」，
 * 采集器、退避调度器、健康记录和界面可以各自做出正确动作。
 *
 * ## 分类原则
 *
 * 认证三类拆分：`AUTH_REQUIRED`（从未登录）、`AUTH_INVALID`（输入有误，不可重试）、
 * `AUTH_EXPIRED`（凭据过期，可安全续期一次）。
 *
 * 上游两类拆分：`UPSTREAM_ERROR`（网络/临时故障，可有限重试）与
 * `UPSTREAM_CHANGED`（接口结构不符，重试无用，应提示更新插件）。
 *
 * ## `UPSTREAM_ERROR` 与 `UPSTREAM_CHANGED` 的判定
 *
 * 两者的分界是「**同样的输入重试，结果会不会变**」：
 *
 * | 现象 | 归类 | 理由 |
 * |---|---|---|
 * | 连接失败、超时、DNS 错误 | `UPSTREAM_ERROR` | 网络环境会变 |
 * | HTTP 5xx、网关 502/503 | `UPSTREAM_ERROR` | 服务端临时故障 |
 * | HTTP 429、明确限流头 | `RATE_LIMITED` | 等待后可恢复 |
 * | HTTP 401/403（凭据层面） | `AUTH_EXPIRED` / `AUTH_INVALID` | 需要用户或续期 |
 * | 业务错误码（如「该日期无记录」以外的拒绝） | 由插件判断 | 见下 |
 * | JSON 结构变了、字段没了、选择器取不到 | `UPSTREAM_CHANGED` | 重试同样失败 |
 * | 响应是 HTML 而不是预期 JSON | `UPSTREAM_CHANGED` | 接口形态已变 |
 *
 * **业务错误码不能一律当作可重试**：多数业务拒绝是确定性结果，重试只会
 * 重复消耗配额。插件若不能确定是临时故障，应返回 `UPSTREAM_ERROR`（保守）
 * 并说明原因，而不是笼统地 `UPSTREAM_CHANGED`。
 *
 * ## 未知码回退
 *
 * 插件返回了不认识的 code 时，一律归入 [INTERNAL_ERROR]，保留脱敏诊断标识。
 * **不能**把未知码变成成功或空数据。
 *
 * ## 取消是控制流
 *
 * `CancellationException` 不属于这里任何一码，应原样传播，
 * 不计入健康故障，也不算连续中断。
 */
enum class PluginErrorCode {
    /** 需要用户首次登录；不清除有效旧数据 */
    AUTH_REQUIRED,

    /** 凭据输入有误（账号/密码/验证码错）；不自动重试 */
    AUTH_INVALID,

    /** 凭据已过期；支持安全续期时至多续期一次，失败后引导登录 */
    AUTH_EXPIRED,

    /** 上游网络/超时/临时故障；仅幂等采集有限退避重试 */
    UPSTREAM_ERROR,

    /** 上游接口结构不符；重试无用，提示更新插件 */
    UPSTREAM_CHANGED,
    /** 上游限流；按 Retry-After 与宿主上下界安排下次采集 */
    RATE_LIMITED,

    /** 缺少所需能力；要求兼容插件或宿主 */
    UNSUPPORTED,

    /** 参数/契约错误；显示参数错误，不重试 */
    INVALID_ARGUMENT,

    /** 存储已满；停止新增写入，提示清理或缩减数据 */
    STORAGE_FULL,

    /** 资源限额（堆/载荷等）；堆 OOM 淘汰实例 */
    RESOURCE_LIMIT,

    /** 执行被中断；同步预算触发则淘汰实例，终止相关会话 */
    INTERRUPTED,

    /** 运行时不可用（关闭/更新/停用/预算不足）；返回明确原因 */
    UNAVAILABLE,

    /** 未知内部错误；记录脱敏诊断并显示失败 */
    INTERNAL_ERROR;

    companion object {
        /** 全部 13 种码，顺序即文档展示顺序 */
        val ALL: List<PluginErrorCode> = entries.toList()

        /**
         * 由字符串解析错误码。未知/空值回退为 [INTERNAL_ERROR]。
         * 调用方可据此保留原始诊断标识，但不暴露给用户。
         */
        fun fromString(raw: String?): PluginErrorCode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: INTERNAL_ERROR
    }
}

/**
 * 跨层传递的结构化错误信封。
 *
 * 插件在 `ctx.makeError()` 中构造，经 native/桥接保留 code 字段，
 * 最终映射到 Kotlin 契约（[FetchResult.Failure] 等）和 UI。
 *
 * ## 字段
 *
 * - [code]：枚举值，未知时回退为 [PluginErrorCode.INTERNAL_ERROR]
 * - [message]：安全可展示的说明，不得含凭据内容
 * - [retryAfterSec]：建议等待秒数，null 表示由宿主决定
 * - [operation]：出错的操作标识，便于定位
 * - [reason]：可选的诊断细节，仅入日志，不直接展示
 * - [limitKind]：限额类错误的细分（如堆/载荷），仅用于诊断
 * - [generation]：代际标识，隔离旧代际迟到提交
 *
 * 取消（`CancellationException`）不构造信封，直接传播。
 */
data class PluginError(
    val code: PluginErrorCode,
    val message: String,
    val retryAfterSec: Long? = null,
    val operation: String = "",
    val reason: String? = null,
    val limitKind: String? = null,
    val generation: Long = 0L,
) {
    /** 是否可自动重试（宿主默认处置） */
    val retryable: Boolean = when (code) {
        PluginErrorCode.AUTH_REQUIRED,
        PluginErrorCode.AUTH_INVALID,
        PluginErrorCode.UPSTREAM_CHANGED,
        PluginErrorCode.UNSUPPORTED,
        PluginErrorCode.INVALID_ARGUMENT,
        PluginErrorCode.STORAGE_FULL,
        PluginErrorCode.RESOURCE_LIMIT,
        PluginErrorCode.INTERRUPTED,
        PluginErrorCode.INTERNAL_ERROR,
        -> false

        PluginErrorCode.AUTH_EXPIRED -> true   // 仅显式安全续期
        PluginErrorCode.UPSTREAM_ERROR -> true // 仅幂等采集
        PluginErrorCode.RATE_LIMITED -> true   // 仅幂等采集
        PluginErrorCode.UNAVAILABLE -> true    // 仅尚未执行且安全的请求
    }

    /** 将本错误转为可展示的诊断文本，用于日志（不含敏感内容） */
    fun diagnosticText(): String =
        "code=$code op=${if (operation.isBlank()) "-" else operation} " +
            "retryable=$retryable retryAfter=${retryAfterSec ?: "-"}" +
            (reason?.let { " reason=$it" } ?: "")
}

package com.lifeboard.plugin.api

/**
 * 一次采集的结果。
 *
 * ## 为什么不能用「异常 = 空列表」
 *
 * 早期契约让 [SourcePlugin.fetch] 直接返回 `List<PluginMetric>`，失败靠抛异常表达，
 * 而宿主的两层兜底（插件侧 catch + 调度侧 catch）都把它收敛成「空列表」。
 * 结果是**三种完全不同的处境在用户眼里长得一模一样**：
 *
 * - 今天确实没运动 → 空列表，正确
 * - 手机在电梯里没信号 → 空列表，其实是「这次没取到」
 * - 密码被改了 → 空列表，其实要用户去重新登录
 *
 * 用户看到「今日无数据」，第一反应是设备没戴或应用没同步，不会想到要去登录。
 * 这个歧义没法在展示层补救——信息在采集层就已经丢了。
 *
 * ## 四个分支对应四种宿主行为
 *
 * | 结果 | 宿主行为 |
 * |---|---|
 * | [Success] | 正常落库，刷新 lastSuccessAt |
 * | [Empty] | 正常落库（无记录），但**必须**说明原因，让用户能区分「没运动」与「没取到」 |
 * | [TemporaryFailure] | 不落库、不算断源，按 [TemporaryFailure.retryAfterSeconds] 退避重试 |
 * | [AuthenticationRequired] | 不重试，提示重新登录，标记为需要用户处理 |
 * | [Failure] | 记为不健康并展示原因，交给用户判断 |
 *
 * 插件**仍然不应该抛异常**：异常会被宿主当成 [Failure] 兜住，
 * 但那条路径拿不到结构化原因。要说明情况就返回这里的分支。
 *
 * ## 结构化错误（可选）
 *
 * 失败分支新增可选的 [PluginError]，用于携带 [PluginErrorCode] 分类与
 * 「默认处置 + 可重试」信息。**旧插件不填就保持原语义**：
 * `authRequired` → `AUTH_REQUIRED`，`temporary` → `UPSTREAM_ERROR`，
 * `failure` → `INTERNAL_ERROR`。
 */
sealed class FetchResult {

    /** 本次拿到的数据；列表为空等价于 [Empty]「确实没有记录」 */
    data class Success(val metrics: List<PluginMetric>) : FetchResult()

    /**
     * 确实没有数据，并且**确定**不是失败。
     *
     * 只有「服务端明确回答没有」才该用它。请求超时、解析失败这类
     * 「不知道有没有」必须走 [TemporaryFailure] 或 [Failure]。
     */
    data class Empty(val reason: String) : FetchResult()

    /**
     * 暂时取不到，过一会可能就好了：断网、超时、限流、服务端 5xx。
     *
     * 宿主不会因此判定插件坏掉，也不会催用户去登录，只会按退避再试。
     *
     * @property retryAfterSeconds 建议的等待秒数，null 表示由宿主决定。
     *   服务端给了 `Retry-After` 就照抄过来——自己猜的间隔通常不是太长就是太短
     */
    data class TemporaryFailure(
        val message: String,
        val retryAfterSeconds: Long? = null,
        /** 结构化错误；缺省按 [PluginErrorCode.UPSTREAM_ERROR] 处理 */
        val error: PluginError? = null,
    ) : FetchResult()

    /**
     * 凭据失效，需要用户重新登录。
     *
     * 单独一档而不是塞进 [Failure]：这是**唯一**一种用户自己动手就能修好的失败。
     * 混进普通失败里，用户只会看到一句「采集失败」，不知道该点什么。
     */
    data class AuthenticationRequired(
        val message: String,
        /** 结构化错误；缺省按 [PluginErrorCode.AUTH_REQUIRED] 处理 */
        val error: PluginError? = null,
    ) : FetchResult()

    /**
     * 明确失败：接口改了、脚本报错、数据格式不符。
     *
     * 重试没有意义——同样的输入会得到同样的错误，只会白耗流量与电量。
     */
    data class Failure(
        val message: String,
        /** 结构化错误；缺省按 [PluginErrorCode.INTERNAL_ERROR] 处理 */
        val error: PluginError? = null,
    ) : FetchResult()
}

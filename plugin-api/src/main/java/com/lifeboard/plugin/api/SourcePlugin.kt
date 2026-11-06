package com.lifeboard.plugin.api

/**
 * 第三方数据源插件的唯一入口契约。
 *
 * 为什么是「代码插件」而不是声明式配置：小米健康的
 * `onCredentialsExpired → refreshWithPassToken` 这类续期逻辑，
 * 接口地址、参数形态、响应结构都是站点私有的，无法配置化。
 * 契约必须允许插件自己写登录、续期、分页与多量纲解析，否则首个真实数据源就抽不出去。
 *
 * **同进程运行，没有沙箱**：插件与本应用同权限，能读本应用数据。
 * 签名与信任体系是准入与防替换机制，不是安全保证，UI 与文档不得使用「沙箱」「隔离」等措辞。
 */
interface SourcePlugin {

    /** 全局唯一标识，反向域名式，如 `dev.foo.mihealth`；不得含 `:`（它是插件 key 的分隔符） */
    val id: String

    /** 展示名称，如「小米健康」 */
    val displayName: String

    /**
     * 契约版本。
     *
     * 单版本策略：与 [PluginApi.VERSION] 不一致即拒绝加载并说明原因，
     * 不做多代并存（见 I 阶段 §八）。
     */
    val apiVersion: Int

    /** 本插件会用到的凭据槽位，供 UI 展示与清除提示 */
    val credentialSpec: CredentialSpec

    /**
     * 本插件能产出的全部指标元数据。
     *
     * 必须是一个**静态列表**：宿主在加载插件时就用它建立「metricKey → 量纲」的映射，
     * 并校验同一 metricKey 不会对应两个量纲（见 [PluginMetrics.validate]）。
     * 若运行期才决定指标，加载期就无法校验，同一条数据可能被两种口径解读。
     *
     * 空列表合法（插件可能只提供登录管理而暂不产数），此时不会为它注册任何数据源。
     */
    val metrics: List<MetricSpec>

    /**
     * 登录。流程完全由插件自己实现，凭据写进 [PluginContext.credentials]。
     *
     * 失败要返回 [LoginResult.Failure] 而不是抛异常，[LoginResult.Failure.message] 会展示给用户。
     */
    suspend fun login(context: PluginContext): LoginResult

    /** 登出：清理本插件名下凭据。宿主也会在用户清除凭据时调用 */
    suspend fun logout(context: PluginContext)

    /** 当前登录态，供插件 Tab 与面板展示 */
    suspend fun authState(context: PluginContext): AuthState

    /**
     * 拉取指定日期的数据。
     *
     * 返回的每条 [PluginMetric] 的 metricKey 必须属于 [metrics]，
     * 量纲由宿主按 key 反查补齐，插件不自行指定。
     * 当日无数据返回空列表，不要用 0 代替缺失。
     *
     * @param date 日期，格式 `yyyy-MM-dd`
     */
    suspend fun fetch(context: PluginContext, date: String): List<PluginMetric>

    /** 连通性与健康度；失败必须返回 [PluginHealth]，不要抛异常 */
    suspend fun health(context: PluginContext): PluginHealth
}

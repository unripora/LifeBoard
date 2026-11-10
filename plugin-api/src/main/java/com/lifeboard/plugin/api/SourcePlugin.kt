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
     * 向后兼容：**不高于** [PluginApi.VERSION] 即可加载。
     * 契约只允许新增带默认实现的方法与新增数据类，这类变更不影响已编译的插件；
     * 只有破坏性变更（改已有签名、删字段、改语义）才递增 [PluginApi.VERSION]，
     * 而那类变更必须让插件重新编译，版本号本身拦不住运行时错误。
     *
     * 高于宿主版本则拒绝加载并说明原因——那种插件可能用了宿主还没有的契约能力。
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
     * 支持多轮：返回 [LoginResult.NeedInput] 表示还差用户填东西，
     * 调用方应展示表单并用用户填的值作为 [inputs] 再次调用本方法，
     * 直到拿到 [LoginResult.Success] 或 [LoginResult.Failure]。
     *
     * 实现方注意：**不要假设 [inputs] 里有什么**。第一轮通常是空的，
     * 第二轮开始会有调用方按需填的值；缺字段应返回新的 [LoginResult.NeedInput]，
     * 而不是抛异常。
     *
     * 失败要返回 [LoginResult.Failure] 而不是抛异常，`message` 会展示给用户。
     *
     * @param inputs 上一轮 [LoginResult.NeedInput] 要求用户填的值，键是 [InputField.name]
     */
    suspend fun login(context: PluginContext, inputs: Map<String, String> = emptyMap()): LoginResult

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

    /**
     * 详情页里由插件自己决定的那部分内容。
     *
     * 宿主会把返回的区块渲染在「通用信息」（包名、版本、签名、健康度）之下、
     * 「通用操作」（停止使用、清除凭据、卸载）之上。这两个通用区由宿主固定，
     * 插件插不进去也改不了：装的是哪个包、签名是什么，必须由宿主说了算。
     *
     * 默认返回空列表，此时详情页只显示通用信息与通用操作。
     * 插件不需要展示任何自有内容时不必重写本方法。
     *
     * **不要在这里发网络请求**：用户点进详情页只是想看细节，
     * 不期望顺便触发一次采集。需要实时数据请用 [ActionResult.TriggerSync] 显式触发。
     */
    suspend fun detailUi(context: PluginContext): List<UiBlock> = emptyList()

    /**
     * 用户在详情页点了 [ButtonSpec.actionId] 对应的按钮。
     *
     * 宿主会在本方法返回前禁用该按钮，插件不必自己防连点；
     * 但**同一插件可能被并发调用**（两个按钮几乎同时点），需要互斥时请自行加锁。
     *
     * 失败不要抛异常：异常会被宿主兜住并转成一句通用错误，
     * 用户看不到原因。要说明情况请返回 [ActionResult.ShowMessage]。
     *
     * @param actionId 插件在 [ButtonSpec] 里给出的标识
     */
    suspend fun onAction(context: PluginContext, actionId: String): ActionResult =
        ActionResult.NoOp
}

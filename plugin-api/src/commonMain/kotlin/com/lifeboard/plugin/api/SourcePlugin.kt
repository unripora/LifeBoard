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
     * 返回 [FetchResult] 而不是裸列表，是为了让插件能说清「为什么没数据」：
     * 真的没运动、网络暂时不通、还是需要重新登录，宿主对这三者的处理完全不同。
     * 详见 [FetchResult] 的类型注释。
     *
     * [FetchResult.Success] 里每条 [PluginMetric] 的 metricKey 必须属于 [metrics]，
     * 量纲由宿主按 key 反查补齐，插件不自行指定。
     *
     * **当日无数据不要用 0 代替缺失**，返回 [FetchResult.Empty]（或空列表的
     * [FetchResult.Success]）。用 0 会让「没记录」变成一条真实的零值数据，
     * 之后无法与「真的走了 0 步」区分。
     *
     * @param date 日期，格式 `yyyy-MM-dd`
     */
    suspend fun fetch(context: PluginContext, date: String): FetchResult

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
     * 用户在详情页触发了某个交互。
     *
     * 三类来源共用这一个入口：
     * - [ButtonRowBlock] 的按钮：只带 `actionId`
     * - [InputBoxBlock] 提交：`actionId` 是输入框的标识，[value] 是用户输入的文本
     * - [ApprovalBlock] 的选项：只带该项的 `actionId`
     *
     * 宿主会在本方法返回前禁用该按钮，插件不必自己防连点；
     * 但**同一插件可能被并发调用**（两个按钮几乎同时点），需要互斥时请自行加锁。
     *
     * 失败不要抛异常：异常会被宿主兜住并转成一句通用错误，
     * 用户看不到原因。要说明情况请返回 [ActionResult.ShowMessage]。
     *
     * @param actionId 插件在 [ButtonSpec] / [InputBoxBlock] 里给出的标识
     * @param value 输入类动作携带的用户文本；非输入类动作为 `null`
     */
    suspend fun onAction(
        context: PluginContext,
        actionId: String,
        value: String? = null,
    ): ActionResult = ActionResult.NoOp

    /**
     * 启动一个交互式会话（例如远程 Agent 对话）。
     *
     * 这三条会话方法都是**宿主驱动的生命周期钩子**，不是「一次请求对一次响应」：
     * 宿主在用户进入会话页时调 [onSessionStart]，之后把用户的每次操作作为
     * [onSessionEvent] 投递进来，离开页面或超时后调 [onSessionStop]。
     *
     * 每次调用只应处理一件事就返回。长时间在线的收发由插件自己的后台循环完成
     * （宿主会持续推进它，见 `ctx.ws` 的拉取模型）：一次调用长期不返回，
     * 会把同一插件的其它调用（采集、健康检查）堵在后面。
     *
     * 默认不实现，返回 `false`；宿主据此在界面上标注「本插件不支持会话」，
     * 并且不会再投递后续事件。
     *
     * @param config 会话启动参数，键值对由插件自己的协议解释，宿主只原样转交
     * @return 会话是否成功启动
     */
    suspend fun onSessionStart(context: PluginContext, config: SessionConfig): Boolean = false

    /**
     * 投递一次会话事件（用户输入、审批结果、取消等）。
     *
     * 默认视为「不需要特殊处理，会话继续」。插件执行失败也不要抛异常，
     * 抛出的异常会被宿主兜底吞掉并转成一句通用错误；需要终止会话时
     * 返回 [SessionAction.Stop] 并说明原因。
     */
    suspend fun onSessionEvent(
        context: PluginContext,
        event: SessionEvent,
    ): SessionAction = SessionAction.Continue

    /** 结束会话。宿主保证在页面关闭、插件卸载前调用；默认空实现 */
    suspend fun onSessionStop(context: PluginContext) {}

    /**
     * 注册会话主动事件监听器。
     *
     * 用途是让插件在**用户没有操作时**也能把消息推给界面（服务端主动来消息、
     * 后台任务完成）。只在会话期间由宿主注册，会话结束后传 `null` 解除。
     *
     * 回调可能发生在插件自己的线程上，实现方必须自行切回主线程再更新界面状态。
     * 默认空实现：纯拉取型插件不需要主动推送。
     */
    fun setSessionUiListener(listener: ((SessionUiEvent) -> Unit)?) {}
}

/**
 * 会话启动参数。
 *
 * 刻意用「键值对 + 插件自行解释」而不是宿主定义的字段：会话里要传什么
 * （网关地址、工作区、会话 id）完全取决于插件对接的协议，宿主定义字段
 * 就等于把某一家协议固化进契约。宿主只负责原样转交。
 */
data class SessionConfig(val options: Map<String, String> = emptyMap())

/**
 * 宿主投递给插件的会话事件。
 *
 * @property type 事件类型，语义由插件自己约定（如 `input` / `approval` / `cancel`）
 * @property payload 事件负载，JSON 文本；宿主不解析
 */
data class SessionEvent(val type: String, val payload: String = "")

/** 插件对一次会话事件的处置 */
sealed interface SessionAction {

    /** 会话继续，宿主无需额外处理 */
    data object Continue : SessionAction

    /**
     * 结束会话。
     *
     * @property reason 展示给用户的原因
     */
    data class Stop(val reason: String) : SessionAction
}

/**
 * 插件主动推给会话区块的事件。
 *
 * 与 [SessionAction] 的分工：那个是「对宿主事件的应答」，这个是「没人问也要说」。
 * 结构刻意保持最小——只够驱动消息列表、流式增量与输入可用性，
 * 不允许插件自定义任意 UI（那会绕开宿主统一的表现层）。
 */
sealed interface SessionUiEvent {

    /** 追加一条完整消息 */
    data class Message(val role: ChatRole, val text: String) : SessionUiEvent

    /**
     * 追加到当前回复末尾的流式片段。
     *
     * 没有进行中的回复时，宿主会新建一条 assistant 消息再接上。
     */
    data class Delta(val text: String) : SessionUiEvent

    /** 更新状态文案与输入可用性（如「已连接」「正在生成」） */
    data class Status(val text: String, val inputEnabled: Boolean) : SessionUiEvent

    /** 清空消息列表（重连后由服务端快照重建时使用） */
    data object Clear : SessionUiEvent

    /** 会话被插件侧关闭，宿主应停止会话并显示原因 */
    data class Closed(val reason: String) : SessionUiEvent
}

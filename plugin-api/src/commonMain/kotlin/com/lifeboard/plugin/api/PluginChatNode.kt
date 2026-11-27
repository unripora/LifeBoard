package com.lifeboard.plugin.api

/**
 * 对话与「从清单里选」两类节点。
 *
 * ## 为什么单独一个文件
 *
 * [PluginNode] 是「所有节点类型的总表」，随着节点变多会超出版本规定的单文件上限。
 * 这里放的是**成对出现**的两组节点：`chat`（把消息流画成对话）与
 * `picker`（从动态清单里选）。它们各自的载体值类型（[ChatMessage] /
 * [PickerCandidate]）也一并放在这里，避免契约读者在两处之间跳。
 *
 * 分类依据与 [PluginNode] 一致：按**渲染时的布局约束**，而不是按功能。
 */

/**
 * 对话流。**宿主负责全部视觉**：气泡、对齐、角色区分、连续消息合并、流式光标、自动滚底。
 *
 * ## 为什么需要一个专用节点
 *
 * 早先的消息只能走 `list` + 绑定：每条消息被展开成一个独立的 [TextNode]，
 * 再被 [ListNode] 塞进 Row。四层各自都「没错」，叠起来就是「一堆一模一样的
 * 文字框」，用户分不清哪些是自己说的、哪些是对方说的，也看不出哪里是一轮问答
 * 的结束。角色信息在「消息 → 文本 → 卡片 → 列表项」这条链路上被磨平了。
 *
 * ## 与「通用 binding 不要专用节点」的关系
 *
 * 通用绑定（任意节点绑任意数据源）原样保留：想把消息自己排布成别的形态，
 * 仍然可以用 `list` + `text`。本节点是**可选的高层封装**，只负责一件事——
 * 把「对话」这种形态一次性说清楚，插件不必为每家公司重新发明气泡。
 *
 * ## 宿主渲染规则（插件无法覆盖）
 *
 * | 规则 | 内容 |
 * |---|---|
 * | 角色呈现 | USER 右对齐 + 主色气泡；ASSISTANT 左对齐 + 表面色气泡；SYSTEM 居中弱化 |
 * | 合并 | 同一角色、同一轮次的连续消息合并为一组，组内不重复显示名字 |
 * | 时间 | 组首显示一次；与上一条间隔较短时不重复显示 |
 * | 轮次 | [ChatMessage.turnId] 变化且角色回到 USER 时，组间留出更明显的间距 |
 * | 流式 | `pending = true` 的消息末尾显示光标 |
 * | 滚动 | 新消息进来自动贴底 |
 *
 * @property messages 消息列表；绑定到会话源时由宿主持续推送
 * @property selfName 自己（用户）一侧的显示名；空则不显示
 * @property peerName 对方（Agent / 服务端）一侧的显示名；空则不显示
 * @property maxHeightDp 超过后内部滚动；0 表示不限制（跟随内容，整页滚动）
 * @property showTime 是否显示时间
 * @property emptyHint 无消息时的提示文案
 */
data class ChatNode(
    val messages: Bound<List<ChatMessage>> = Bound.Static(emptyList()),
    val selfName: String = "",
    val peerName: String = "",
    val maxHeightDp: Int = 0,
    val showTime: Boolean = true,
    val emptyHint: String = "暂无对话",
    override val id: String? = null,
    override val locked: Boolean = false,
) : NodeContent

/**
 * 弹层选择：从一份**清单**里选一项或多项。
 *
 * ## 与 [SelectNode] 的分工
 *
 * `select` 是「在一组固定选项里切」，选项写在契约里；`picker` 是「从一份
 * 随时会变的清单里挑」，清单通常绑定到宿主数据源（如会话清单）。
 *
 * ## 典型用法
 *
 * 插件在 `detailUi` 里给出 `picker`，其 `candidates` 绑定到清单源
 * （如 `sessionList:items`）；用户选中一项，宿主把该候选的 `id` 通过
 * `onAction(actionId, id)` 回传（多选回传 JSON 数组文本）。插件据此
 * 把内容区改绑到对应数据源的 key 上，再返回一次 `detailUi`。
 *
 * ## 不允许插件自定义行外观
 *
 * [PickerCandidate] 只有语义字段，不许嵌节点。这个控件的价值就在于
 * 「所有插件的选择器长得一样、行为一样」。要自定义排版请用
 * [ListNode] + `itemActionId`。
 *
 * @property actionId 回传标识；为空时渲染成禁用态
 * @property title 弹层标题；INLINE 模式下作为段落小标题
 * @property candidates 候选清单；可绑定到数据源
 * @property mode MODAL 弹出对话框；INLINE 就地展开（候选少、页面有空位时更顺手）
 * @property multiple 是否多选；多选回传 JSON 数组文本
 * @property selectedIds 已选中的候选 id，由插件回传；树重建后选中态不丢
 * @property submitLabel 多选时的确认按钮文案；空则用「确定」
 * @property emptyHint 清单为空时的提示文案
 */
data class PickerNode(
    override val actionId: String?,
    val title: String = "",
    val candidates: Bound<List<PickerCandidate>> = Bound.Static(emptyList()),
    val mode: PickerMode = PickerMode.MODAL,
    val multiple: Boolean = false,
    val selectedIds: List<String> = emptyList(),
    val submitLabel: String = "",
    val emptyHint: String = "暂无可选项",
    override val id: String? = null,
    override val locked: Boolean = true,
) : NodeInteractive

/**
 * 一个候选。
 *
 * ## ⚠️ 这些字段会直接展示给用户
 *
 * [label] 与 [detail] 都不得拼入凭据、token、内部地址等敏感信息。
 * 这与 [ActionResult.ShowMessage] 的既有约束一致。
 *
 * @property id 机器可读标识，**必填**；回传给插件的就是它
 * @property label 主文案
 * @property detail 次要说明（如最后活跃时间、消息条数）；空则不显示
 * @property tone 语义色调，用于状态标记（如异常会话）
 * @property disabled 置灰且不可选
 */
data class PickerCandidate(
    val id: String,
    val label: String,
    val detail: String = "",
    val tone: Tone = Tone.NEUTRAL,
    val disabled: Boolean = false,
)

/** 选择器的呈现方式 */
enum class PickerMode {
    /** 弹出对话框：候选多、或页面已经没有空位时用 */
    MODAL,

    /** 就地展开：候选少（三五个），用户想一眼看全并直接点 */
    INLINE,
}

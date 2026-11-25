package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.PluginNode

/**
 * 详情页展示的插件通用信息。
 *
 * 这些字段全部由宿主读出，**不来自插件自己**：插件在 UI 上说什么都可以，
 * 唯独「装的是哪个包、签名是什么、有没有被信任」必须由宿主说了算，
 * 否则详情页就成了插件自我认证的窗口。
 *
 * @property healthy null 表示还没采集过，不是「正常」也不是「异常」
 * @property healthMessage 仅在异常时有意义
 */
data class PluginDetailInfo(
    val pluginId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val metricCount: Int,
    val trusted: Boolean,
    val healthy: Boolean?,
    val healthMessage: String,
    val signatureHashes: List<String>,
    val needsLogin: Boolean,
    val loginText: String,
    val needsLoginAction: Boolean,
    /** 这次没问出登录态，界面应显示「登录状态待确认」而不是「未登录」 */
    val authStateUnknown: Boolean = false,
)

data class PluginDetailUiState(
    val loading: Boolean = true,
    /** 插件已不存在（例如在列表页停留期间被卸载），UI 应提示后退出 */
    val missing: Boolean = false,
    val info: PluginDetailInfo? = null,
    /** 插件自己决定要展示的节点树；插件未实现 detailUi 时为 null */
    val root: PluginNode? = null,
    /** 区块顺序策略：默认按插件给的顺序，自定义按用户拖动后的顺序 */
    val orderMode: BlockOrderMode = BlockOrderMode.DEFAULT,
    /** 「全部数据」：上次同步拿到的原始条目，按指标分组 */
    val rawGroups: List<RawRecordGroup> = emptyList(),
    /** 当前选中的时间段 */
    val rawRange: RawRange = RawRange.Today,
    /** 库里已有原始数据的日期范围 */
    val availableRange: Pair<String, String>? = null,
    /** 有动作正在执行，UI 应禁用全部插件按钮，防止连点并发调插件 */
    val busy: Boolean = false,
    /** 一次性提示，展示后由 UI 调 [PluginDetailViewModel.consumeMessage] 清掉 */
    val message: String? = null,
    /** 危险操作完成后置位，UI 据此关闭详情页回到列表 */
    val closed: Boolean = false,
)

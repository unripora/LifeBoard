package com.lifeboard.shared.platform

import com.lifeboard.plugin.api.ActionResult
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.LoginResult
import com.lifeboard.plugin.api.MetricSeries
import com.lifeboard.plugin.api.PluginHealth
import com.lifeboard.plugin.api.UiBlock

/**
 * 插件的**业务调用**契约。
 *
 * ## 为什么与 [PluginHost] 分开
 *
 * [PluginHost] 管的是「装了什么、信不信」——生命周期与信任，返回的都是宿主自己的状态；
 * 本接口管的是「让插件去干活」——登录、拉数、出界面，返回的都是插件产出的业务结果。
 *
 * 合成一个接口时，任何用到插件列表的页面都会被要求实现一整套业务方法
 * （包括桌面端还没有的采集链路），而这些方法在列表页上一个都不会被调用。
 * 拆开之后，列表页只依赖 [PluginHost]，详情页才依赖本接口。
 *
 * 另有一层更实际的原因：两个接口的**失败模式不同**。
 * [PluginHost] 的方法失败通常是宿主自己的 IO 问题；本接口的方法失败是
 * 插件实现的问题（脚本报错、返回结构不对），需要区别对待——
 * 后者必须把原因原样透出给用户，否则「点了按钮没反应」无从排查。
 *
 * ## 所有方法都按 pluginId 寻址
 *
 * 不接收插件实例，只接收 id：调用方（ViewModel）不该持有 QuickJS 运行时。
 * 实现方按 id 找回已加载的插件，找不到就返回明确的「未安装/未信任」，
 * 而不是抛异常——界面刷新时插件刚好被卸载是常态。
 *
 * ## 与采集链路的关系
 *
 * [collectNow] 是**手动触发一次采集**，与定时任务走同一条链路（含量纲校验、
 * 去重、健康度更新）。它不是「直接调 fetch」——那样会绕过宿主的全部检查，
 * 用户手动拉一次就可能写进脏数据。
 */
interface PluginInvoker {

    /**
     * 用用户填的输入尝试登录。
     *
     * @param inputs 键为插件 [com.lifeboard.plugin.api.CredentialSpec] 里声明的字段名
     */
    suspend fun login(pluginId: String, inputs: Map<String, String>): LoginResult

    /** 登出并清空凭据 */
    suspend fun logout(pluginId: String)

    /**
     * 当前登录状态。
     *
     * 返回 `null` 表示**这次问不出来**（运行时尚未就绪、已被关闭、插件抛异常等），
     * 与「插件明确回答未登录」不是一回事，调用方必须分开显示。
     *
     * 早先这里声明成非空并在失败时退回 [AuthState.LoggedOut]，
     * 后果是用户明明登录成功，界面却写着「未登录」——把一个瞬时故障
     * 当成了插件的结论，而且完全没有线索指向真正原因。
     */
    suspend fun authState(pluginId: String): AuthState?

    /**
     * 插件自己的详情区块（图表、按钮、说明）。
     *
     * 空列表表示插件没有自定义界面，调用方应展示通用内容（指标列表等）。
     */
    suspend fun detailUi(pluginId: String): List<UiBlock>

    /**
     * 执行详情页上某个交互。
     *
     * @param actionId 来自 [UiBlock] 里的 action 标识
     */
    suspend fun onAction(pluginId: String, actionId: String): ActionResult

    /** 插件自报的健康状态 */
    suspend fun health(pluginId: String): PluginHealth

    /**
     * 读插件的历史指标，供趋势图使用。
     *
     * 与 [collectNow] 分开：这个是**只读**，失败不影响采集状态，
     * 也不写库；[collectNow] 会改变库里的数据。
     */
    suspend fun metricSeries(pluginId: String, days: Int): List<MetricSeries>

    /**
     * 立即采集一次。
     *
     * @return 本次采集的结果，供界面提示「成功 / 无数据 / 失败原因」
     */
    suspend fun collectNow(pluginId: String): CollectOutcome
}

/**
 * 一次手动采集的结果。
 *
 * 刻意不直接用 `FetchResult`：那是插件返回给宿主的原始产物，
 * 而这里要表达的是「宿主处理完之后，用户该看到什么」——
 * 比如插件返回成功但所有指标都因量纲不符被丢弃，用户看到的不该是「成功」。
 */
sealed interface CollectOutcome {

    /** 采集成功，写入了 [written] 条指标 */
    data class Success(val written: Int, val skipped: Int = 0) : CollectOutcome

    /** 插件明确表示「今天没有数据」，属于正常结果 */
    data object NoData : CollectOutcome

    /** 插件未登录，界面应引导登录 */
    data object NotLoggedIn : CollectOutcome

    /** 采集失败。[reason] 直接展示给用户 */
    data class Failed(val reason: String) : CollectOutcome
}

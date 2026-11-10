package com.lifeboard.plugin.api

/**
 * 用户在详情页点了插件自己的按钮之后，插件返回什么。
 *
 * 返回值决定宿主接下来做什么，插件**不能**直接操作宿主界面——
 * 那等于把 UI 控制权交出去，且插件也无法保证在主线程上做对的事。
 */
sealed interface ActionResult {

    /**
     * 界面内容变了，用新的区块重新渲染。
     *
     * 这是最常用的返回：插件处理完动作后把最新的 [UiBlock] 列表一起带回来，
     * 宿主不必再问一次 [SourcePlugin.detailUi]，也就不会出现「点了按钮先闪一下旧内容」。
     */
    data class RefreshUi(val blocks: List<UiBlock>) : ActionResult

    /**
     * 只弹一句提示，界面不变。
     *
     * 用于「已提交」「请先在手机上确认」这类不需要重绘的反馈。
     * 文案会原样展示给用户，**插件不得把凭据等敏感内容拼进来**。
     */
    data class ShowMessage(val text: String) : ActionResult

    /**
     * 请求宿主立刻跑一次采集并落库。
     *
     * 插件拿不到数据库入口，也不该有——数据写入只走宿主既有的采集链路
     * （`SourceProviderRegistry` → `metric` 表），否则健康度、去重、量纲校验
     * 都会被绕过。需要「手动同步」时返回这个，宿主会等写完再刷新界面。
     */
    data object TriggerSync : ActionResult

    /** 什么都不做 */
    data object NoOp : ActionResult
}

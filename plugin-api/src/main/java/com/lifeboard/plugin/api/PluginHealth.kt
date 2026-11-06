package com.lifeboard.plugin.api

/**
 * 插件健康度。
 *
 * 约定（见 P 计划 M3.7）：插件内部的失败必须**返回**这些状态，不得向宿主抛出异常；
 * 宿主侧另有一层 try/catch 兜底，插件抛出的任何异常都会被视为 [Broken]，不影响其他源。
 */
sealed class PluginHealth {

    /** 正常，可以正常拉数 */
    data object Healthy : PluginHealth()

    /**
     * 可用但有隐患，如「登录即将过期」「部分分页失败」。
     *
     * 仍会继续调度：这是提醒，不是停用理由。
     */
    data class Degraded(val message: String) : PluginHealth()

    /**
     * 不可用，宿主停止对它调度。
     *
     * 触发场景：登录失效且无法续期、契约不匹配、指标定义冲突、加载或实例化失败。
     */
    data class Broken(val message: String) : PluginHealth()
}

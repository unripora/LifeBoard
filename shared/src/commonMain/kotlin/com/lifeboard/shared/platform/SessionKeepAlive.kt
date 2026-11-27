package com.lifeboard.shared.platform

/**
 * 会话存续期间的保活句柄。
 *
 * 会话要跨越多次调用（输入、消息、切换 key）。若只有「每次调用现申请」
 * 的短租约，两次输入之间实例是空闲的，按需加载的空闲回收会把监听和订阅
 * 一起关掉。[release] 必须幂等——停机可能被多条路径同时触发。
 */
interface SessionKeepAlive {
    /**
     * 在保活状态下执行一次插件会话调用。
     *
     * @return 实例已被强制停机（撤权/更新/故障）时为 `null`，
     *   调用方应给出「会话已断开」而不是笼统的「操作失败」
     */
    suspend fun <R> withPlugin(block: suspend () -> R): R?

    fun release()
}

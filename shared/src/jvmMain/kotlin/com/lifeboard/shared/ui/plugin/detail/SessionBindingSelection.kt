package com.lifeboard.shared.ui.plugin.detail

import com.lifeboard.plugin.api.PluginNode

/** 树决定需要哪些源；唯一会话 key 才能表达插件刷新后的切换意图。 */
internal data class SessionBindingRequirements(
    val sessionKeys: Set<String>,
    val hasSessionList: Boolean,
) {
    fun selectKey(activeKey: String, isActive: Boolean): String? = when {
        sessionKeys.isEmpty() -> null
        !isActive -> sessionKeys.first()
        sessionKeys.size == 1 -> sessionKeys.first()
        // 多个会话可能同时展示，不能因为区块重排或刷新而擅自切到第一条。
        activeKey.isNotBlank() -> activeKey
        else -> sessionKeys.first()
    }
}

internal fun sessionBindingRequirements(root: PluginNode?): SessionBindingRequirements {
    val refs = collectBindingRefs(root)
    return SessionBindingRequirements(
        sessionKeys = refs.filter { it.source == SessionBindingSource.SOURCE_NAME }
            .map { it.key }.toSet(),
        hasSessionList = refs.any { it.source == SessionListBindingSource.SOURCE_NAME },
    )
}

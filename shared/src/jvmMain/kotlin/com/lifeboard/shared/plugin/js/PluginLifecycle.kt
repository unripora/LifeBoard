package com.lifeboard.shared.plugin.js

/**
 * 插件运行时生命周期策略。
 *
 * ## 三档的语义
 *
 * | 档位 | 加载时机 | 普通释放条件 | 典型 |
 * |---|---|---|---|
 * | [ON_DEMAND] | 首次业务调用或租约申请 | 最后一个租约释放后空闲阈值到期 | 定时/手动同步型插件 |
 * | [SESSION_SCOPED] | 首次业务调用或租约申请 | 最后一个租约释放后立即进入停机 | 详情页型插件 |
 * | [RESIDENT] | 可信目录就绪、准入成功后预热 | 仅无租约时可因名额/预算降级 | 需要跨页面长连接的插件 |
 *
 * ## 声明与实际
 *
 * manifest 里的 `lifecycle` 只是**声明**。宿主还要看常驻名额、全局预算、
 * 回收总开关和包是否可重建，最终生效的是 [PluginRuntimePolicy.effective]。
 * 声明 `RESIDENT` 不等于获得名额。
 *
 * ## 默认值
 *
 * 缺省 [ON_DEMAND]，最省资源；需要更多能力的一方显式声明。
 */
enum class PluginLifecycle {
    /** 按需：空闲阈值到期释放 */
    ON_DEMAND,

    /** 会话范围：最后一个使用者离开后立即释放 */
    SESSION_SCOPED,

    /** 常驻：仅在名额/预算允许且无活跃租约时降级 */
    RESIDENT;

    companion object {
        /** 缺省档位：最保守，最少占用 */
        val DEFAULT: PluginLifecycle = ON_DEMAND

        /**
         * 解析 manifest 里的 `lifecycle` 字符串。
         *
         * @return 解析成功返回枚举；缺省返回 [DEFAULT]；非法值返回 `null`
         *   （由加载器转成明确拒载，不静默退化成默认值）
         */
        fun parse(raw: String?): PluginLifecycle? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return DEFAULT
            return when (text.lowercase()) {
                "ondemand" -> ON_DEMAND
                "sessionscoped" -> SESSION_SCOPED
                "resident" -> RESIDENT
                else -> null
            }
        }

        /** 空闲阈值允许范围（秒） */
        const val MIN_IDLE_SECONDS: Int = 60
        const val MAX_IDLE_SECONDS: Int = 3600

        /** 缺省空闲阈值（秒） */
        const val DEFAULT_IDLE_SECONDS: Int = 300
    }
}

/**
 * 某一插件在当前宿主上的生效策略。
 *
 * [declared] 是 manifest 声明的；[effective] 是宿主实际授予的。
 * 两者不一致时 [degradeReason] 说明原因，界面应展示，避免用户困惑
 * 「为什么声明了常驻却在被回收」。
 */
data class PluginRuntimePolicy(
    val declared: PluginLifecycle,
    val effective: PluginLifecycle,
    val idleTimeoutSec: Int,
    /** 生效策略与声明不一致时的可展示原因；一致时为 null */
    val degradeReason: String? = null,
) {
    /** 是否因为名额/预算被降级 */
    val degraded: Boolean get() = declared != effective

    companion object {
        fun of(
            declared: PluginLifecycle,
            idleTimeoutSec: Int? = null,
            effective: PluginLifecycle = declared,
            degradeReason: String? = null,
        ): PluginRuntimePolicy = PluginRuntimePolicy(
            declared = declared,
            effective = effective,
            idleTimeoutSec = (idleTimeoutSec ?: PluginLifecycle.DEFAULT_IDLE_SECONDS)
                .coerceIn(PluginLifecycle.MIN_IDLE_SECONDS, PluginLifecycle.MAX_IDLE_SECONDS),
            degradeReason = degradeReason,
        )
    }
}

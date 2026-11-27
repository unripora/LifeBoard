package com.lifeboard.shared.plugin.js

/**
 * 宿主能力集合：供 manifest `requires` 精确匹配。
 *
 * ## 为什么单独维护一份
 *
 * `requires` 声明的是「这个插件依赖宿主具备哪些能力」，必须与真实实现逐项对应。
 * 若直接拿 [JsHostFunctions.ALL]（`ctx.*` 函数名）充当能力集，就无法表达
 * 「生命周期」「登录意图」这类非函数能力，也无法避免把未实现的规划项误报为可用。
 *
 * ## 维护规则
 *
 * 一项能力在**代码确实可用并通过测试**后才可以加进来；规划中、尚未实现的能力
 * 不得提前写入，否则插件会以为自己能用而被放行。删除或改语义必须换新名称，
 * 不能用同名复用（旧宿主不会识别新语义）。
 */
object HostCapabilities {

    /** 运行时生命周期与租约策略 */
    const val RUNTIME_LIFECYCLE = "runtime.lifecycle"

    /** 登录意图（open/submit/restart/abandon） */
    const val CONTRACT_LOGIN_INTENT = "contract.loginIntent"

    /** 结构化错误码与错误信封 */
    const val CONTRACT_ERROR_CODE = "contract.errorCode"

    /** 文件读写（用于状态保存与缓存） */
    const val CTX_FILES_WRITE = "ctx.files.write"

    /** 凭据读写 */
    const val CTX_CREDENTIALS = "ctx.credentials"

    /**
     * 当前已实现、可供 `requires` 匹配的能力。**只列已验证可用项**
     *
     * [CONTRACT_LOGIN_INTENT] 已打通「宿主传意图 → JS 收第二个实参」，
     * 可以声明；[RUNTIME_LIFECYCLE] 只做到「解析 manifest 的 lifecycle」，
     * 回收默认关闭，因此**不**列入，避免插件误以为空闲释放一定生效。
     */
    val ALL: Set<String> = setOf(
        CONTRACT_ERROR_CODE,
        CONTRACT_LOGIN_INTENT,
        CTX_FILES_WRITE,
        CTX_CREDENTIALS,
    )

    /**
     * 校验插件声明的能力是否都被宿主满足。
     *
     * @param requires 插件 manifest 声明的必需能力；空表示不校验
     * @return 不满足的能力列表；空表示通过
     */
    fun unmet(requires: List<String>): List<String> =
        requires.filter { it !in ALL }

    /**
     * 生成可直接展示给用户的拒载原因。
     */
    fun rejectReason(requires: List<String>, unmet: List<String>): String =
        "插件需要宿主能力 ${unmet.joinToString("、")}，本机 LifeBoard 未提供；" +
            "请升级宿主或安装兼容版本（声明：${requires.joinToString("、")}）"
}

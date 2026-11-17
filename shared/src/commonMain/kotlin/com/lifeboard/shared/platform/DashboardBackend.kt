package com.lifeboard.shared.platform

/**
 * 仪表盘布局的宿主能力（M1-1.3）。
 *
 * 只有一件事：[ensureInitialized]。默认布局的写入涉及内置页签清单与默认卡片顺序，
 * 这些定义在 Android 业务层（`DashboardLayout`），属于「首次启动时把默认值落库」，
 * 与「读改页签与卡片」是两类操作——后者走 [com.lifeboard.shared.repository.DashboardStore]。
 *
 * 之所以不塞进 Store：Store 是纯数据访问，而写默认布局带有产品决策（默认放哪些卡）。
 */
interface DashboardBackend {

    /** 页签表为空时写入默认布局；已有数据时什么都不做 */
    suspend fun ensureDefaults()

    /** 是否处于「页签表为空」的状态，用于判断能否恢复默认 */
    suspend fun isLayoutEmpty(): Boolean
}

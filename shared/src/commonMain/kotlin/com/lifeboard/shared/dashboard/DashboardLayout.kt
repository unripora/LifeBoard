package com.lifeboard.shared.dashboard

import com.lifeboard.shared.repository.DashboardStore
import com.lifeboard.shared.repository.model.DashboardCard
import com.lifeboard.shared.repository.model.DashboardTab

/**
 * 默认布局的生成与读取。
 *
 * ## 为什么「首次读取时生成」而不是「迁移里插入」
 *
 * 迁移只该改结构。把内置页签和默认卡片写进迁移，等于把业务内容
 * 固化在数据库历史里——将来调整默认顺序，老用户和新用户会拿到不同的结果，
 * 而且必须再写一次迁移才能改。放在这里，判断依据是「表是不是空的」。
 *
 * ## 幂等
 *
 * 「判空 + 写入」必须在同一个事务里。先 count 再 insert 分开做的话，
 * 进程被杀在中间会留下一半的布局；而每次启动无条件写，
 * 会把用户删掉的卡片重新塞回来。判空与写入因此收敛到
 * [DashboardStore.ensureInitialized] 的一个事务里。
 */
object DashboardLayout {

    /**
     * 默认布局：与 M6 之前 `PanelScreen` 的显示顺序逐项对应。
     *
     * 对外可见是因为界面在数据库就绪前要先用它顶一帧；
     * 两端共用同一份清单，才不会出现「首帧用的顺序和落库后的顺序对不上」。
     */
    val DEFAULT_CARDS = listOf(
        CardType.SCORE,
        CardType.TODO,
        CardType.WEEK,
        CardType.SOURCE_HEALTH,
    )

    /**
     * 确保布局存在，返回当前页签列表（按 order 排序）。
     *
     * 只在页签表为空时写入默认值。页签非空就视为用户已有布局，一律不动——
     * 这是「用户把面板卡片全删光后，下次启动不会被塞满」的保证。
     * 卡片表单独不判空：用户很可能主动清空某个页签的卡片，那不该触发重建。
     */
    suspend fun ensureInitialized(store: DashboardStore): List<DashboardTab> =
        store.ensureInitialized(defaultTabs(), defaultCards())

    /** 内置页签的默认行，按 [BuiltinTabs.ALL] 的顺序编号 */
    fun defaultTabs(): List<DashboardTab> = BuiltinTabs.ALL.mapIndexed { index, tab ->
        DashboardTab(
            tabId = tab.tabId,
            name = tab.defaultName,
            order = index,
            isBuiltin = true,
        )
    }

    /** 内置面板的默认卡片，固定 id 以便重建时识别成「同一张默认卡」 */
    fun defaultCards(): List<DashboardCard> = DEFAULT_CARDS.mapIndexed { index, type ->
        DashboardCard(
            cardId = "default_${type.name.lowercase()}",
            type = type.name,
            order = index,
            tabId = BuiltinTabs.TAB_ID_PANEL,
        )
    }
}

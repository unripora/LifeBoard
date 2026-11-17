package com.lifeboard.shared.platform

import com.lifeboard.plugin.api.SourceRef

/**
 * 规则页的宿主能力（M1-1.3）。
 *
 * 规则本身是纯数据（走 `RuleStore`），但「这条规则此刻生效了吗」要看三样宿主状态：
 * 通知监听有没有开、无障碍有没有开、这个源今天记了多少。
 * 这三件事在桌面端要么不存在（无无障碍）、要么换成别的实现（读本地库而非通知监听），
 * 所以隔一层接口，而不是让 ViewModel 直接引用 `NoticeListenerService`。
 */
interface RulesBackend {

    /** 通知监听是否已授权 */
    suspend fun isNotificationListenerEnabled(): Boolean

    /** 无障碍采集服务是否已开启 */
    suspend fun isCaptureServiceEnabled(): Boolean

    /** 某个源某个指标今天的累计值，用于规则列表右侧的实时计数 */
    suspend fun todayCount(sourceKey: String, bucket: String): Double

    /**
     * 把某个源今天的计数归零（自测规则时反复用）。
     *
     * 放在后端而不是 ViewModel 里拼 `MetricRecord`：写指标要先由
     * 源的 key 还原出 `SourceRef`（自建源还要回查名称与量纲），
     * 这个解析是平台侧的职责，UI 层不该重新实现一遍。
     */
    suspend fun clearTodayCount(sourceKey: String, bucket: String)

    /** 规则变更后让采集端重新订阅包名 */
    suspend fun notifyRulesChanged()

    /** 规则变更后重排提醒闹钟 */
    suspend fun rescheduleReminders()

    /** 可被规则引用的数据源（内置 + 自建） */
    suspend fun selectableSources(): List<SourceRef>

    /** 某个源被多少条规则引用，删除自建源前用来拦截 */
    suspend fun referenceCount(sourceKey: String): Int
}

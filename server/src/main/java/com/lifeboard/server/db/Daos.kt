package com.lifeboard.server.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM task ORDER BY id ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<TaskEntity>

    @Query("SELECT * FROM task ORDER BY id ASC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    /** 统计有多少任务关联了该规则，规则删除前用来拦截 */
    @Query("SELECT COUNT(*) FROM task WHERE ruleId = :ruleId")
    suspend fun countByRule(ruleId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_record WHERE date = :date")
    fun observeByDate(date: String): Flow<List<DailyRecordEntity>>

    @Query("SELECT * FROM daily_record WHERE date = :date")
    suspend fun getByDate(date: String): List<DailyRecordEntity>

    @Query("SELECT * FROM daily_record WHERE taskId = :taskId AND date = :date")
    suspend fun getOne(taskId: Long, date: String): DailyRecordEntity?

    @Query("SELECT * FROM daily_record WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getRange(from: String, to: String): List<DailyRecordEntity>

    @Upsert
    suspend fun upsert(record: DailyRecordEntity)

    @Query("DELETE FROM daily_record WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}

@Dao
interface MetricDao {
    @Query("SELECT * FROM metric WHERE date = :date")
    suspend fun getByDate(date: String): List<MetricEntity>

    @Query("SELECT * FROM metric WHERE sourceType = :source AND date = :date AND bucket = :bucket")
    suspend fun getOne(source: String, date: String, bucket: String): MetricEntity?

    @Query("SELECT * FROM metric WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getRange(from: String, to: String): List<MetricEntity>

    /**
     * 某个源某个量纲在最近 [days] 天里**有数据的**最晚一天，没有返回 null。
     *
     * 指标卡要往前找「最近一次」的值（今天还没采集到 vs 今天真的是 0 是两回事），
     * 而这个查找必须在 SQL 里做：逐天把日期字符串减一天，跨月与夏令时都会出错。
     *
     * 按 bucket 而不是整个源过滤是必要的：小米健康一个源同时有步数与睡眠，
     * 前天采到了步数、昨天只采到睡眠，读「睡眠」卡不该拿到前天的日期。
     *
     * @param from 窗口起点（含），由调用方按本地日历算好
     */
    @Query(
        "SELECT MAX(date) FROM metric " +
            "WHERE sourceType = :source AND bucket = :bucket AND date BETWEEN :from AND :today",
    )
    suspend fun latestDateWithBucket(
        source: String,
        bucket: String,
        from: String,
        today: String,
    ): String?

    @Upsert
    suspend fun upsert(metric: MetricEntity)

    /**
     * 删除某个数据源的全部指标。
     *
     * 用于某个源被移除后清理残留：那些行已经没有生产者，
     * 留在表里会让面板出现一张永远不更新的卡片。
     */
    @Query("DELETE FROM metric WHERE sourceType = :sourceType")
    suspend fun deleteBySource(sourceType: String): Int
}

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak WHERE taskId = :taskId")
    suspend fun get(taskId: Long): StreakEntity?

    @Query("SELECT * FROM streak")
    fun observeAll(): Flow<List<StreakEntity>>

    @Query("SELECT * FROM streak")
    suspend fun getAll(): List<StreakEntity>

    @Upsert
    suspend fun upsert(streak: StreakEntity)

    @Query("DELETE FROM streak WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}

@Dao
interface NotificationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: NotificationLogEntity): Long

    @Query("SELECT * FROM notification_log ORDER BY triggeredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<NotificationLogEntity>
}

@Dao
interface SourceHealthDao {
    @Query("SELECT * FROM source_health")
    fun observeAll(): Flow<List<SourceHealthEntity>>

    @Query("SELECT * FROM source_health")
    suspend fun getAll(): List<SourceHealthEntity>

    @Upsert
    suspend fun upsert(health: SourceHealthEntity)

    /** 删除某个源的健康度记录，与 [MetricDao.deleteBySource] 配套使用 */
    @Query("DELETE FROM source_health WHERE sourceType = :sourceType")
    suspend fun deleteBySource(sourceType: String): Int
}

@Dao
interface RawNotificationDao {
    @Insert
    suspend fun insert(entry: RawNotificationEntity)

    /** 最近的原始通知，倒序 */
    @Query("SELECT * FROM raw_notification ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<RawNotificationEntity>>

    /** 只保留最近 N 条，聚合时顺手裁剪，防止无限膨胀 */
    @Query("DELETE FROM raw_notification WHERE id NOT IN (SELECT id FROM raw_notification ORDER BY postedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM raw_notification")
    suspend fun clear()
}

@Dao
interface CaptureRuleDao {
    @Query("SELECT * FROM capture_rule ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CaptureRuleEntity>>

    /** 采集时用：只取启用的规则，避免每次事件都过滤全表 */
    @Query("SELECT * FROM capture_rule WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabled(): List<CaptureRuleEntity>

    @Query("SELECT * FROM capture_rule WHERE packageName = :pkg AND enabled = 1")
    suspend fun getEnabledByPackage(pkg: String): List<CaptureRuleEntity>

    @Query("SELECT * FROM capture_rule ORDER BY createdAt ASC")
    suspend fun getAll(): List<CaptureRuleEntity>

    @Query("SELECT * FROM capture_rule WHERE id = :id")
    suspend fun getById(id: Long): CaptureRuleEntity?

    @Upsert
    suspend fun upsert(rule: CaptureRuleEntity): Long

    @Query("DELETE FROM capture_rule WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 统计某个数据源被多少条规则引用，删除自定义源前用来拦截 */
    @Query("SELECT COUNT(*) FROM capture_rule WHERE sourceName = :sourceName")
    suspend fun countBySource(sourceName: String): Int
}

@Dao
interface CustomSourceDao {
    @Query("SELECT * FROM custom_source ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CustomSourceEntity>>

    @Query("SELECT * FROM custom_source ORDER BY createdAt ASC")
    suspend fun getAll(): List<CustomSourceEntity>

    @Query("SELECT * FROM custom_source WHERE id = :id")
    suspend fun getById(id: Long): CustomSourceEntity?

    @Upsert
    suspend fun upsert(source: CustomSourceEntity): Long

    @Query("DELETE FROM custom_source WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AppUsageDao {

    /** 观察某天的分应用用量，按分钟降序，界面直接消费 */
    @Query("SELECT * FROM app_usage WHERE date = :date ORDER BY minutes DESC")
    fun observeByDate(date: String): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage WHERE date = :date ORDER BY minutes DESC")
    suspend fun getByDate(date: String): List<AppUsageEntity>

    /** 同一天同一应用覆盖写入，重复采集不产生重复行 */
    @Upsert
    suspend fun upsertAll(rows: List<AppUsageEntity>)

    @Query("DELETE FROM app_usage WHERE date = :date")
    suspend fun deleteByDate(date: String)

    /**
     * 只保留最近 N 天，清理更早的记录。
     *
     * 分应用数据每天几十行，不清会一直涨；用户实际只会回看最近一两周。
     */
    @Query("DELETE FROM app_usage WHERE date < :keepFrom")
    suspend fun trimBefore(keepFrom: String)
}

@Dao
interface DashboardTabDao {

    /** 全部页签，按用户排定的顺序 */
    @Query("SELECT * FROM dashboard_tab ORDER BY `order` ASC")
    fun observeAll(): Flow<List<DashboardTabEntity>>

    @Query("SELECT * FROM dashboard_tab ORDER BY `order` ASC")
    suspend fun getAll(): List<DashboardTabEntity>

    @Query("SELECT * FROM dashboard_tab WHERE tabId = :tabId")
    suspend fun getById(tabId: String): DashboardTabEntity?

    @Query("SELECT COUNT(*) FROM dashboard_tab")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(tab: DashboardTabEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<DashboardTabEntity>)

    @Query("DELETE FROM dashboard_tab WHERE tabId = :tabId")
    suspend fun deleteById(tabId: String)

    @Query("UPDATE dashboard_tab SET name = :name WHERE tabId = :tabId")
    suspend fun rename(tabId: String, name: String)

    @Query("UPDATE dashboard_tab SET `order` = :order WHERE tabId = :tabId")
    suspend fun setOrder(tabId: String, order: Int)
}

@Dao
interface DashboardCardDao {

    @Query("SELECT * FROM dashboard_card WHERE tabId = :tabId ORDER BY `order` ASC")
    fun observeByTab(tabId: String): Flow<List<DashboardCardEntity>>

    @Query("SELECT * FROM dashboard_card WHERE tabId = :tabId ORDER BY `order` ASC")
    suspend fun getByTab(tabId: String): List<DashboardCardEntity>

    @Query("SELECT COUNT(*) FROM dashboard_card")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(card: DashboardCardEntity)

    @Upsert
    suspend fun upsertAll(cards: List<DashboardCardEntity>)

    /**
     * 插入一张卡，被约束挡住时返回行 id ≤ 0。
     *
     * 为什么不用 @Upsert：upsert 的语义是「主键冲突就覆盖」，
     * 而卡片 id 是调用方新造的，重复插入只会悄悄覆盖一张已有的卡。
     * 用 `IGNORE` 让唯一索引冲突变成「什么都没发生」（返回 -1），调用方据此知道自己撞上了约束。
     *
     * 注意 `onConflict = IGNORE` 对**外键冲突不生效**：插进不存在的页签会直接抛
     * `SQLiteConstraintException`，调用方仍需 catch。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: DashboardCardEntity): Long

    @Query("DELETE FROM dashboard_card WHERE cardId = :cardId")
    suspend fun deleteById(cardId: String)

    /** 删除页签时级联清理它的卡片，否则会留下指向不存在页签的孤儿行 */
    @Query("DELETE FROM dashboard_card WHERE tabId = :tabId")
    suspend fun deleteByTab(tabId: String): Int

    @Query("UPDATE dashboard_card SET `order` = :order WHERE cardId = :cardId")
    suspend fun setOrder(cardId: String, order: Int)

    @Query("UPDATE dashboard_card SET tabId = :tabId WHERE cardId = :cardId")
    suspend fun moveToTab(cardId: String, tabId: String)
}

@Dao
interface PluginCredentialDao {

    /** 取某插件某个槽位的密文；没有返回 null */
    @Query("SELECT cipherText FROM plugin_credential WHERE pluginId = :pluginId AND slot = :slot")
    suspend fun get(pluginId: String, slot: String): String?

    /** 某插件名下全部槽位名，用于「清除前告知会影响哪些项」 */
    @Query("SELECT slot FROM plugin_credential WHERE pluginId = :pluginId ORDER BY slot ASC")
    suspend fun slots(pluginId: String): List<String>

    @Upsert
    suspend fun upsert(row: PluginCredentialEntity)

    /** 清空某个插件名下全部凭据，返回删除的行数 */
    @Query("DELETE FROM plugin_credential WHERE pluginId = :pluginId")
    suspend fun deleteByPlugin(pluginId: String): Int

    /** 删除单个槽位 */
    @Query("DELETE FROM plugin_credential WHERE pluginId = :pluginId AND slot = :slot")
    suspend fun deleteSlot(pluginId: String, slot: String)
}


package com.lifeboard.shared.repository

import com.lifeboard.core.model.DailyRecord
import com.lifeboard.core.model.StreakInfo
import com.lifeboard.core.model.TaskConfig
import kotlinx.coroutines.flow.Flow

/**
 * 任务与每日记录的**数据访问**契约。
 *
 * 注意这一层只负责存取，**不含业务逻辑**：闹钟联动、手动打卡、
 * 重新判定、得分计算都在 `:server` 的 `panel.TaskRepository` 里，
 * 它站在本接口之上做编排。之所以这样分层：
 * 业务逻辑依赖 `RemindScheduler`、`ConditionEvaluator` 等平台能力，
 * 而那些能力尚未抽成接口（1.4 才做），过早合并会形成循环依赖。
 *
 * 为什么任务与每日记录放在同一个接口：两者的生命周期绑死——
 * 删除任务必须同时删掉它的历史记录，否则会留下永远没人读的孤儿行，
 * 而趋势页按日期读记录时会把它们算进去。
 */
interface TaskStore {

    /** 全部任务，按 id 升序。界面订阅它做列表刷新 */
    fun observeAll(): Flow<List<TaskConfig>>

    suspend fun getAll(): List<TaskConfig>

    /** 启用中的任务，供调度器遍历 */
    suspend fun getEnabled(): List<TaskConfig>

    suspend fun getById(id: Long): TaskConfig?

    /** 统计有多少任务关联了该规则，规则删除前用来拦截 */
    suspend fun countByRule(ruleId: Long): Int

    /**
     * 保存任务。`id = 0` 时插入并返回新 id，否则按主键覆盖。
     *
     * 沿用原 DAO 的 REPLACE 语义：传了已存在的 id 时旧行会被整行替换，
     * 未显式赋值的列回到默认值。
     */
    suspend fun save(task: TaskConfig): Long

    /** 删除任务，同时清理它的每日记录与连击状态 */
    suspend fun deleteById(id: Long)

    fun observeRecordsByDate(date: String): Flow<List<DailyRecord>>

    suspend fun getRecordsByDate(date: String): List<DailyRecord>

    suspend fun getRecord(taskId: Long, date: String): DailyRecord?

    /** 闭区间内的记录，按日期升序 */
    suspend fun getRecordsInRange(from: String, to: String): List<DailyRecord>

    /**
     * 闭区间内每天每种来源的记录条数：`date` → (`origin` → 条数)。
     *
     * 单独一个方法而不是把 `origin` 塞进 [DailyRecord]：它是**写入语境**
     * （自动判定 / 手动打卡 / 迁移前的不可考），不是记录的属性。
     * 只有「手动打卡次数」这类图表需要它，为它污染所有读路径不值。
     */
    suspend fun countRecordsByOrigin(from: String, to: String): Map<String, Map<String, Int>>

    /** 写入某条记录，同 (taskId, date) 已存在时覆盖 */
    suspend fun saveRecord(record: DailyRecord)

    /**
     * 写入某条手动打卡记录。
     *
     * 与 [saveRecord] 的区别只在 `origin` 列：这里写 `MANUAL`，让「手动打卡次数」
     * 这类图表能把它和自动判定区分开。**不能**让 [saveRecord] 承担这个职责——
     * 那条路径的调用者是采集与判定链路，混进人工干预会让完成率统计失真。
     */
    suspend fun saveManualRecord(record: DailyRecord)

    suspend fun getStreak(taskId: Long): StreakInfo?

    fun observeAllStreaks(): Flow<List<StreakInfo>>

    suspend fun getAllStreaks(): List<StreakInfo>

    suspend fun saveStreak(streak: StreakInfo)
}

package com.lifeboard.shared.platform

import com.lifeboard.core.model.RemindLevel
import com.lifeboard.core.model.TaskConfig

/**
 * 提醒验证页所需的宿主能力（M1-1.3）。
 *
 * 这个页面里几乎每个动作都需要平台能力：查权限、算判定、发提醒、排闹钟。
 * 但页面本身只需要「能做这几件事」，不需要知道背后是 `AlarmManager` 还是别的。
 * 收成一个接口后，ViewModel 就不再继承 `AndroidViewModel`，桌面端也能注入自己的实现。
 */
interface RemindBackend {

    /** 权限自检结果，供界面逐项展示 */
    data class PermissionItem(
        val key: String,
        val title: String,
        val description: String,
        val granted: Boolean,
        val required: Boolean,
    )

    suspend fun checkPermissions(): List<PermissionItem>

    /** 当前启用的任务 */
    suspend fun listTasks(): List<TaskConfig>

    /** 新建任务，返回自增 id */
    suspend fun createTask(task: TaskConfig): Long

    suspend fun deleteTask(id: Long)

    /** 重排全部闹钟 */
    suspend fun rescheduleAll()

    suspend fun cancelTask(id: Long)

    /**
     * 立刻判定并提醒一次。
     *
     * @return 给用户看的一句话；任务不存在时返回 null
     */
    suspend fun evaluateAndRemind(taskId: Long): String?
}

/** 把提醒档位转成界面文案 */
fun remindLevelName(level: RemindLevel): String = when (level) {
    RemindLevel.NONE -> "不提醒"
    RemindLevel.WEAK -> "弱提醒"
    RemindLevel.STRONG -> "强提醒"
}

package com.lifeboard.server.platform

import android.content.Context
import com.lifeboard.core.model.TaskConfig
import com.lifeboard.core.util.Dates
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.server.remind.ConditionEvaluator
import com.lifeboard.server.remind.RemindPermissions
import com.lifeboard.server.remind.RemindScheduler
import com.lifeboard.server.remind.Reminder
import com.lifeboard.shared.platform.RemindBackend
import com.lifeboard.shared.platform.remindLevelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RemindBackend] 的 Android 实现。
 *
 * 全部委托既有实现（权限自检、条件判定、三档分发、闹钟排布），
 * 这里只做「把 `Context` 换成构造时注入」这一层薄封装，不搬任何业务逻辑。
 */
class AndroidRemindBackend(context: Context) : RemindBackend {

    private val appContext = context.applicationContext

    override suspend fun checkPermissions(): List<RemindBackend.PermissionItem> =
        withContext(Dispatchers.IO) {
            RemindPermissions.checkAll(appContext).map {
                RemindBackend.PermissionItem(
                    key = it.key,
                    title = it.title,
                    description = it.description,
                    granted = it.granted,
                    required = it.required,
                )
            }
        }

    override suspend fun listTasks(): List<TaskConfig> = withContext(Dispatchers.IO) {
        DatabaseProviders.taskStore(appContext).getEnabled()
    }

    override suspend fun createTask(task: TaskConfig): Long = withContext(Dispatchers.IO) {
        DatabaseProviders.taskStore(appContext).save(task)
    }

    override suspend fun deleteTask(id: Long) {
        withContext(Dispatchers.IO) { DatabaseProviders.taskStore(appContext).deleteById(id) }
    }

    override suspend fun rescheduleAll() {
        withContext(Dispatchers.IO) { RemindScheduler.rescheduleAll(appContext) }
    }

    override suspend fun cancelTask(id: Long) {
        withContext(Dispatchers.IO) { RemindScheduler.cancelTask(appContext, id) }
    }

    override suspend fun evaluateAndRemind(taskId: Long): String? {
        val result = ConditionEvaluator(appContext)
            .evaluate(taskId, Dates.todayString()) ?: return null
        val level = Reminder.dispatch(appContext, result)
        Reminder.logDelivery(appContext, result, actualLevel = level, delivered = true)
        val verdict = if (result.completed) "已达成" else "未达成"
        return "任务 #$taskId 判定 $verdict（值 ${result.value} / 阈值 " +
            "${result.task.threshold}），实际提醒：${remindLevelName(level)}"
    }
}

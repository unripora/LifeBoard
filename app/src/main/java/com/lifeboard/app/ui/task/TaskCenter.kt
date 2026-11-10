package com.lifeboard.app.ui.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台任务中心。
 *
 * 顶栏那颗旋转环要回答两个问题：「现在有活干吗」「刚才那些活干完了吗」。
 * 界面各处跑耗时操作时用 [begin] 占一个坑，结束时 [finish] 填结果，
 * 图标据此决定要不要转。
 *
 * ## 为什么是进程内单例而不是 ViewModel
 *
 * 发起任务的地方分散在好几个 ViewModel（插件列表、插件详情、面板卡片），
 * 而顶栏在 Activity 里。挂到任一 ViewModel 上都拿不到别人的任务，
 * 只能各自上报到一个共享的地方。这里的状态只用于展示，
 * 进程重启后历史任务已无意义，所以不做持久化。
 */
object TaskCenter {

    /** 跑完的任务最多留这么多条：它只是给人看最近发生了什么，不是审计日志 */
    private const val MAX_FINISHED = 30

    private val nextId = AtomicLong(1)

    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()

    /** 有任务在跑时顶栏图标要转 */
    fun isRunning(): Boolean = _tasks.value.any { it.running }

    /**
     * 登记一个正在跑的任务，返回它的句柄。
     *
     * 调用方必须在 `finally` 里 [TaskHandle.finish]：漏掉的话这个任务会永远停在
     * 「进行中」，图标一直转，用户看不出是卡住了还是真在干活。
     */
    fun begin(title: String, detail: String = ""): TaskHandle {
        val id = nextId.getAndIncrement()
        val task = BackgroundTask(
            id = id,
            title = title,
            detail = detail,
            startedAt = System.currentTimeMillis(),
            running = true,
        )
        _tasks.update { (listOf(task) + it).trimmed() }
        return TaskHandle(id)
    }

    private fun finish(id: Long, error: String?) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id != id || !task.running) return@map task
                task.copy(
                    running = false,
                    finishedAt = System.currentTimeMillis(),
                    error = error,
                )
            }.trimmed()
        }
    }

    /**
     * 裁剪：进行中的一条都不能丢，已完成的最多留 [MAX_FINISHED] 条。
     *
     * 列表是「新的在前」排的，所以顺次保留前 N 条已完成的就是最新的那些。
     */
    private fun List<BackgroundTask>.trimmed(): List<BackgroundTask> {
        var kept = 0
        return filter { task ->
            if (task.running) true else (kept++ < MAX_FINISHED)
        }
    }

    /** 一个任务的句柄。重复结束无副作用，方便写在多层 `runCatching` 里 */
    class TaskHandle internal constructor(private val id: Long) {

        private var done = false

        /** 正常结束 */
        fun finish() = finishInternal(null)

        /** 失败结束，[reason] 会展示在列表里，不该把堆栈塞进来 */
        fun fail(reason: String) = finishInternal(reason.ifBlank { "失败" })

        @Synchronized
        private fun finishInternal(error: String?) {
            if (done) return
            done = true
            TaskCenter.finish(id, error)
        }
    }
}

/**
 * 一条后台任务的展示状态。
 *
 * @param startedAt 毫秒时间戳，列表里换算成「刚刚 / 3 分钟前」
 * @param finishedAt 未结束时为 null
 * @param error 非 null 表示这次失败，列表里标红
 */
data class BackgroundTask(
    val id: Long,
    val title: String,
    val detail: String = "",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val running: Boolean = true,
    val error: String? = null,
)

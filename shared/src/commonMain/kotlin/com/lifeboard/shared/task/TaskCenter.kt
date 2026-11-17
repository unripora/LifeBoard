package com.lifeboard.shared.task

import com.lifeboard.shared.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * 而顶栏在页面壳里。挂到任一 ViewModel 上都拿不到别人的任务，
 * 只能各自上报到一个共享的地方。这里的状态只用于展示，
 * 进程重启后历史任务已无意义，所以不做持久化。
 *
 * ## 为什么放在 `:shared`
 *
 * 原本在 `:app`，桌面端拿不到——症状是桌面端飞书插件同步时顶栏毫无反应，
 * 用户看不出是在干活还是卡死了。两端跑的是同一份页面壳
 * （`DashboardHost`），任务指示器自然也该是同一份。
 */
object TaskCenter {

    /** 跑完的任务最多留这么多条：它只是给人看最近发生了什么，不是审计日志 */
    private const val MAX_FINISHED = 30

    private val nextId = AtomicLong(1)

    /**
     * 任务自身协程的宿主。
     *
     * ## 为什么任务不能挂在页面 scope 上
     *
     * 早先 [begin] 只登记状态，真正的耗时调用由调用方在**自己的** scope 里跑
     * （插件页拿的是 `rememberCoroutineScope()`，页面一离开就被取消）。
     * 症状是用户点「同步」后立刻切到别的页签，同步被静默掐断：没有结果、
     * 没有提示，任务环也随手消失，看起来像什么都没发生。
     *
     * 而「后台任务」的定义就是它该活过页面。所以这里自持一个
     * `SupervisorJob + Dispatchers.Default` 的进程级 scope：
     * 一个任务失败不影响别的任务，页面销毁也不会连带取消。
     *
     * `Dispatchers.Default` 而不是 `Main`：任务体大多是网络与数据库 IO，
     * 在 UI 线程上跑会把界面一起卡住；要更新 UI 的地方各自切回去。
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineName("LifeBoard-TaskCenter"),
    )

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

    /**
     * 在后台跑一段活，并自动把它登记成一条任务。
     *
     * 这是绝大多数调用方该用的入口：登记、执行、收尾三件事绑在一起，
     * 不可能出现「登记了但忘了结束」或「跑到一半页面没了」。
     * [block] 里抛出的异常会记为失败，但**不会**再抛回调用方——
     * 这是一个进程级的 fire-and-forget 入口，没有调用方在原地等结果；
     * 失败原因已经进了任务列表，界面照常提示自己那份就行。
     */
    fun launch(
        title: String,
        detail: String = "",
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val handle = begin(title, detail)
        return appScope.launch {
            try {
                block()
                handle.finish()
            } catch (error: CancellationException) {
                // 进程级 scope 正常不会被取消；真被取消时也要把状态收掉，
                // 否则任务永远停在「进行中」，指示环一直转
                handle.fail("已取消")
                throw error
            } catch (error: Throwable) {
                // 失败原因进任务列表，完整堆栈进日志。
                //
                // 只把 message 写进任务列表是因为那是给用户看的；
                // 但排查「Job was cancelled」这类 message 很含糊的失败时，
                // 光有一句文案根本定位不到来源——所以这里额外落一份堆栈。
                // 没有这一步，同一个错误会反复出现在不同层，
                // 只能靠猜哪一层抛的。
                AppLogger.error("后台任务失败：$title", error)
                handle.fail(error.message ?: error::class.simpleName ?: "失败")
            }
        }
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

package com.lifeboard.shared.plugin.js

import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.fs.ArchiveFingerprint
import com.lifeboard.shared.plugin.fs.PluginFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 插件目录变更监听：盘面指纹变了就回调一次。
 *
 * ## 为什么两端共用一份
 *
 * 「什么时候该重扫」是个策略问题，不是平台问题。安卓与桌面的差异只有
 * 「目录在哪」，那件事已经由 [PluginFileSystem] 收敛掉了。
 * 若各写一份，将来调轮询间隔或防抖窗口就要改两处，而且两端会慢慢漂移成不同行为——
 * 用户会遇到「同一个包拷进手机立刻出现，拷进电脑要重启」。
 *
 * ## 为什么轮询而不是文件系统事件
 *
 * `WatchService` 在 Android 上没有可靠实现，桌面端对「正在写入的文件」
 * 也会连发多次事件。而插件目录里通常只有几个包，一次 `listFiles` 的开销
 * 与一次目录事件的开销同量级。用轮询换来的是：两端同一套判断、不受平台差异影响。
 *
 * ## 防抖为什么不能省
 *
 * 用户把插件包从别处**拖进**目录时，文件先是 0 字节再逐渐变大，最后 mtime 还会再变一次。
 * 不防抖的话，最容易重扫的时机恰好是文件还没写完的时刻——结果是读到半个 zip，
 * 插件被判成「包已损坏」，而用户看着一个完好的包不知所措。
 * 所以「指纹稳定了 [quietMillis] 毫秒」才算一次真实变更。
 *
 * @param intervalMillis 轮询间隔
 * @param quietMillis 指纹需保持不变多久才认为变更结束
 * @param onChange 变更回调，在 [scope] 上串行执行
 */
class PluginDirWatcher(
    private val fileSystem: PluginFileSystem,
    private val suffix: String,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val quietMillis: Long = DEFAULT_QUIET_MILLIS,
    private val onChange: suspend () -> Unit,
) {

    @Volatile
    private var job: Job? = null

    /** 监听是否在跑。接入方据此避免重复启动 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 启动监听。
     *
     * 幂等：已在跑时直接返回。刻意**不**在启动时立即回调一次——
     * 首次扫描由调用方自己控制（它可能还要处理「尚未初始化」的时序），
     * 监听只负责「之后的变化」。
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        job = scope.launch {
            var last = snapshotSafely()
            while (isActive) {
                delay(intervalMillis)
                val current = snapshotSafely()
                if (current == last) continue

                // 指纹变了：再等到稳定为止，避免读到写了一半的包
                var stable = current
                while (isActive && stable != last) {
                    delay(quietMillis)
                    val next = snapshotSafely()
                    if (next == stable) break
                    stable = next
                }
                if (stable == last) continue

                last = stable
                runCatching { onChange() }
                    .onFailure { AppLogger.error("插件目录变更后重扫失败", it) }
                // 重扫本身会解压、重建运行时，期间目录可能又被改。
                // 重新取一次指纹作基准，避免把自己的解压产物当成用户改动而反复触发
                last = snapshotSafely()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 读指纹，失败当空列表。
     *
     * 目录被临时锁住（Windows 上杀毒软件、同步盘都可能）时会抛异常，
     * 这类瞬时失败不该让整个监听协程退出——退出后用户再改目录就永远没反应了。
     */
    private fun snapshotSafely(): List<ArchiveFingerprint> =
        runCatching { fileSystem.snapshot(suffix) }
            .getOrElse {
                AppLogger.warn("读取插件目录指纹失败：${it.message}")
                emptyList()
            }

    private companion object {
        /** 2 秒：用户感知上是「立刻」，而 2 秒一次目录列举几乎不耗电 */
        const val DEFAULT_INTERVAL_MILLIS = 2_000L

        /** 800 毫秒：足够覆盖「复制一个大包」的末尾抖动，又不会让人觉得卡顿 */
        const val DEFAULT_QUIET_MILLIS = 800L
    }
}

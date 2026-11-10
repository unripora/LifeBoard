package com.lifeboard.app

import android.app.Application
import android.content.Context
import com.lifeboard.server.collect.DailyAggregateWorker
import com.lifeboard.server.log.AppLogger
import com.lifeboard.server.mihealth.MiHealthMigration
import com.lifeboard.server.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用级初始化入口。
 *
 * 为什么必须有这一层：插件扫描原先放在 `MainActivity.onCreate()` 里，
 * 但 WorkManager 会在**没有 Activity** 的情况下为后台任务单独拉起进程。
 * 那种进程里插件注册表是空的，定时聚合会跑成「一个插件都没有」，
 * 表现为后台明明在执行、数据却一直不更新。
 *
 * 所以插件的注册与调度都收敛到这里：进程一起来先注册插件，再注册周期任务。
 * 放在后台协程里做，读 APK 元数据和实例化 dex 都是 IO，不能卡启动；
 * 失败也不影响应用启动，插件是可选能力。
 */
class LifeBoardApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 日志通道必须最先起来：后面的初始化即使失败，原因也得留在库里，
        // 否则用户遇到的只有「打不开」，没有任何可查的线索
        AppLogger.init(this)
        AppLogger.info("应用启动，版本 ${versionText()}")

        // SupervisorJob：一个初始化失败不该把其余的也一起取消
        appScope.launch {
            runCatching { PluginManager.refresh(this@LifeBoardApp) }
                .onFailure { AppLogger.error("启动时扫描插件失败", it) }

            // 小米健康从内置源改成 .ext 插件后，内置实现产出的历史行已无生产者。
            // 不清掉的话面板会留一张永远不更新的卡片；标记过就不再重复执行
            runCatching { MiHealthMigration.cleanupLegacyRows(this@LifeBoardApp) }
                .onFailure { AppLogger.warn("清理小米历史数据失败：${it.message.orEmpty()}") }
        }

        // 调度不依赖插件扫描结果：采集时还会再查一次注册表，
        // 而且周期任务注册本身很快，没必要等 IO 扫描完
        runCatching { DailyAggregateWorker.schedule(this) }
            .onFailure { AppLogger.error("注册自动刷新失败", it) }
    }

    companion object {

        /**
         * 版本号文本，形如 `0.2.0(2)`。
         *
         * 走 PackageManager 而不是 `BuildConfig`：后者在本模块没有开启生成，
         * 为了一个日志字段去改构建开关不值得。
         */
        private fun Context.versionText(): String = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName}($code)"
        }.getOrDefault("未知")

        /**
         * 应用级协程作用域。
         *
         * 用 [Dispatchers.Default] 而不是 IO：实例化插件要跑 DexClassLoader，
         * 属于 CPU + class loading 的混合负载，Default 的线程池语义更贴切。
         * 它是进程级单例，与 Application 同生命周期，不需要取消。
         */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

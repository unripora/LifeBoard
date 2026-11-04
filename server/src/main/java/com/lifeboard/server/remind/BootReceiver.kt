package com.lifeboard.server.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lifeboard.server.collect.DailyAggregateWorker

/**
 * 开机恢复接收器（M2.7）。
 *
 * AlarmManager 的闹钟在设备重启后全部丢失，必须在开机时重排。
 * 同时补一次日切聚合，因为关机期间可能跨过了 00:05 的定时任务。
 *
 * 注意：澎湃OS 需要「自启动」权限，否则本接收器不会被执行——
 * 这一点在权限引导页里显式提示，不能默默失效。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.i(TAG, "boot/replace received: $action")

        val app = context.applicationContext
        runCatching { RemindScheduler.rescheduleAll(app) }
            .onFailure { Log.e(TAG, "reschedule after boot failed", it) }
        runCatching { DailyAggregateWorker.schedule(app) }
            .onFailure { Log.e(TAG, "reschedule aggregate failed", it) }
    }

    companion object {
        private const val TAG = "LifeBoardBoot"
    }
}

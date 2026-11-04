package com.lifeboard.app.xposed

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * LSPosed 模块入口（Legacy Xposed API）。
 *
 * 为什么从 Modern API 换成 Legacy：
 * 本项目原用 `META-INF/xposed/java_init.list` + `XposedModule`（libxposed API 102）。
 * 该方式在本机（澎湃OS 3 / Android 16 / Vector 2.1.1）上被框架识别，管理器能显示模块、
 * 提示作用域变更，但 system_server 进程**从未真正注入**，且框架不打任何日志。
 * 同机 AutoAccounting（Legacy API）在 system_server 稳定工作，其余已加载模块中
 * Modern 与 Legacy 混杂，故改用与 AutoAccounting 完全相同的加载路径。
 *
 * 职责只有一件：在 system_server 里 hook 通知入队，抓 app 前台时发出的静默通知
 * （NotificationListenerService 收不到这类），通过显式广播回传给模块进程落库。
 *
 * 注意：本类不得依赖 app/server 模块的任何类。Legacy 入口在 system_server 中加载，
 * 任何对 UI/Room 的静态引用都可能触发类初始化失败并静默中断 hook。
 */
class LifeBoardEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        log("initZygote, modulePath=${startupParam?.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        val process = lpparam.processName
        // system_server 在 Legacy API 里的 packageName 是 "android"
        log("handleLoadPackage: pkg=$pkg process=$process")
        if (pkg == "android" && process == "android") {
            runCatching { installNotificationHook(lpparam.classLoader) }
                .onFailure { log("install notification hook failed: $it") }
        }
    }

    /**
     * 双通道日志：XposedBridge.log 会进 LSPosed 的 modules 日志，
     * 用来确认模块到底有没有被注入；android.util.Log 进 logcat，
     * 带堆栈。两边都写，避免任一路径被系统吞掉。
     */
    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching { XposedBridge.log("[$TAG] $message") }
    }

    private fun logError(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        runCatching { XposedBridge.log("[$TAG][E] $message ${t?.stackTraceToString() ?: ""}") }
    }

    /**
     * hook NotificationManagerService.enqueueNotificationInternal。
     *
     * hook 两个点：
     * 1. `enqueueNotificationInternal` —— 所有通知入队的必经之路，能抓到 app 前台时
     *    的静默/即发即撤通知。但它的签名随版本变动，args[0] 未必是包名。
     * 2. `notifyCallNotificationEventListenerOnPosted` —— 通知投递给监听器时触发，
     *    参数是 NotificationRecord，可从中取 StatusBarNotification 拿到可靠包名。
     *
     * 两点并用、内容去重合并：只靠第 1 个点在部分机型/版本上会漏抓。
     */
    private fun installNotificationHook(classLoader: ClassLoader) {
        val nmsClass = classLoader.loadClass(
            "com.android.server.notification.NotificationManagerService",
        )
        hookEnqueueAll(nmsClass)
        hookNotifyPosted(nmsClass)
    }

    private fun hookEnqueueAll(nmsClass: Class<*>) {
        // declaredMethods：目标方法不是 public，getMethods 会筛出 0 个
        val targets = nmsClass.declaredMethods.filter { it.name == ENQUEUE_METHOD }
        if (targets.isEmpty()) {
            logError("$ENQUEUE_METHOD not found; candidates=" +
                nmsClass.declaredMethods.filter { it.name.contains("nqueue") }
                    .joinToString { it.name })
            return
        }
        var hooked = 0
        targets.forEach { method ->
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { onEnqueue(param.args) }
                            .onFailure { logError("enqueue handle failed: ${it.message}") }
                    }
                })
                hooked++
            }.onFailure { logError("hook ${method.name} failed: ${it.message}") }
        }
        log("$ENQUEUE_METHOD hooked $hooked/${targets.size} overload(s)")
    }

    /**
     * 从 NotificationRecord 里取 StatusBarNotification，拿到可靠的包名与内容。
     * record 结构：NotificationRecord.sbn.packageName / .notification
     */
    private fun hookNotifyPosted(nmsClass: Class<*>) {
        val targets = nmsClass.declaredMethods.filter { it.name == NOTIFY_POSTED_METHOD }
        if (targets.isEmpty()) {
            log("$NOTIFY_POSTED_METHOD not found; candidates=" +
                nmsClass.declaredMethods.filter { it.name.contains("Posted") }
                    .joinToString { it.name })
            return
        }
        var hooked = 0
        targets.forEach { method ->
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { onPosted(param.args) }
                            .onFailure { logError("posted handle failed: ${it.message}") }
                    }
                })
                hooked++
            }.onFailure { logError("hook ${method.name} failed: ${it.message}") }
        }
        log("$NOTIFY_POSTED_METHOD hooked $hooked/${targets.size} overload(s)")
    }

    private fun onPosted(args: Array<Any?>) {
        val record = args.firstOrNull() ?: return
        val sbn = runCatching {
            record.javaClass.getField("sbn").apply { isAccessible = true }.get(record)
        }.getOrNull() ?: runCatching {
            record.javaClass.getDeclaredField("sbn").apply { isAccessible = true }.get(record)
        }.getOrNull() ?: return

        val sbnClass = sbn.javaClass
        val pkg = runCatching {
            sbnClass.getMethod("getPackageName").invoke(sbn) as? String
        }.getOrNull() ?: return
        val notification = runCatching {
            sbnClass.getMethod("getNotification").invoke(sbn) as? Notification
        }.getOrNull() ?: return

        handleNotification(pkg, notification, source = "posted")
    }

    /** 参数形态（Android 8+）：args[0]=pkg，其后某位是 Notification */
    private fun onEnqueue(args: Array<Any?>) {
        val pkg = args.getOrNull(0) as? String
        if (pkg == null) {
            log("enqueue: pkg is null, argCount=${args.size}")
            return
        }
        val notification = args.filterIsInstance<Notification>().firstOrNull()
        if (notification == null) {
            log("enqueue: notification not found, pkg=$pkg")
            return
        }
        handleNotification(pkg, notification, source = "enqueue")
    }

    /**
     * 两个 hook 点共用的处理逻辑。
     *
     * 不做包名白名单：要抓哪些应用由用户在模块进程的规则表里配置，
     * system_server 侧读不到那份配置，也不该把应用名单写死在代码里。
     * 全部通知都回传，由接收端按规则判定，未命中只进原始流水不计数。
     */
    private fun handleNotification(pkg: String, notification: Notification, source: String) {
        val extras: Bundle? = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = buildString {
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it) }
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }

        // 先留痕再过滤：不打印日志就无法判断目标应用的通知是否到达 hook。
        log("[$source] app=$pkg title=${title.take(40)} text=${text.take(60)}")

        if (title.isBlank() && text.isBlank()) {
            log("[$source] skip blank content, pkg=$pkg")
            return
        }

        // 去重：两个 hook 点会报同一条通知，通知更新也会重复入队
        if (isDuplicate(pkg, title, text)) {
            log("[$source] skip duplicate, pkg=$pkg")
            return
        }
        sendBroadcast(pkg, title, text, source)
    }

    private fun isDuplicate(pkg: String, title: String, text: String): Boolean =
        synchronized(recent) {
            val now = System.currentTimeMillis()
            recent.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
            val key = "$pkg|$title|$text"
            if (recent.containsKey(key)) {
                true
            } else {
                recent[key] = now
                while (recent.size > 200) recent.remove(recent.keys.first())
                false
            }
        }

    /** system_server → 模块进程的显式广播，extras 带通知原文 */
    private fun sendBroadcast(pkg: String, title: String, text: String, source: String) {
        val intent = Intent(ACTION_NOTICE_REPORTED).apply {
            setPackage(RECEIVER_PACKAGE)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TIME, System.currentTimeMillis())
            // 前台广播：接收方进程被缓存/回收时也立即拉起，不排队延迟
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val sysContext = systemContext()
        if (sysContext == null) {
            logError("system context unavailable, drop notification $pkg")
            return
        }
        sysContext.sendBroadcast(intent)
        log("[$source] broadcast sent: pkg=$pkg title=${title.take(30)}")
    }

    /** system_server 里的系统上下文：ActivityThread.getSystemContext() */
    private fun systemContext(): Context? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val field = atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }
        val at = field.get(null) ?: return null
        atClass.getMethod("getSystemContext").invoke(at) as? Context
    }.getOrNull()

    companion object {
        private const val TAG = "LifeBoardXposed"
        private const val ENQUEUE_METHOD = "enqueueNotificationInternal"
        private const val NOTIFY_POSTED_METHOD = "notifyCallNotificationEventListenerOnPosted"
        private const val DEDUP_WINDOW_MS = 10_000L

        private val recent = LinkedHashMap<String, Long>()

        const val ACTION_NOTICE_REPORTED = "com.lifeboard.action.SYSTEM_NOTICE_REPORTED"
        const val RECEIVER_PACKAGE = "com.lifeboard.app"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIME = "time"
    }
}

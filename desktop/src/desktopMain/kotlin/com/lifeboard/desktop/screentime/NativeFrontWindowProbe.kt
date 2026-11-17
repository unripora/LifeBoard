package com.lifeboard.desktop.screentime

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 用 Win32 读「当前前台窗口属于哪个进程」（M11）。
 *
 * ## 为什么不用 JNA 的 `platform` 封装
 *
 * `com.sun.jna.platform.win32.User32` 这个类在 `jna-platform` 包里，
 * 而项目只依赖了核心包 `net.java.dev.jna:jna`。
 * 为了几个 API 多引一个 jar 不划算，所以这里和
 * [com.lifeboard.desktop.secret.DpapiSecretCodec] 一样，
 * 直接 `Native.load` 声明需要的那几个函数——那个类是项目里已有的先例。
 *
 * ## 为什么不用 `ProcessHandle` 之类的高层 API
 *
 * JDK 没有「前台窗口是哪个进程」的概念，且这个信息只在 Win32 层有。
 * 唯一的高层替代是轮询 `Get-Process | Where MainWindowHandle -ne 0`，
 * 那是启动一个 PowerShell 子进程——每秒一次会把 CPU 吃掉。
 *
 * ## 拿不到信息时怎么办
 *
 * 一律返回 null，绝不抛异常。原因：
 * 这是每 30 秒跑一次的后台采样，任何一次异常若冒出去，
 * 上层的定时器会被打断，屏幕时长从此永久停止记录，而且**不会有任何提示**。
 * 少记一个采样点是可以接受的损失，静默停摆不是。
 */
class NativeFrontWindowProbe : FrontWindowProbe {

    /**
     * 只在 Windows 上启用。
     *
     * 非 Windows 上 `Native.load("User32")` 会抛 `UnsatisfiedLinkError`，
     * 若在类初始化时抛，会让整个 `PlatformDependencies` 装配失败——
     * 那意味着应用根本打不开，只因为一个统计功能不可用。
     */
    override val available: Boolean by lazy { isWindows() && load() != null }

    private val labelCache = ConcurrentHashMap<String, String>()

    override fun sample(): ForegroundSample? {
        val user32 = load() ?: return null

        return runCatching {
            // 人不在（锁屏 / 离开）时不计入任何应用。
            // 这一步放在最前面：先把「这段时间算不算使用」判掉，
            // 后面取窗口才有意义
            if (isIdle(user32)) return null

            val hwnd = user32.GetForegroundWindow() ?: return null
            // 零句柄是「没有前台窗口」（切换桌面、锁屏中）
            if (hwnd == Pointer.NULL) return null

            val pidRef = IntByReference()
            user32.GetWindowThreadProcessId(hwnd, pidRef)
            val pid = pidRef.value
            if (pid <= 0) return null

            val exe = exeNameOf(pid) ?: return null
            val label = labelCache.getOrPut(exe) { friendlyName(exe) }
            ForegroundSample(packageName = exe, label = label)
        }.getOrNull()
    }

    /**
     * 用户是否已经空闲超过阈值。
     *
     * `GetLastInputInfo` 给的是「最后一次键鼠输入的系统 tick 数」，
     * 与 `GetTickCount` 同一基准，两者相减就是空闲毫秒数。
     *
     * 拿不到 `LASTINPUTINFO` 时返回 `false`（当作不空闲）：
     * 宁可多记一点，也不要因为取不到空闲信息就把全部时长清零——
     * 后者会让用户以为「统计坏了」。
     */
    private fun isIdle(user32: User32): Boolean = runCatching {
        val info = LASTINPUTINFO().apply { cbSize = size() }
        if (!user32.GetLastInputInfo(info)) return false
        val now = user32.GetTickCount()
        // 注意方向：tick 是 DWORD，会回绕（约 49 天一次）。
        // 用无符号差值算，回绕那一刻不会变成「空闲了 49 天」
        val idleMillis = now.toUInt().minus(info.dwTime.toUInt()).toLong()
        idleMillis >= IDLE_THRESHOLD_MILLIS
    }.getOrDefault(false)

    /**
     * 拿进程的可执行文件名。
     *
     * 用 `QueryFullProcessImageNameW` 而不是 `GetModuleFileNameEx`：
     * 后者需要 `PROCESS_VM_READ` 权限，对部分进程会失败；
     * 前者只要 `PROCESS_QUERY_LIMITED_INFORMATION`，
     * 对绝大多数用户态进程都能读，且本身就在 Kernel32 里。
     */
    private fun exeNameOf(pid: Int): String? = runCatching {
        val kernel32 = loadKernel32() ?: return null
        val process = kernel32.OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION,
            false,
            pid,
        )
        // 零句柄表示打不开（受保护进程 / 已退出）
        if (process == null || process == Pointer.NULL) return null
        val handle: Pointer = process
        try {
            val buffer = CharArray(MAX_PATH)
            val sizeRef = IntByReference(MAX_PATH)
            val ok = kernel32.QueryFullProcessImageNameW(handle, 0, buffer, sizeRef)
            if (!ok) return null
            val full = String(buffer, 0, sizeRef.value)
            // 只留最后一段：`C:\...\chrome.exe` → `chrome.exe`。
            // 用完整路径当 key 的话，同一个程序从不同路径启动会被算成两个应用
            full.substringAfterLast('\\', full).substringAfterLast('/', full)
        } finally {
            kernel32.CloseHandle(handle)
        }
    }.getOrNull()

    /**
     * 把 exe 名换成更容易读的名字。
     *
     * `Code.exe` → `Code`、`chrome.exe` → `chrome`。
     * 不去查文件版本信息里的「文件说明」：那要再调一次
     * `GetFileVersionInfo` 一整套 API，收益不抵复杂度。
     */
    private fun friendlyName(exe: String): String {
        val base = exe.substringBeforeLast('.', exe)
        return base.ifBlank { exe }
    }

    /** 惰性加载，只试一次；失败就一直返回 null，不反复抛异常 */
    private val user32: User32? by lazy {
        if (!isWindows()) null else runCatching { loadUser32Raw() }.getOrNull()
    }

    private val kernel32: Kernel32? by lazy {
        if (!isWindows()) null else runCatching { loadKernel32Raw() }.getOrNull()
    }

    private fun load(): User32? = user32

    private fun loadKernel32(): Kernel32? = kernel32

    // ---------------------------------------------------------------------
    // Win32 声明
    // ---------------------------------------------------------------------

    private interface User32 : Library {
        fun GetForegroundWindow(): Pointer?

        fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntByReference): Int

        fun GetLastInputInfo(plii: LASTINPUTINFO): Boolean

        fun GetTickCount(): Int
    }

    private interface Kernel32 : Library {
        fun OpenProcess(dwDesiredAccess: Int, bInheritHandle: Boolean, dwProcessId: Int): Pointer?

        fun QueryFullProcessImageNameW(
            hProcess: Pointer,
            dwFlags: Int,
            lpExeName: CharArray,
            lpdwSize: IntByReference,
        ): Boolean

        fun CloseHandle(hObject: Pointer): Boolean
    }

    /**
     * `LASTINPUTINFO` 结构体。
     *
     * 用 JNA 的 `Structure` 而不是手搓 `Memory`：这个结构只有两个 DWORD，
     * 但字段对齐仍然要遵守 Win32 规则，让 JNA 处理更稳。
     * 注意 `dwTime` 声明为 `Int`（DWORD），计算时按无符号处理。
     */
    @Suppress("ClassName")
    class LASTINPUTINFO : com.sun.jna.Structure() {
        @JvmField
        var cbSize: Int = 0

        @JvmField
        var dwTime: Int = 0

        override fun getFieldOrder(): List<String> = listOf("cbSize", "dwTime")
    }

    private companion object {
        const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        const val MAX_PATH = 1024
        const val IDLE_THRESHOLD_MILLIS = 5 * 60_000L

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun loadUser32Raw(): User32 = Native.load("User32", User32::class.java)

        fun loadKernel32Raw(): Kernel32 = Native.load("Kernel32", Kernel32::class.java)
    }
}

/**
 * 非 Windows 平台的探测器：明确报告「不可用」。
 *
 * 不抛异常、不返回假数据——上层据此让面板显示「当前环境不支持」，
 * 而不是显示一个永远 0 分钟的面板让人以为在记却没记。
 */
object UnavailableFrontWindowProbe : FrontWindowProbe {
    override val available: Boolean = false
    override fun sample(): ForegroundSample? = null
}

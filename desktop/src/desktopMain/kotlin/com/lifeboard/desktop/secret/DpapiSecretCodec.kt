package com.lifeboard.desktop.secret

import com.lifeboard.shared.platform.SecretCodec
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.util.Base64

/**
 * 用 Windows DPAPI 做加解密的 [SecretCodec]（M4-4.3）。
 *
 * ## 为什么是 DPAPI 而不是自己管一把密钥
 *
 * 「把密钥存在哪」是个没有正解的问题：存明文文件等于没加密；内置一个固定密钥
 * 等于把密钥连同二进制一起发出去；要用户记一个主密码，就变成每次启动都要输。
 *
 * DPAPI 把这件事交给操作系统：它从**当前登录用户的凭据**派生主密钥
 * （不传 `CRYPTPROTECT_LOCAL_MACHINE` 时就是 `CurrentUser` 范围），
 * 应用既不用保存密钥，也不用管理生命周期。代价是明确且可接受的：
 * **换 Windows 账户或重装系统后，旧密文解不开**——那时正确行为就是
 * 「当作没有凭据」，让用户重新登录一次插件。
 *
 * ## 为什么密文能跨进程、跨升级使用
 *
 * DPAPI 的输出自带完整性校验（HMAC）与密钥标识，解密时由系统自己找回对应主密钥。
 * 所以密文可以直接存进 SQLite，重启应用、升级版本都不影响；
 * 只要还是同一个 Windows 用户，就能解开。
 *
 * ## 与 Android Keystore 的对齐
 *
 * 两端的**契约**完全一致（见 [SecretCodec]）：每次加密用新 nonce、密文自包含、
 * 解密失败返回 null 不抛异常。Android 侧是 AES-GCM + 随机 IV，这边是
 * DPAPI 内部处理，对调用方没有区别。
 *
 * ## 只能在 Windows 上构造
 *
 * 其它系统上 `Crypt32` 库不存在，[Native.load] 会直接抛
 * `UnsatisfiedLinkError`。装配层负责只在 Windows 上用它，
 * 非 Windows 换 [SecretCodec.Insecure] 并在启动日志里说明。
 */
class DpapiSecretCodec : SecretCodec {

    override fun encrypt(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        // 入参缓冲要活到 DPAPI 调用结束，用 Memory 显式分配
        val inMem = Memory(data.size.coerceAtLeast(1).toLong()).also {
            it.write(0, data, 0, data.size)
        }
        val inBlob = DATA_BLOB(data.size, inMem)

        val cipherBytes = outBlob { out ->
            val ok = Crypt32.INSTANCE.CryptProtectData(
                inBlob,
                // 描述串会随密文一起保存，本身不加密。用来在调试时看出
                // 「这坨密文是哪段数据」，不要往里放任何敏感内容
                WString(DESCRIPTION),
                null,
                null,
                null,
                CRYPTPROTECT_UI_FORBIDDEN,
                out,
            )
            if (!ok) null else readOut(out)
        } ?: error("DPAPI 加密失败，GetLastError=${Native.getLastError()}")

        return Base64.getEncoder().encodeToString(cipherBytes)
    }

    override fun decrypt(cipherText: String): String? {
        val cipher = runCatching { Base64.getDecoder().decode(cipherText) }.getOrNull() ?: return null
        if (cipher.isEmpty()) return null

        val inMem = Memory(cipher.size.toLong()).also { it.write(0, cipher, 0, cipher.size) }
        val inBlob = DATA_BLOB(cipher.size, inMem)

        // 密钥不可用（换了 Windows 账户 / 系统重置）或密文被篡改时，
        // CryptUnprotectData 返回 false。契约要求「当作没有凭据」而不是崩，
        // 所以这里从头到尾不抛异常
        val plainBytes = outBlob { out ->
            val ok = Crypt32.INSTANCE.CryptUnprotectData(
                inBlob,
                null,
                null,
                null,
                null,
                CRYPTPROTECT_UI_FORBIDDEN,
                out,
            )
            if (!ok) null else readOut(out)
        } ?: return null

        return String(plainBytes, Charsets.UTF_8)
    }

    // ───────────────────────── 内存管理 ─────────────────────────

    /**
     * 准备一个出参 [DATA_BLOB]，把内容读出来并保证释放。
     *
     * 三步顺序不能换：
     *
     * 1. `block(out)` 让 DPAPI 往里写
     * 2. `readOut(out)` **先读出字节**——`pbData` 是 DPAPI 用 `LocalAlloc`
     *    分配的裸内存，`LocalFree` 之后就是野指针，晚一步读到的就是已回收的堆
     * 3. `finally` 里 `LocalFree`
     *
     * 漏掉第 3 步是每次读凭据泄漏一小块原生内存（GC 看不到它）；
     * 把第 3 步提到第 2 步之前则是读到垃圾数据。
     */
    private inline fun outBlob(block: (DATA_BLOB) -> ByteArray?): ByteArray? {
        val out = DATA_BLOB()
        return try {
            block(out)
        } finally {
            out.pbData?.let { Kernel32.INSTANCE.LocalFree(it) }
        }
    }

    /**
     * 从 DPAPI 写好的出参结构里取字节。
     *
     * **必须显式 `read()`**：JNA 只在传参方向（Java → 原生）自动同步字段，
     * 原生侧改写结构体之后，Java 看到的 `cbData` / `pbData` 还是构造时的旧值（0 / null）。
     * 大 buffer 偶尔能过是读到栈上残留值，属于不确定行为——这正是本实现
     * 第一版在小数据上通过、4096 字节上崩溃的原因。
     */
    private fun readOut(out: DATA_BLOB): ByteArray {
        out.read()
        val ptr = out.pbData ?: return ByteArray(0)
        return ptr.getByteArray(0, out.cbData)
    }

    // ───────────────────────── 原生绑定 ─────────────────────────

    /**
     * DPAPI 的 `DATA_BLOB` 结构。
     *
     * 字段顺序与 `minwindef.h` 一致，不能调换：JNA 按声明顺序按平台默认对齐。
     * 64 位下 `cbData` 是 DWORD（4 字节），后面跟 4 字节填充再放指针，
     * JNA 自动插入填充，与 C 编译器一致。
     */
    @Suppress("PropertyName")
    class DATA_BLOB() : com.sun.jna.Structure() {
        @JvmField
        var cbData: Int = 0

        @JvmField
        var pbData: Pointer? = null

        constructor(cbData: Int, pbData: Pointer) : this() {
            this.cbData = cbData
            this.pbData = pbData
        }

        override fun getFieldOrder(): List<String> = listOf("cbData", "pbData")
    }

    private interface Crypt32 : Library {
        /**
         * 加密。
         *
         * 传 [CRYPTPROTECT_UI_FORBIDDEN] 表示不弹任何 UI。
         * 后台同步时弹窗会让进程卡死——没有用户在场，窗口永远等不到点击。
         */
        fun CryptProtectData(
            pDataIn: DATA_BLOB,
            szDataDescr: WString?,
            pOptionalEntropy: Pointer?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DATA_BLOB,
        ): Boolean

        fun CryptUnprotectData(
            pDataIn: DATA_BLOB,
            ppszDataDescr: PointerByReference?,
            pOptionalEntropy: Pointer?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DATA_BLOB,
        ): Boolean

        companion object {
            val INSTANCE: Crypt32 = Native.load("Crypt32", Crypt32::class.java)
        }
    }

    private interface Kernel32 : Library {
        fun LocalFree(hMem: Pointer): Pointer?

        companion object {
            val INSTANCE: Kernel32 = Native.load("Kernel32", Kernel32::class.java)
        }
    }

    private companion object {
        /** 不弹 UI。后台路径必须带它 */
        const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

        /** 随密文保存的描述串，仅用于调试辨识 */
        const val DESCRIPTION = "LifeBoard plugin credential"
    }
}

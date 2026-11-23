package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.shared.plugin.json.PluginJson
import com.lifeboard.shared.plugin.json.PluginJsonArray
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 把哈希与编解码能力暴露给 JS 的 `ctx.crypto.*` / `ctx.codec.*`。
 *
 * ## 为什么要桥接而不是纯 JS 实现
 *
 * QuickJS 上下文是裸的 `JS_NewContext`，没有 `crypto`、`btoa`、`TextEncoder`。
 * 纯 JS 手写 MD5 / SHA-1 / SHA-256 / RC4 当然可行，但代价是：
 * - 小米健康这类插件每次登录要算十几次摘要，纯 JS 版本比 native 慢一到两个数量级
 * - 手写实现正确性靠自测保证，一旦某个填充字节写错，症状是「服务端返回 70016」，
 *   排查成本极高，且错误模式与真实的风控拦截无法区分
 *
 * 宿主侧用 JDK 的 [MessageDigest] 已经过生产验证，经 JNI 调一次的开销远小于
 * 在 JS 里跑一遍压缩函数，因此这里选择桥接。
 *
 * ## 不提供的部分
 *
 * RC4 不在 JDK 里（且已被认为不安全），但它只是个 256 字节的状态机，
 * 用纯 JS 实现既短又快（每次加密几十字节），所以留在插件侧实现。
 * 本桥只暴露「字节 → 摘要」这一层，不碰具体协议。
 *
 * ## 为什么还要 randomBytes 与字节形态编解码
 *
 * 上游密码栈（`@dsh-remote/crypto`）用 `crypto.getRandomValues` 取随机数、
 * 用 `btoa`/`Buffer` 做 base64、用 `TextEncoder` 做 UTF-8。QuickJS 三者都没有。
 * 缺了它们，插件只能自己手写，而随机数手写就等于没有随机性。
 * 所以这里补齐这四类基础能力，让上游代码只需注入一层薄 polyfill 即可原样运行。
 *
 * 摘要类接口一律「字节进」，不提供字符串进：字符串到字节的编码由插件决定
 * （UTF-8 / GBK），宿主替它决定会在非 ASCII 输入上产生难以察觉的不一致。
 */
class JsCryptoBridge : HostFunctionDispatcher {

    fun functionNames(): List<String> = JsHostFunctions.CRYPTO

    override fun invoke(name: String, argsJson: String): String {
        val args = runCatching { PluginJsonArray(argsJson) }
            .getOrElse { return HostFunctionDispatcher.error("参数不是数组") }
        return try {
            when (name) {
                "ctx.crypto.md5Hex" -> md5Hex(bytesArg(args, 0))
                "ctx.crypto.sha1Base64" -> sha1Base64(bytesArg(args, 0))
                "ctx.crypto.sha256Base64" -> sha256Base64(bytesArg(args, 0))
                "ctx.crypto.sha256Hex" -> sha256Hex(bytesArg(args, 0))
                "ctx.crypto.sha256Bytes" -> bytesToJson(digest("SHA-256", bytesArg(args, 0)))
                "ctx.crypto.randomBytes" -> randomBytes(countArg(args, 0))
                "ctx.codec.base64Encode" -> base64Encode(bytesArg(args, 0))
                "ctx.codec.base64Decode" -> bytesToJson(base64Decode(stringArg(args, 0)))
                "ctx.codec.base64UrlEncode" -> base64UrlEncode(bytesArg(args, 0))
                "ctx.codec.base64UrlDecode" -> bytesToJson(base64UrlDecode(stringArg(args, 0)))
                "ctx.codec.utf8Encode" -> bytesToJson(textArg(args, 0).toByteArray(Charsets.UTF_8))
                "ctx.codec.utf8Decode" -> PluginJson.quote(String(bytesArg(args, 0), Charsets.UTF_8))
                else -> HostFunctionDispatcher.error("unknown crypto function: $name")
            }
        } catch (e: IllegalArgumentException) {
            HostFunctionDispatcher.error(e.message ?: "参数非法")
        }
    }

    /**
     * 取第一个参数当「字节数」。
     *
     * 上界 4096 是为了挡住插件把长度写成变量后一路放大到内存耗尽的低级错误；
     * 密码场景里最长的是握手 payload（约 1KB 级），4096 足够用。
     */
    private fun countArg(args: PluginJsonArray, index: Int): Int {
        val n = args.optInt(index, -1)
        require(n in 1..MAX_RANDOM_BYTES) { "字节数必须在 1..$MAX_RANDOM_BYTES，实际为 $n" }
        return n
    }

    /** 取第一个参数当普通字符串，**允许空串**（空串的 UTF-8 是零字节，合法）。 */
    private fun textArg(args: PluginJsonArray, index: Int): String =
        args.optStringOrNull(index)
            ?: throw IllegalArgumentException("第 ${index + 1} 个参数需要是字符串")

    /**
     * 取第一个参数当字节序列。
     *
     * 约定 JS 侧传**字节数组**（`number[]`，每个元素 0..255），而不是字符串：
     * 字符串到字节的编码由插件自己决定（UTF-8 还是 GBK），宿主替它决定
     * 会在遇到非 ASCII 密码时产生难以察觉的不一致。
     */
    private fun bytesArg(args: PluginJsonArray, index: Int): ByteArray {
        val arr = args.optJSONArray(index)
            ?: throw IllegalArgumentException("第 ${index + 1} 个参数需要是字节数组")
        val out = ByteArray(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            require(v in 0..255) { "字节值必须在 0..255，实际第 $i 项为 $v" }
            out[i] = v.toByte()
        }
        return out
    }

    /** 取第一个参数当字符串。空串合法（空 base64 解出零字节），缺参数不算。 */
    private fun stringArg(args: PluginJsonArray, index: Int): String =
        args.optStringOrNull(index)
            ?: throw IllegalArgumentException("第 ${index + 1} 个参数需要是字符串")

    private fun md5Hex(input: ByteArray): String =
        digest("MD5", input).joinToString("") { "%02X".format(it) }

    private fun sha1Base64(input: ByteArray): String =
        base64Encode(digest("SHA-1", input))

    private fun sha256Base64(input: ByteArray): String =
        base64Encode(digest("SHA-256", input))

    private fun sha256Hex(input: ByteArray): String =
        digest("SHA-256", input).joinToString("") { "%02x".format(it) }

    private fun digest(algorithm: String, input: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(input)

    /**
     * 标准 Base64 编码（带 `=` 补齐，非 URL-safe）。
     *
     * 用 JDK 的 [java.util.Base64] 而不是 `android.util.Base64`：
     * 前者是纯 JDK API，桌面端与 Android 端可以共用同一份实现。
     */
    private fun base64Encode(input: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(input)

    /**
     * 标准 Base64 解码，返回**字节数组**。
     *
     * **错误输入返回空数组而不是抛异常**：插件从网络拿到的值可能被截断，
     * 让它自己判断「解出来是空的」比让宿主抛错更好处理。
     */
    private fun base64Decode(input: String): ByteArray =
        runCatching { java.util.Base64.getDecoder().decode(input) }
            .getOrDefault(ByteArray(0))

    /**
     * URL-safe Base64 编码，**不带 `=` 补齐**（RFC 4648 §5）。
     *
     * 与上游 `toBase64Url` 的输出必须逐字节一致：Noise 协议把公钥、密文
     * 放进取值参数时，补齐符会与 URL 编码/签名逻辑互相干扰，所以不带补齐。
     */
    private fun base64UrlEncode(input: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input)

    /**
     * URL-safe Base64 解码。
     *
     * JDK 的解码器同时接受带补齐与不带补齐的输入，因此对端多给几个 `=`
     * 也能解开；非法字符按 [base64Decode] 的约定返回空数组。
     */
    private fun base64UrlDecode(input: String): ByteArray =
        runCatching { java.util.Base64.getUrlDecoder().decode(input) }
            .getOrDefault(ByteArray(0))

    /**
     * 安全随机字节。
     *
     * 用共享的 [SecureRandom] 实例：它本身线程安全，反复 `new` 反而会因
     * 重新播种而拖慢首次调用（Android 上尤其明显）。
     */
    private fun randomBytes(count: Int): String =
        bytesToJson(ByteArray(count).also { secureRandom.nextBytes(it) })

    /** 字节 → JS 的 `number[]` JSON 文本（紧凑写法，不带空格）。 */
    private fun bytesToJson(bytes: ByteArray): String =
        bytes.joinToString(separator = ",", prefix = "[", postfix = "]") {
            (it.toInt() and 0xff).toString()
        }

    private companion object {
        /**
         * `randomBytes` 的上界。
         *
         * 密码场景里最长的是握手 payload（约 1KB 级），4096 足够；
         * 设上下界是为了挡住「长度写成变量后一路放大到内存耗尽」这类低级错误。
         */
        const val MAX_RANDOM_BYTES = 4096

        val secureRandom: SecureRandom = SecureRandom()
    }
}

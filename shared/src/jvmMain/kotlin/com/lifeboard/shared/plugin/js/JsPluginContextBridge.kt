package com.lifeboard.shared.plugin.js

import com.lifeboard.jsengine.HostFunctionDispatcher
import com.lifeboard.jsengine.JsRuntime
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.shared.log.AppLogger
import com.lifeboard.shared.plugin.json.PluginJson
import com.lifeboard.shared.plugin.json.PluginJsonArray
import com.lifeboard.shared.plugin.json.PluginJsonObject
import kotlinx.coroutines.runBlocking

/**
 * 把 [PluginContext] 的能力暴露给 JS 的 `ctx.*`。
 *
 * ## 支持的宿主函数
 *
 * | JS 侧 | 行为 |
 * |---|---|
 * | `ctx.credentials.get(slot)` | 读凭据，返回字符串或 null |
 * | `ctx.credentials.put(slot, value)` | 写凭据 |
 * | `ctx.credentials.remove(slot)` | 删单个槽位 |
 * | `ctx.credentials.clear()` | 清空本插件凭据 |
 * | `ctx.files.read(path)` | 读插件私有目录下的文本文件 |
 * | `ctx.files.write(path, text)` | 写文本文件（覆盖） |
 * | `ctx.files.readBase64(path)` | 读二进制文件，返回 base64 字符串 |
 * | `ctx.files.writeBase64(path, b64)` | 把 base64 解码后写成二进制文件 |
 * | `ctx.files.list()` | 列出私有目录下的文件名 |
 * | `ctx.recentMetrics(key, days)` | 读本插件已落库指标，返回 `[{date,value}]` |
 * | `ctx.reportRawRecords(key, records, date?)` | 上报原始条目 |
 * | `ctx.reportMetricQueried(key, date?)` | 标记「查过但没数据」 |
 * | `ctx.log.d/i/w/e(tag, msg)` | 写宿主日志 |
 * | `ctx.pluginId()` | 取本插件 id |
 *
 * ## 为什么 context 是提供器而不是构造参数
 *
 * [PluginContext] 由宿主在每次调用插件方法时构造（它绑定采集日期等运行期状态），
 * 而桥接对象在插件加载时就注册给 JS 了。用提供器让桥接始终拿到「本次调用」的
 * 上下文，避免缓存一个过期的实例。
 *
 * ## 关于网络
 *
 * `ctx.http.*` 单独由 [JsHttpBridge] 提供。它需要真正的异步回调
 * （OkHttp → `resolvePromise`），与这里的同步能力分开实现，
 * 两类桥接通过 [CompositeHostDispatcher] 合并后一起注册。
 *
 * ## 线程模型
 *
 * 宿主函数是从 QuickJS 的调用栈里同步调进来的，因此内部一律用
 * [runBlocking] 等待挂起操作。这是安全的：调用方已经在插件专用单线程上，
 * 不会阻塞主线程。
 */
class JsPluginContextBridge(
    private val contextProvider: () -> PluginContext,
    private val pluginIdProvider: () -> String,
) : HostFunctionDispatcher {

    private val pluginContext: PluginContext get() = contextProvider()

    private val pluginId: String get() = pluginIdProvider()

    /** 当前生效的采集日期，由 [JsSourcePlugin] 在 fetch 前设置。 */
    @Volatile
    private var collectDate: String = ""

    /** 设置默认采集日期。由 [JsSourcePlugin] 在调用 fetch 前设置。 */
    fun setCollectDate(date: String) {
        collectDate = date
    }

    /** 注册到 JS 的函数名清单，交给 [JsRuntime.registerHostFunctions]。 */
    fun functionNames(): List<String> = listOf(
        "ctx.credentials.get",
        "ctx.credentials.put",
        "ctx.credentials.remove",
        "ctx.credentials.clear",
        "ctx.files.read",
        "ctx.files.write",
        "ctx.files.readBase64",
        "ctx.files.writeBase64",
        "ctx.files.list",
        "ctx.recentMetrics",
        "ctx.reportRawRecords",
        "ctx.reportMetricQueried",
        "ctx.log.d",
        "ctx.log.i",
        "ctx.log.w",
        "ctx.log.e",
        "ctx.pluginId",
    )

    /**
     * 把本对象注册给运行时。调用后 JS 侧即可使用 `ctx.*`。
     *
     * 注意：一个运行时只能挂一个 dispatcher，重复调用会替换掉之前的。
     * 需要同时挂 HTTP 能力时用一个组合 dispatcher 包起来，
     * 见 [CompositeHostDispatcher]。
     */
    fun install(runtime: JsRuntime) {
        runtime.registerHostFunctions(functionNames(), this)
    }

    override fun invoke(name: String, argsJson: String): String {
        val args = runCatching { PluginJsonArray(argsJson) }.getOrElse { PluginJsonArray() }
        return try {
            when (name) {
                "ctx.credentials.get" -> credentialGet(args.stringOrNull(0))
                "ctx.credentials.put" -> credentialPut(args.stringOrNull(0), args.stringOrNull(1))
                "ctx.credentials.remove" -> credentialRemove(args.stringOrNull(0))
                "ctx.credentials.clear" -> credentialClear()
                "ctx.files.read" -> fileRead(args.stringOrNull(0))
                "ctx.files.write" -> fileWrite(args.stringOrNull(0), args.stringOrNull(1))
                "ctx.files.readBase64" -> fileReadBase64(args.stringOrNull(0))
                "ctx.files.writeBase64" -> fileWriteBase64(
                    args.stringOrNull(0),
                    args.stringOrNull(1),
                )
                "ctx.files.list" -> fileList()
                "ctx.recentMetrics" -> recentMetrics(args.stringOrNull(0), args.optInt(1, 7))
                "ctx.reportRawRecords" -> reportRawRecords(args)
                "ctx.reportMetricQueried" -> reportMetricQueried(args)
                "ctx.log.d", "ctx.log.i", "ctx.log.w", "ctx.log.e" ->
                    log(name.substringAfterLast('.'), args.stringOrNull(0), args.stringOrNull(1))
                "ctx.pluginId" -> PluginJson.quote(pluginId)
                else -> HostFunctionDispatcher.error("unknown host function: $name")
            }
        } catch (e: Exception) {
            // 宿主函数内部异常不能让 JS 侧收到裸异常信息（可能含路径等细节），
            // 只回传一句可读原因
            HostFunctionDispatcher.error(e.message ?: "host call failed")
        }
    }

    // ---------- 凭据 ----------

    private fun credentialGet(slot: String?): String {
        if (slot.isNullOrBlank()) {
            return HostFunctionDispatcher.error("credentials.get 需要 slot")
        }
        val value = runBlocking { pluginContext.credentials.get(slot) }
        return if (value == null) "null" else PluginJson.quote(value)
    }

    private fun credentialPut(slot: String?, value: String?): String {
        if (slot.isNullOrBlank()) {
            return HostFunctionDispatcher.error("credentials.put 需要 slot")
        }
        runBlocking { pluginContext.credentials.put(slot, value.orEmpty()) }
        return "null"
    }

    private fun credentialRemove(slot: String?): String {
        if (slot.isNullOrBlank()) {
            return HostFunctionDispatcher.error("credentials.remove 需要 slot")
        }
        runBlocking { pluginContext.credentials.remove(slot) }
        return "null"
    }

    private fun credentialClear(): String {
        runBlocking { pluginContext.credentials.clear() }
        return "null"
    }

    // ---------- 文件 ----------

    /**
     * 读插件私有目录下的文本文件。
     *
     * 只允许相对路径：插件拿不到 `..` 之类的越权入口，
     * 目录穿越在接口层就不可表达。
     */
    private fun fileRead(path: String?): String {
        val file = safeFile(path) ?: return "null"
        if (!file.exists() || !file.isFile) return "null"
        return PluginJson.quote(file.readText())
    }

    private fun fileWrite(path: String?, text: String?): String {
        val file = safeFile(path)
            ?: return HostFunctionDispatcher.error("files.write 需要合法的相对路径")
        file.parentFile?.mkdirs()
        file.writeText(text.orEmpty())
        return "null"
    }

    private fun fileList(): String {
        val names = java.io.File(pluginContext.filesDirPath).listFiles()?.map { it.name }.orEmpty()
        return PluginJsonArray(names).toString()
    }

    /**
     * 读二进制文件，以 base64 字符串交给 JS。
     *
     * ## 为什么文本读法不够
     *
     * [fileRead] 用 `readText()`，遇到非 UTF-8 字节会替换成 `�`，**不可逆**。
     * 插件要缓存一张图片、一段 protobuf、或一份签名后的二进制包时，
     * 用文本读法拿到的内容已经损坏，而且不会报错——写回去才发现坏了。
     *
     * ## 为什么是 base64 而不是 Uint8Array
     *
     * 宿主函数与 JS 之间只传 JSON 文本，二进制过不去。
     * base64 的膨胀（+33%）对插件级文件（几十 KB）可以接受；
     * 真有几十 MB 的二进制需求，那已经超出插件该做的事。
     *
     * 用法：`ctx.codec.base64Decode(ctx.files.readBase64('a.bin'))` 得到字节数组。
     */
    private fun fileReadBase64(path: String?): String {
        val file = safeFile(path) ?: return "null"
        if (!file.exists() || !file.isFile) return "null"
        return PluginJson.quote(java.util.Base64.getEncoder().encodeToString(file.readBytes()))
    }

    /**
     * 把 base64 字符串解码后写进二进制文件。
     *
     * 解码失败必须报错而不是写空文件：写一个空文件会让下次读取拿到
     * 「文件存在但内容为空」，看起来像数据丢了，而不是调用参数错了。
     */
    private fun fileWriteBase64(path: String?, base64: String?): String {
        val file = safeFile(path)
            ?: return HostFunctionDispatcher.error("files.writeBase64 需要合法的相对路径")
        val bytes = try {
            java.util.Base64.getDecoder().decode(base64.orEmpty())
        } catch (e: IllegalArgumentException) {
            return HostFunctionDispatcher.error("files.writeBase64 的 base64 内容非法")
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return "null"
    }

    /**
     * 把相对路径解析到插件私有目录内。
     *
     * 规范化后必须仍在插件私有目录之下，否则返回 null——
     * 这是防止插件读写宿主其它文件的关键检查。
     */
    private fun safeFile(path: String?): java.io.File? {
        if (path.isNullOrBlank()) return null
        val root = java.io.File(pluginContext.filesDirPath).canonicalFile
        val target = java.io.File(root, path).canonicalFile
        val rootPath = root.path + java.io.File.separator
        return if (target.path == root.path || target.path.startsWith(rootPath)) target else null
    }

    // ---------- 历史指标 ----------

    private fun recentMetrics(metricKey: String?, days: Int): String {
        if (metricKey.isNullOrBlank()) {
            return HostFunctionDispatcher.error("recentMetrics 需要 metricKey")
        }
        val samples = runBlocking {
            pluginContext.recentMetrics(metricKey, days.coerceAtLeast(1))
        }
        val arr = PluginJsonArray()
        samples.forEach { s ->
            arr.put(
                PluginJsonObject().apply {
                    put("date", s.date)
                    put("value", s.value.toString())
                }
            )
        }
        return arr.toString()
    }

    // ---------- 原始记录 ----------

    private fun reportRawRecords(args: PluginJsonArray): String {
        val metricKey = args.stringOrNull(0)
            ?: return HostFunctionDispatcher.error("reportRawRecords 需要 metricKey")

        val records = JsTypeConverter.toRawRecords(args.optJSONArray(1))
        // 日期缺省用本次采集日；显式传入则以其为准（按日回补历史时用）
        val date = args.stringOrNull(2).orEmpty().ifBlank { collectDate }

        runBlocking { pluginContext.reportRawRecords(metricKey, records, date) }
        return "null"
    }

    private fun reportMetricQueried(args: PluginJsonArray): String {
        val metricKey = args.stringOrNull(0)
            ?: return HostFunctionDispatcher.error("reportMetricQueried 需要 metricKey")
        val date = args.stringOrNull(1).orEmpty().ifBlank { collectDate }
        runBlocking { pluginContext.reportMetricQueried(metricKey, date) }
        return "null"
    }

    // ---------- 日志 ----------

    private fun log(level: String, tag: String?, message: String?): String {
        // 插件日志统一加前缀，便于在宿主日志里筛出是哪个插件说的话
        val safeTag = tag.orEmpty().ifBlank { "JsPlugin" }
        val line = "[$safeTag] ${message.orEmpty()}"
        AppLogBridge.log(level, pluginId, line)
        return "null"
    }

    // ---------- 工具 ----------

    private fun PluginJsonArray.stringOrNull(index: Int): String? {
        if (index < 0 || index >= length()) return null
        if (isNull(index)) return null
        return optStringOrNull(index)?.ifBlank { null }
    }
}

/**
 * 把 JS 插件的日志接到宿主日志体系。
 *
 * 单独一层是为了让 [JsPluginContextBridge] 能在纯 JVM 单测里跑，
 * 不必拉起 Android 的 Log。
 */
object AppLogBridge {
    fun log(level: String, pluginId: String, message: String) {
        val line = "[$pluginId] $message"
        when (level.lowercase()) {
            "d" -> AppLogger.debug(line)
            "i" -> AppLogger.info(line)
            "w" -> AppLogger.warn(line)
            else -> AppLogger.error(line)
        }
    }
}

package com.lifeboard.shared.plugin.js

import com.lifeboard.plugin.api.CredentialSpec
import com.lifeboard.plugin.api.MetricSpec
import com.lifeboard.shared.plugin.PluginPackageInfo
import com.lifeboard.shared.plugin.json.PluginJsonObject
import java.security.MessageDigest

/**
 * `.jsplugin` 包里的 `manifest.json`。
 *
 * ## 与 APK manifest 的区别
 *
 * APK 的元数据由 Android 系统解析，包名、版本、签名都由系统保证；
 * JS 包是自己解 ZIP 读 JSON，**没有任何系统级保证**，因此：
 *
 * 1. 每个字段都要校验，缺失或格式错就拒绝加载，不能带着半份元数据继续跑；
 * 2. 签名是「包内文件的 SHA-256」，只能防传输被改，防不了作者本人作恶——
 *    信任弹窗的措辞必须体现这个强度差异，不能和 APK 签名混为一谈。
 *
 * ## 身份
 *
 * [packageName][id] 用 manifest 的 `id`。它同时是落库用的 pluginId，
 * 因此必须满足 `SourceRef` 的约束：不含 `:`。
 */
data class JsPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiVersion: Int,
    val entry: String,
    val metrics: List<MetricSpec>,
    val credentialSpec: CredentialSpec,
    /** manifest 自称的内容哈希（发布者计算），可为空表示未声明 */
    val declaredHash: String? = null,
    /**
     * 插件要信任的自签 CA 的 SHA-256 指纹（小写十六进制）。
     *
     * 为空表示只用系统信任库。声明了它，插件就能连自签证书的局域网服务
     * （例如电脑上的 DSH 移动网关），而其余证书一律拒绝——见 [_pluginTls]。
     */
    val pinnedCaSha256: String? = null,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val defaultTabName: String? = null,
    val tabIcon: String? = null,
    /**
     * 插件声明的生命周期策略；缺省 [PluginLifecycle.ON_DEMAND]。
     *
     * 这是**声明**，宿主还会按常驻名额、全局预算和回收开关决定实际授予的策略，
     * 见 [PluginRuntimePolicy]。
     */
    val lifecycle: PluginLifecycle = PluginLifecycle.DEFAULT,
    /**
     * manifest 是否**显式声明**了 `lifecycle`。
     *
     * 必须与 [lifecycle] 分开：缺省值也是 `onDemand`，但"没写"和"写了 onDemand"
     * 的信任程度完全不同。没写的旧包可能依赖模块级状态活着，自动回收会让它
     * 凭空丢状态——所以只有显式声明的才允许被自动回收。
     */
    val lifecycleDeclared: Boolean = false,
    /**
     * 按需释放的空闲阈值（秒），仅 `onDemand` 生效。
     *
     * 缺省 300，合法范围 [60, 3600]；越界整数夹取并告警。
     */
    val idleTimeoutSec: Int = PluginLifecycle.DEFAULT_IDLE_SECONDS,
    /**
     * 插件显式声明的必需宿主能力。
     *
     * 空表示不校验（缺省行为）；非空时每一项都必须被宿主满足，否则在执行入口前拒载。
     * 名称精确匹配，如 `runtime.lifecycle`、`contract.loginIntent`、`ctx.files.write`。
     */
    val requires: List<String> = emptyList(),
) {

    /**
     * 转换成通用的包元数据，供加载器与信任体系使用。
     *
     * @param actualHash 解包后实际计算出的内容哈希。
     *   作为签名指纹使用——这样「同一插件换了内容」就会被识别成换签名，
     *   触发重新信任，与 APK 的「换证书必须重新信任」语义对齐
     */
    fun toPackageInfo(actualHash: String): JsPluginPackageInfo = JsPluginPackageInfo(
        packageName = id,
        versionCode = versionCode,
        versionName = version,
        signatureHashes = if (actualHash.isBlank()) emptyList() else listOf(actualHash.uppercase()),
        manifest = this,
    )

    companion object {
        /** manifest.json 在包内的固定位置 */
        const val MANIFEST_NAME = "manifest.json"

        /** 入口脚本默认名 */
        const val DEFAULT_ENTRY = "main.js"

        /**
         * `pinnedCaSha256` 的哨兵值：指纹由运行时提供。
         *
         * 用于「一个包分发给多个用户」：每台电脑的 dsh-mobile 都会生成自己的 CA，
         * 指纹无法预先写进包。插件改为从用户粘贴的配对链接里取 `instance=`
         * （网关把它设为 CA 指纹），再交给 `ctx.http.trustCa` 比对。
         *
         * 安全强度不降低：指纹仍由用户从自己电脑取得、带外交给 App，
         * 中间人伪造的证书算不出这个指纹。
         */
        const val PINNED_CA_RUNTIME = "runtime"

        /**
         * 解析 manifest。
         *
         * @return 成功返回 manifest；任何必需字段缺失或非法返回 null，
         *   由调用方转成「包损坏」的展示原因。
         *   需要具体原因时用 [parseDetailed]。
         */
        fun parse(json: String): JsPluginManifest? =
            (parseDetailed(json) as? ParseResult.Success)?.manifest

        /**
         * 解析 manifest 并给出失败原因。
         *
         * 与 [parse] 的区别只有「能不能说清为什么失败」：加载器与安装校验
         * 需要把原因展示给用户，而 `null` 只能汇成一句笼统的格式错误。
         */
        fun parseDetailed(json: String): ParseResult {            val o = runCatching { PluginJsonObject(json) }.getOrNull()
                ?: return ParseResult.Failure("manifest.json 不是合法 JSON")

            val id = o.optString("id").trim()
            // 落库 key 用 `PLUGIN_{id}:{metricKey}`，id 里出现 `:` 会让 key 无法反解
            if (id.isBlank()) return ParseResult.Failure("manifest 缺少 id")
            if (id.contains(':')) return ParseResult.Failure("manifest id 不能包含 ':'（当前为 \"$id\"）")

            val name = o.optString("name").ifBlank { id }
            val version = o.optString("version").ifBlank { "0.0.0" }
            val versionCode = o.optLong("versionCode", 0L).coerceAtLeast(0L)
            val apiVersion = o.optInt("apiVersion", 0)
            val entry = o.optString("entry").ifBlank { DEFAULT_ENTRY }

            // 入口路径同样不能越出包根目录
            if (entry.startsWith("/") || entry.contains("..")) {
                return ParseResult.Failure("入口路径非法：\"$entry\"（不能是绝对路径或含 ..）")
            }

            // 生命周期：未知值明确拒载，不静默退化成默认值——
            // 否则作者写了拼错的 resident 会以为生效了，实际被按需回收
            val lifecycleRaw = o.optString("lifecycle")
            val lifecycleDeclared = lifecycleRaw.isNotBlank()
            val lifecycle = PluginLifecycle.parse(lifecycleRaw)
                ?: return ParseResult.Failure(
                    "manifest lifecycle 取值非法：\"${o.optString("lifecycle")}\"，" +
                        "应为 onDemand / sessionScoped / resident"
                )
            val rawIdle = o.optInt("idleTimeoutSec", PluginLifecycle.DEFAULT_IDLE_SECONDS)
            val idleTimeoutSec = rawIdle.coerceIn(
                PluginLifecycle.MIN_IDLE_SECONDS,
                PluginLifecycle.MAX_IDLE_SECONDS,
            )

            // 指纹只接受规范格式：写错必须让包加载失败，
            // 而不是静默退化成「信任系统证书」——那会让插件以为连上了自签服务
            val pinnedCa = o.optString("pinnedCaSha256").ifBlank { null }?.let { raw ->
                if (raw.equals(PINNED_CA_RUNTIME, ignoreCase = true)) {
                    PINNED_CA_RUNTIME
                } else {
                    PluginTls.normalizeFingerprint(raw)
                        ?: return ParseResult.Failure("pinnedCaSha256 不是合法的 SHA-256 指纹：\"$raw\"")
                }
            }

            return ParseResult.Success(
                JsPluginManifest(
                    id = id,
                    name = name,
                    version = version,
                    versionCode = versionCode,
                    apiVersion = apiVersion,
                    entry = entry,
                    metrics = JsTypeConverter.toMetricSpecs(o.optJSONArray("metrics")),
                    credentialSpec = JsTypeConverter.toCredentialSpec(o.optJSONArray("credentialSpec")),
                    declaredHash = o.optString("sha256").ifBlank { null },
                    pinnedCaSha256 = pinnedCa,
                    description = o.optString("description").ifBlank { null },
                    author = o.optString("author").ifBlank { null },
                    homepage = o.optString("homepage").ifBlank { null },
                    defaultTabName = o.optString("defaultTabName").ifBlank { null },
                    tabIcon = o.optString("tabIcon").ifBlank { null },
                    lifecycle = lifecycle,
                    lifecycleDeclared = lifecycleDeclared,
                    idleTimeoutSec = idleTimeoutSec,
                    // 缺省不校验；显式声明才逐项校验。空数组与缺字段等价
                    requires = o.optJSONArray("requires")
                        ?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optStringOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
                            }.distinct()
                        }
                        .orEmpty(),
                )
            )
        }

        /**
         * 计算一组文件的内容哈希。
         *
         * 按文件名排序后逐个喂进 SHA-256，保证同一份内容在任何解压顺序下
         * 得到相同结果。**不含 manifest.json 自身**——否则改一行描述文字
         * 就会让签名变化、要求用户重新信任，那是过度惩罚。
         */
        fun computeContentHash(files: Map<String, ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            files.entries
                .filter { it.key != MANIFEST_NAME }
                .sortedBy { it.key }
                .forEach { (path, bytes) ->
                    digest.update(path.toByteArray())
                    digest.update(0)
                    digest.update(bytes)
                    digest.update(0)
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * [JsPluginManifest.parseDetailed] 的结果。
 *
 * 放在顶层而不是 companion 里：嵌套在 companion 内的 sealed 接口
 * 在 `when` 中不会被当作可穷尽类型，会导致加载器的分支判定报错。
 */
sealed interface ParseResult {
    data class Success(val manifest: JsPluginManifest) : ParseResult
    data class Failure(val reason: String) : ParseResult
}

/**
 * JS 插件的包元数据。
 *
 * [manifest] 保留原始声明，加载器建 `SourcePlugin` 时要用到入口路径与指标。
 */
data class JsPluginPackageInfo(
    override val packageName: String,
    override val versionCode: Long,
    override val versionName: String,
    override val signatureHashes: List<String>,
    val manifest: JsPluginManifest,
) : PluginPackageInfo {

    /** 指纹来自包内文件内容哈希，内容变化属于正常更新而非可疑替换 */
    override val isContentAddressed: Boolean get() = true
}

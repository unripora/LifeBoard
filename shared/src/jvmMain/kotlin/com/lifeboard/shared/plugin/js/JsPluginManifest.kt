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
         *   由调用方转成「包损坏」的展示原因
         */
        fun parse(json: String): JsPluginManifest? {
            val o = runCatching { PluginJsonObject(json) }.getOrNull() ?: return null

            val id = o.optString("id").trim()
            // 落库 key 用 `PLUGIN_{id}:{metricKey}`，id 里出现 `:` 会让 key 无法反解
            if (id.isBlank() || id.contains(':')) return null

            val name = o.optString("name").ifBlank { id }
            val version = o.optString("version").ifBlank { "0.0.0" }
            val versionCode = o.optLong("versionCode", 0L).coerceAtLeast(0L)
            val apiVersion = o.optInt("apiVersion", 0)
            val entry = o.optString("entry").ifBlank { DEFAULT_ENTRY }

            // 入口路径同样不能越出包根目录
            if (entry.startsWith("/") || entry.contains("..")) return null

            return JsPluginManifest(
                id = id,
                name = name,
                version = version,
                versionCode = versionCode,
                apiVersion = apiVersion,
                entry = entry,
                metrics = JsTypeConverter.toMetricSpecs(o.optJSONArray("metrics")),
                credentialSpec = JsTypeConverter.toCredentialSpec(o.optJSONArray("credentialSpec")),
                declaredHash = o.optString("sha256").ifBlank { null },
                // 只接受规范格式：写错的指纹必须让包加载失败，
                // 而不是静默退化成「信任系统证书」——那会让插件以为连上了自签服务
                //
                // `runtime` 是哨兵值：指纹不在包里写死，由插件在配对时从
                // 用户粘贴的配对链接（`instance=` 段）取，见 [PINNED_CA_RUNTIME]。
                // 这是「一个包分发给多个用户」的唯一可行方式——每台电脑的
                // dsh-mobile 首次启动都会生成自己的 CA，指纹不可能预先知道。
                pinnedCaSha256 = o.optString("pinnedCaSha256")
                    .ifBlank { null }
                    ?.let { raw ->
                        if (raw.equals(PINNED_CA_RUNTIME, ignoreCase = true)) PINNED_CA_RUNTIME
                        else PluginTls.normalizeFingerprint(raw) ?: return null
                    },
                description = o.optString("description").ifBlank { null },
                author = o.optString("author").ifBlank { null },
                homepage = o.optString("homepage").ifBlank { null },
                defaultTabName = o.optString("defaultTabName").ifBlank { null },
                tabIcon = o.optString("tabIcon").ifBlank { null },
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

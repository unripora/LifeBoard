package com.lifeboard.shared.plugin

import com.lifeboard.shared.platform.SettingsStore

/**
 * 用户信任的插件身份与已安装版本记录。
 *
 * ## 为什么基于 [SettingsStore] 而不是自建存储
 *
 * 这里只有几十条「包名|签名」字符串，没有任何关系查询需求。
 * 早期 Android 侧直接用了 `SharedPreferences`，那会导致桌面端必须再写一份；
 * 改成注入平台无关的键值存储后，两端共用同一套读写逻辑与 key 约定。
 *
 * 记录内容**不含凭据、不含插件数据**，被读到也只能知道「用户装过哪些插件」。
 *
 * ## 为什么集合要序列化成字符串
 *
 * [SettingsStore] 的契约里只有标量（意图是让它能被任何平台的键值存储实现）。
 * 为这里单独加 `getStringSet` 会让桌面实现被迫处理「集合怎么存」这件事，
 * 而它对这个键之外的一切都无关。所以用 `\n` 分隔——包名/哈希里不可能出现换行，
 * 这个约定是安全的，且不需要转义。
 */
class PluginTrustStore(private val settings: SettingsStore) {

    companion object {
        private const val KEY_TRUSTED = "plugin_trust.trusted_identities"
        private const val KEY_INSTALLED = "plugin_trust.installed"
        private const val VERSION_PREFIX = "plugin_trust.version_"
        private const val SIGNATURE_PREFIX = "plugin_trust.signature_"

        /** 集合序列化分隔符。包名与十六进制哈希都不含换行，无需转义 */
        private const val SEPARATOR = "\n"

        fun encodeSet(values: Set<String>): String =
            values.filter { it.isNotEmpty() }.sorted().joinToString(SEPARATOR)

        fun decodeSet(raw: String): Set<String> =
            if (raw.isEmpty()) emptySet() else raw.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    /**
     * 读出全部信任状态。
     *
     * ## 版本记录为什么必须有一份包名索引
     *
     * 早期实现靠遍历 `SharedPreferences.all` 过滤前缀来发现「装过哪些包」。
     * [SettingsStore] 没有 `all`，所以额外维护一个已安装包名列表 `plugin_trust.installed`。
     *
     * 两份数据（索引 + 逐条记录）理论上会不一致，但这里的不一致是**自愈**的：
     * 读记录时若签名键缺失按空集合处理，写记录时总是同时更新索引。
     * 真正的风险只有「索引里有、记录里没有」，那条包名的 [InstalledVersion]
     * 会被跳过（versionCode 取 0 视为未记录），不会误判成降级。
     */
    fun load(): TrustState {
        val trusted = decodeSet(settings.getString(KEY_TRUSTED, ""))

        val installedNames = decodeSet(settings.getString(KEY_INSTALLED, ""))
        val versions = installedNames.mapNotNull { packageName ->
            val versionKey = VERSION_PREFIX + packageName
            if (!settings.contains(versionKey)) return@mapNotNull null
            val versionCode = settings.getLong(versionKey, 0L)
            val signatures = decodeSet(settings.getString(SIGNATURE_PREFIX + packageName, ""))
            packageName to InstalledVersion(versionCode, signatures)
        }.toMap()

        return TrustState(trusted, versions)
    }

    /**
     * 信任一个身份。
     *
     * 只记身份（包名 + 签名集合），不记版本：同一签名发新版不需要再信任一次。
     */
    fun trust(identity: PluginIdentity) {
        val current = decodeSet(settings.getString(KEY_TRUSTED, "")).toMutableSet()
        current += identity.key
        settings.putString(KEY_TRUSTED, encodeSet(current))
    }

    /** 撤销信任。撤销后该插件立刻停止调度（调用方负责重新扫描） */
    fun revoke(identity: PluginIdentity) {
        val current = decodeSet(settings.getString(KEY_TRUSTED, "")).toMutableSet()
        current -= identity.key
        settings.putString(KEY_TRUSTED, encodeSet(current))
    }

    /** 撤销某个包名下的全部信任（含历史签名） */
    fun revokePackage(packageName: String) {
        val current = decodeSet(settings.getString(KEY_TRUSTED, ""))
            .filterNot { it.startsWith("$packageName|") }
            .toSet()
        settings.putString(KEY_TRUSTED, encodeSet(current))
    }

    /** 记录已安装版本，用于下次启动时防降级与校验签名一致性 */
    fun recordInstalled(info: PluginPackageInfo) {
        settings.putLong(VERSION_PREFIX + info.packageName, info.versionCode)
        settings.putString(
            SIGNATURE_PREFIX + info.packageName,
            encodeSet(info.signatureHashes.toSet()),
        )
        val installed = decodeSet(settings.getString(KEY_INSTALLED, "")).toMutableSet()
        installed += info.packageName
        settings.putString(KEY_INSTALLED, encodeSet(installed))
    }

    /** 清除安装记录，用于卸载后不留残迹 */
    fun forgetInstalled(packageName: String) {
        settings.remove(VERSION_PREFIX + packageName)
        settings.remove(SIGNATURE_PREFIX + packageName)
        val installed = decodeSet(settings.getString(KEY_INSTALLED, "")).toMutableSet()
        installed -= packageName
        settings.putString(KEY_INSTALLED, encodeSet(installed))
    }

    /** 撤销全部信任，对应 Mihon 的 `revokeAll()` */
    fun revokeAll() {
        // 只清信任，不清已安装版本记录：后者是防降级的依据，
        // 跟着一起清会让用户在「撤销全部信任」后能把旧包装回去
        settings.putString(KEY_TRUSTED, "")
    }
}

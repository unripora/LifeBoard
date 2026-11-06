package com.lifeboard.server.plugin

import android.content.Context

/**
 * 用户信任的插件身份与已安装版本记录。
 *
 * 存 `SharedPreferences` 而不是建 Room 表：这里只有几十条「包名|签名」字符串，
 * 没有任何关系查询需求，为它加一张表和一次 schema 迁移不划算。
 * 业务数据（`metric` / `source_health`）才需要 Room。
 *
 * 记录内容**不含凭据、不含插件数据**，被读到也只能知道「用户装过哪些插件」。
 */
object PluginTrustStore {

    private const val FILE = "plugin_trust"
    private const val KEY_TRUSTED = "trusted_identities"
    private const val VERSION_PREFIX = "version_"
    private const val SIGNATURE_PREFIX = "signature_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 读出全部信任状态。
     *
     * 已安装版本记录按包名逐条存，包名作为 key 后缀。
     * 用 `all` 过滤前缀，避免再维护一份「装过哪些包」的索引——两份索引迟早会不一致。
     */
    fun load(context: Context): TrustState {
        val prefs = prefs(context)
        val trusted = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty().toSet()

        val versions = prefs.all
            .filterKeys { it.startsWith(VERSION_PREFIX) }
            .mapNotNull { (key, value) ->
                val packageName = key.removePrefix(VERSION_PREFIX)
                val versionCode = (value as? Long) ?: return@mapNotNull null
                val signatures = prefs.getStringSet(SIGNATURE_PREFIX + packageName, emptySet())
                    .orEmpty()
                    .toSet()
                packageName to InstalledVersion(versionCode, signatures)
            }
            .toMap()

        return TrustState(trusted, versions)
    }

    /**
     * 信任一个身份。
     *
     * 只记身份（包名 + 签名集合），不记版本：同一签名发新版不需要再信任一次。
     */
    fun trust(context: Context, identity: PluginIdentity) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty().toMutableSet()
        current += identity.key
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
    }

    /** 撤销信任。撤销后该插件立刻停止调度，见 `PluginManager.revokeTrust` */
    fun revoke(context: Context, identity: PluginIdentity) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty().toMutableSet()
        current -= identity.key
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
    }

    /** 撤销某个包名下的全部信任（含历史签名） */
    fun revokePackage(context: Context, packageName: String) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty()
            .filterNot { it.startsWith("$packageName|") }
            .toSet()
        prefs.edit().putStringSet(KEY_TRUSTED, current).apply()
    }

    /** 记录已安装版本，用于下次启动时防降级与校验签名一致性 */
    fun recordInstalled(context: Context, info: PluginArchiveInfo) {
        prefs(context).edit()
            .putLong(VERSION_PREFIX + info.packageName, info.versionCode)
            .putStringSet(SIGNATURE_PREFIX + info.packageName, info.signatureHashes.toSet())
            .apply()
    }

    /** 清除安装记录，用于卸载后不留残迹 */
    fun forgetInstalled(context: Context, packageName: String) {
        prefs(context).edit()
            .remove(VERSION_PREFIX + packageName)
            .remove(SIGNATURE_PREFIX + packageName)
            .apply()
    }

    /** 撤销全部信任，对应 Mihon 的 `revokeAll()` */
    fun revokeAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

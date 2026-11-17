package com.lifeboard.server.plugin

import android.content.Context
import com.lifeboard.plugin.api.CredentialStore
import com.lifeboard.plugin.api.PluginContext
import com.lifeboard.plugin.api.RawRecord
import com.lifeboard.server.db.DatabaseProviders
import com.lifeboard.shared.plugin.host.HostPluginContext as SharedHostPluginContext
import com.lifeboard.shared.plugin.host.RawRecordSink
import java.io.File

/**
 * [PluginContext] 的 Android 实现。
 *
 * 隔离规则、窗口计算、指标归并全部在 [SharedHostPluginContext] 里，两端共用；
 * 这一层只负责注入三样平台相关的东西：凭据加密存储、数据库连接、原始条目落库。
 *
 * 保留这个壳而不是让调用方直接 new 共享类，是因为既有调用点遍布
 * `PluginDataSource` / `PluginManager` / 各 ViewModel，且都写成
 * `HostPluginContext(pluginId, context)`——换成共享实现要动几十处，
 * 而它们关心的只是「拿到一个绑定好 pluginId 的上下文」。
 */
class HostPluginContext(
    pluginId: String,
    context: Context,
) : PluginContext {

    private val appContext = context.applicationContext

    private val delegate = SharedHostPluginContext(
        pluginId = pluginId,
        filesDirPathProvider = {
            pluginDataDir(appContext, pluginId).also { if (!it.exists()) it.mkdirs() }.absolutePath
        },
        credentialStoreFactory = { id -> vaultStore(id) },
        metricStore = DatabaseProviders.metricStore(appContext),
        rawRecordSink = AndroidRawRecordSink(appContext),
    )

    /**
     * 直接实现 [PluginContext] 而不是再暴露一个 `.context` getter。
     *
     * 既有调用点都写成 `plugin.login(HostPluginContext(pluginId, ctx), inputs)`，
     * 若这里只是一个普通类，那几十处都得改成 `.context`；而它们真正关心的
     * 只是「拿到一个绑定好 pluginId 的上下文」。实现接口就是零改动。
     */
    override val pluginId: String get() = delegate.pluginId

    override val credentials: CredentialStore get() = delegate.credentials

    override val filesDirPath: String get() = delegate.filesDirPath

    override suspend fun recentMetrics(metricKey: String, days: Int) =
        delegate.recentMetrics(metricKey, days)

    override suspend fun allRecentMetrics(days: Int, metrics: List<String>) =
        delegate.allRecentMetrics(days, metrics)

    override suspend fun reportRawRecords(
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) = delegate.reportRawRecords(metricKey, records, date)

    override suspend fun reportMetricQueried(metricKey: String, date: String) =
        delegate.reportMetricQueried(metricKey, date)

    /** 宿主内部使用的 [File] 视图；契约层只暴露 [filesDirPath] 字符串 */
    val filesDir: File get() = File(filesDirPath)

    fun bindCollectDate(date: String) = delegate.bindCollectDate(date)

    private fun vaultStore(pluginId: String): CredentialStore = VaultCredentialStore(pluginId, appContext)

    companion object {
        /**
         * 插件私有目录：`filesDir/plugins/{pluginId}/data`。
         *
         * 用 pluginId 拼路径前必须校验：pluginId 来自插件 manifest，
         * 属于外部输入，含 `../` 就能写到别的插件或宿主目录里去。
         */
        fun pluginDataDir(context: Context, pluginId: String): File {
            require(SharedHostPluginContext.isSafePluginId(pluginId)) { "非法 pluginId：$pluginId" }
            return File(File(File(context.applicationContext.filesDir, "plugins"), pluginId), "data")
        }

        fun isSafePluginId(pluginId: String): Boolean =
            SharedHostPluginContext.isSafePluginId(pluginId)
    }
}

/** 把 [CredentialVault] 收窄成「只认本插件」的存取口 */
private class VaultCredentialStore(
    private val pluginId: String,
    private val context: Context,
) : CredentialStore {

    override suspend fun get(slot: String): String? =
        CredentialVault.read(context, pluginId, slot)

    override suspend fun put(slot: String, value: String) {
        CredentialVault.write(context, pluginId, slot, value)
    }

    override suspend fun remove(slot: String) {
        CredentialVault.clearSlot(context, pluginId, slot)
    }

    override suspend fun clear() {
        CredentialVault.clearPlugin(context, pluginId)
    }
}

/** 原始条目落库走 Android 的 [RawRecordCollector] */
private class AndroidRawRecordSink(private val context: Context) : RawRecordSink {
    override suspend fun stage(
        pluginId: String,
        metricKey: String,
        records: List<RawRecord>,
        date: String,
    ) {
        RawRecordCollector.stage(context, pluginId, metricKey, records, date)
    }

    override suspend fun markQueried(pluginId: String, metricKey: String, date: String) {
        RawRecordCollector.markQueried(context, pluginId, metricKey, date)
    }
}

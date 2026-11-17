package com.lifeboard.server.platform

import android.content.Context
import android.net.Uri
import com.lifeboard.server.plugin.CredentialVault
import com.lifeboard.server.plugin.InstallOutcome
import com.lifeboard.server.plugin.PluginManager
import com.lifeboard.server.plugin.js.JsPluginLoader
import com.lifeboard.shared.platform.PendingPlugin
import com.lifeboard.shared.platform.PickedFile
import com.lifeboard.shared.platform.PluginHost
import com.lifeboard.shared.platform.PluginInstallResult
import com.lifeboard.shared.platform.PluginList
import com.lifeboard.shared.platform.PluginSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [PluginHost] 的 Android 实现，委托给 [PluginManager]。
 *
 * 这一层**只做类型翻译**：
 * `PluginManager` 的方法都以 `Context` 为第一个参数（它是 Android 专属的），
 * 返回值里带 `File`、`PluginIdentity`、`PluginPackageInfo`。
 * 契约层不能出现这些，所以在这里把 `Context` 绑死、
 * 把返回值压成「界面需要看到的那几个字段」。
 *
 * 逻辑一行都不搬：安装校验、签名比对、格式分派
 * 全在 `PluginManager` 及其协作者里。把它们复制一份到本类，
 * 会出现「改了这边忘了那边」的双份真相。
 *
 * @param overwriteRequest 覆盖安装需要的暂存文件与落盘名。
 *   `PluginManager.install` 在冲突时返回 `stagedFile` 与 `incomingArchiveName`，
 *   契约里不能带 `File`，只能由本类暂存；这里用字段记住上一次的冲突，
 *   供后续 [confirmOverwrite] / [discardStaged] 使用。
 */
class AndroidPluginHost(
    private val context: Context,
) : PluginHost {

    private val appContext = context.applicationContext

    /** 最近一次冲突安装留下的暂存现场，非冲突时为 null */
    private var staged: StagedOverwrite? = null

    private data class StagedOverwrite(
        val stagedFile: File,
        val archiveName: String,
        val packageName: String,
    )

    /**
     * 引擎可用性。
     *
     * 必须走 [JsPluginLoader] 背后的同一份判断，不能在这里自己 `loadLibrary`：
     * 那样会真的触发一次 dlopen，把「查询」变成「加载」。
     */
    override fun engineAvailable(): Boolean = JsPluginLoader.engineAvailable()

    override fun snapshots(): StateFlow<PluginList> = PluginManager.snapshots(appContext)

    override suspend fun refresh(): List<PluginSummary> = withContext(Dispatchers.IO) {
        PluginManager.refresh(appContext)
            .map { loaded ->
                PluginSummary(
                    pluginId = loaded.pluginId,
                    displayName = loaded.plugin.displayName,
                    packageName = loaded.archive.packageName,
                    versionName = loaded.archive.versionName,
                    archiveName = loaded.archiveName,
                    metricCount = loaded.metrics.size,
                    trusted = loaded.trusted,
                    signatureHashes = loaded.archive.signatureHashes.toList(),
                    // 有槽位就意味着这个插件曾经登录过（槽位是在登录时按需创建的），
                    // 空表示不需要账号
                    needsLogin = CredentialVault.slots(appContext, loaded.pluginId).isNotEmpty(),
                )
            }
    }

    override suspend fun pending(): List<PendingPlugin> = withContext(Dispatchers.IO) {
        PluginManager.pending(context).map { entry ->
            PendingPlugin(
                archiveName = entry.archiveName,
                packageName = entry.packageName,
                versionName = entry.versionName,
                unsigned = entry.unsigned,
                reason = entry.reason,
                signatureHashes = entry.identity?.signatureHashes?.toList().orEmpty(),
            )
        }
    }

    override suspend fun broken(): List<PendingPlugin> = withContext(Dispatchers.IO) {
        PluginManager.broken(context).map { entry ->
            PendingPlugin(
                archiveName = entry.archiveName,
                packageName = entry.packageName,
                versionName = "",
                unsigned = false,
                reason = entry.reason,
                signatureHashes = emptyList(),
            )
        }
    }

    override suspend fun install(file: PickedFile): PluginInstallResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.uri)
        when (val outcome = PluginManager.install(appContext, uri)) {
            is InstallOutcome.Installed -> PluginInstallResult.Installed(
                packageName = outcome.info.packageName,
                versionName = outcome.info.versionName,
            )

            is InstallOutcome.Conflict -> {
                // 记住现场：确认覆盖时要把 stagedFile 原样交回 PluginManager，
                // 契约层不能带 File，只能暂存在这
                staged = StagedOverwrite(
                    stagedFile = outcome.stagedFile,
                    archiveName = outcome.incomingArchiveName,
                    packageName = outcome.incoming.packageName,
                )
                PluginInstallResult.Conflict(
                    packageName = outcome.incoming.packageName,
                    incomingVersionName = outcome.incoming.versionName,
                    existingVersionName = outcome.existing.versionName,
                )
            }

            is InstallOutcome.Failed -> PluginInstallResult.Failed(outcome.reason)
        }
    }

    override suspend fun confirmOverwrite(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // 按包名比对而不是直接用字段：中间可能插了一次别的安装，
        // 用错暂存文件会把 A 插件的包落成 B 插件的名字
        val current = staged?.takeIf { it.packageName == packageName } ?: return@withContext false
        val ok = PluginManager.confirmOverwrite(appContext, current.stagedFile, current.archiveName)
        staged = null
        ok
    }

    override suspend fun discardStaged() {
        staged?.let { runCatching { PluginManager.discardInstall(it.stagedFile) } }
        staged = null
    }

    override suspend fun trust(packageName: String, signatureHashes: Set<String>) =
        withContext(Dispatchers.IO) {
            // 契约层传的是「包名 + 指纹」这种平台无关组合，
            // PluginManager 要的是它自己的 PluginIdentity。
            // 逐字段比对而不是只比包名：指纹变了意味着这不是原来那个插件，
            // 拿旧身份去信任会让一个同名的陌生包被放行
            val identity = PluginManager.pending(context)
                .firstOrNull { it.packageName == packageName }
                ?.identity
                ?.takeIf { it.signatureHashes == signatureHashes }
                ?: return@withContext
            PluginManager.trust(appContext, identity)
        }

    override suspend fun revokeTrust(pluginId: String) = withContext(Dispatchers.IO) {
        PluginManager.revokeTrust(appContext, pluginId)
    }

    override suspend fun uninstall(pluginId: String) = withContext(Dispatchers.IO) {
        PluginManager.uninstall(appContext, pluginId)
    }

    override suspend fun uninstallPending(archiveName: String) = withContext(Dispatchers.IO) {
        PluginManager.uninstallPending(appContext, archiveName)
    }

    override suspend fun clearCredentials(pluginId: String): Int =
        CredentialVault.clearPlugin(appContext, pluginId)
}

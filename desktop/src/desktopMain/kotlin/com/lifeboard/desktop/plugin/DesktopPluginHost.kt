package com.lifeboard.desktop.plugin

import com.lifeboard.shared.platform.PendingPlugin
import com.lifeboard.shared.platform.PickedFile
import com.lifeboard.shared.platform.PluginHost
import com.lifeboard.shared.platform.PluginInstallResult
import com.lifeboard.shared.platform.PluginList
import com.lifeboard.shared.platform.PluginSummary
import com.lifeboard.shared.plugin.PluginIdentity
import com.lifeboard.shared.plugin.js.JsPluginInstallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 桌面端 [PluginHost] 实现，全部委托给 [DesktopPluginRepository]。
 *
 * ## 这一层只做翻译
 *
 * 仓库层用的是共享层的类型（`JsPluginPackageInfo`、`PendingJsPlugin`），
 * 契约层用的是界面能直接渲染的类型（[PluginSummary]、[PendingPlugin]）。
 * 两者的差别只是字段挑选与扁平化，不含任何判断——判断放在仓库层，
 * 一处写好，Android 与桌面各翻译一次。
 */
class DesktopPluginHost(
    private val repository: DesktopPluginRepository,
) : PluginHost {

    /**
     * 桌面侧恒为 true。
     *
     * dll 随 jar 分发，不存在 Android 那种「只编了部分 ABI」的问题。
     * 真加载不出来属于构建事故，让它在构造运行时照常抛错更有助于定位。
     */
    override fun engineAvailable(): Boolean = true

    /**
     * 直接把仓库的推送流交出去。
     *
     * 这里不多包一层 `map`：仓库推的就是契约类型 [PluginList]，
     * 再翻译一次只会让「监听触发的刷新」和「手动刷新的结果」走上两条不同的路径。
     */
    override fun snapshots(): StateFlow<PluginList> = repository.snapshots()

    override suspend fun refresh(): List<PluginSummary> = withContext(Dispatchers.IO) {
        repository.refresh().map { it.toSummary() }
    }

    override suspend fun pending(): List<PendingPlugin> = withContext(Dispatchers.IO) {
        repository.pendingPlugins().map {
            PendingPlugin(
                archiveName = it.archiveName,
                packageName = it.packageName,
                versionName = it.versionName,
                unsigned = it.unsigned,
                reason = it.reason,
                // 无内容指纹时不给信任入口，签名列表也为空——界面据此隐藏「信任」按钮
                signatureHashes = it.identity?.signatureHashes?.sorted().orEmpty(),
            )
        }
    }

    override suspend fun broken(): List<PendingPlugin> = withContext(Dispatchers.IO) {
        repository.brokenPlugins().map {
            PendingPlugin(
                archiveName = it.archiveName,
                // 解析失败时拿不到包名，用包文件名顶替：用户至少能看出是哪个文件
                packageName = it.archiveName.substringBeforeLast('.'),
                versionName = "",
                unsigned = false,
                reason = it.reason,
                signatureHashes = emptyList(),
            )
        }
    }

    /**
     * 安装。
     *
     * 契约给的 [PickedFile.uri] 在桌面端就是绝对路径（见 `SwingFilePicker`）。
     * 文件不存在时直接判失败而不是抛异常：用户可能在选完文件后、点安装前
     * 把文件删了或挪走了，这属于可预期情形。
     */
    override suspend fun install(file: PickedFile): PluginInstallResult =
        withContext(Dispatchers.IO) {
            val source = File(file.uri)
            if (!source.isFile) return@withContext PluginInstallResult.Failed("找不到所选文件：${file.displayName}")

            when (val outcome = repository.install(source)) {
                is JsPluginInstallService.Outcome.Installed ->
                    PluginInstallResult.Installed(
                        packageName = outcome.info.packageName,
                        versionName = outcome.info.versionName,
                    )
                is JsPluginInstallService.Outcome.Conflict ->
                    PluginInstallResult.Conflict(
                        packageName = outcome.incoming.packageName,
                        incomingVersionName = outcome.incoming.versionName,
                        existingVersionName = outcome.existing.versionName,
                    )
                is JsPluginInstallService.Outcome.Failed ->
                    PluginInstallResult.Failed(outcome.reason)
            }
        }

    override suspend fun confirmOverwrite(packageName: String): Boolean =
        withContext(Dispatchers.IO) { repository.confirmOverwrite() }

    override suspend fun discardStaged() {
        withContext(Dispatchers.IO) { repository.discardStaged() }
    }

    override suspend fun trust(packageName: String, signatureHashes: Set<String>) {
        withContext(Dispatchers.IO) {
            repository.trust(PluginIdentity(packageName, signatureHashes))
        }
    }

    override suspend fun revokeTrust(pluginId: String) {
        withContext(Dispatchers.IO) { repository.revokeTrust(pluginId) }
    }

    override suspend fun uninstall(pluginId: String) {
        withContext(Dispatchers.IO) { repository.uninstall(pluginId) }
    }

    override suspend fun uninstallPending(archiveName: String) {
        withContext(Dispatchers.IO) { repository.uninstallArchive(archiveName) }
    }

    override suspend fun clearCredentials(pluginId: String): Int =
        withContext(Dispatchers.IO) { repository.clearCredentials(pluginId) }

    private fun LoadedEntry.toSummary(): PluginSummary = PluginSummary(
        pluginId = pluginId,
        displayName = displayName,
        packageName = packageInfo.packageName,
        versionName = packageInfo.versionName,
        archiveName = archiveName,
        metricCount = metrics.size,
        trusted = trusted,
        signatureHashes = packageInfo.signatureHashes.sorted(),
        needsLogin = needsLogin,
    )
}

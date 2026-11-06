package com.lifeboard.app.ui.plugin

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.server.db.LifeBoardDatabase
import com.lifeboard.server.plugin.CredentialVault
import com.lifeboard.server.plugin.LoadedPlugin
import com.lifeboard.server.plugin.PluginInstallService
import com.lifeboard.server.plugin.PluginLoader
import com.lifeboard.server.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件页面上的一行「已装可用」插件。
 *
 * @property healthMessage 来自 `source_health` 表，空串表示还没采集过
 */
data class PluginRow(
    val pluginId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val archiveName: String,
    val metricCount: Int,
    val trusted: Boolean,
    val healthy: Boolean?,
    val healthMessage: String,
    val signatureHashes: List<String>,
)

/** 待信任 / 无签名，用户在这里做决定 */
data class PendingRow(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val unsigned: Boolean,
    val reason: String,
    val signatureHashes: List<String>,
)

/** 加载失败 */
data class BrokenRow(
    val archiveName: String,
    val packageName: String,
    val reason: String,
)

/**
 * 一次待确认的安装：用户已经选完文件，还没决定信不信。
 *
 * @property signatureHashes 要让用户看清的就是这个——换签名 = 换了作者
 */
data class PendingInstall(
    val archiveName: String,
    val packageName: String,
    val versionName: String,
    val signatureHashes: List<String>,
    val isOverwrite: Boolean,
    /** 仅覆盖安装时有值，用于在确认框里告诉用户「从哪个版本换成哪个版本」 */
    val existingVersionName: String = "",
)

data class PluginUiState(
    val loading: Boolean = true,
    val installed: List<PluginRow> = emptyList(),
    val pending: List<PendingRow> = emptyList(),
    val broken: List<BrokenRow> = emptyList(),
    /** 有值表示要弹信任确认框 */
    val pendingInstall: PendingInstall? = null,
    /** 一次性提示，展示后由 UI 调 [PluginViewModel.consumeMessage] 清掉 */
    val message: String? = null,
)

/**
 * 插件页状态。
 *
 * 健康度读 `source_health` 表而不是现调插件的 `health()`：
 * 后者会真的发网络请求，打开一次页面就同步一次代价太大，
 * 而且用户点开列表只是想看看装了什么，不是要触发采集。
 */
class PluginViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    /** 覆盖安装时暂存的文件，等用户确认 */
    private var staged: java.io.File? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val ctx = getApplication<Application>()
            val rows = withContext(Dispatchers.IO) {
                runCatching { PluginManager.refresh(ctx) }
                buildRows(ctx)
            }
            _state.value = _state.value.copy(loading = false, installed = rows.first, pending = rows.second, broken = rows.third)
        }
    }

    private suspend fun buildRows(ctx: Application): Triple<List<PluginRow>, List<PendingRow>, List<BrokenRow>> {
        val healthBySource = LifeBoardDatabase.get(ctx).sourceHealthDao()
            .getAll()
            .associateBy { it.sourceType }

        val installed = PluginManager.loaded().map { plugin ->
            toRow(plugin, healthBySource)
        }

        val pending = PluginManager.pending().map {
            PendingRow(
                archiveName = it.archiveName,
                packageName = it.packageName,
                versionName = it.versionName,
                unsigned = it.unsigned,
                reason = it.reason,
                signatureHashes = it.identity?.signatureHashes?.sorted().orEmpty(),
            )
        }

        val broken = PluginManager.broken().map {
            BrokenRow(it.archiveName, it.packageName, it.reason)
        }

        return Triple(installed, pending, broken)
    }

    private fun toRow(
        plugin: LoadedPlugin,
        health: Map<String, com.lifeboard.server.db.SourceHealthEntity>,
    ): PluginRow {
        // 一个插件有多个指标、每个指标一条健康记录：任意一条不健康就认为有问题
        val records = plugin.metrics.keys.mapNotNull {
            health["PLUGIN_${plugin.pluginId}:$it"]
        }
        val healthy = when {
            records.isEmpty() -> null
            else -> records.all { it.healthy }
        }
        val message = records.firstOrNull { !it.healthy }?.message.orEmpty()

        return PluginRow(
            pluginId = plugin.pluginId,
            displayName = plugin.plugin.displayName,
            packageName = plugin.archive.packageName,
            versionName = plugin.archive.versionName,
            archiveName = plugin.archiveName,
            metricCount = plugin.metrics.size,
            trusted = plugin.trusted,
            healthy = healthy,
            healthMessage = message,
            signatureHashes = plugin.archive.signatureHashes.sorted(),
        )
    }

    /**
     * 用户选完文件。
     *
     * 安装**不等于**信任：这里只是把包装进私有目录，然后停下来问用户。
     */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            when (val outcome = PluginInstallService.installFromUri(ctx, uri)) {
                is PluginInstallService.InstallOutcome.Installed -> {
                    // 装好了但还没信任，直接进「待处理」：让用户在那里看签名再决定
                    refresh()
                    _state.value = _state.value.copy(
                        message = "已导入 ${outcome.info.packageName}，确认签名后才会启用",
                    )
                }
                is PluginInstallService.InstallOutcome.Conflict -> {
                    staged = outcome.stagedFile
                    _state.value = _state.value.copy(
                        pendingInstall = PendingInstall(
                            archiveName = outcome.incoming.packageName + ".ext",
                            packageName = outcome.incoming.packageName,
                            versionName = outcome.incoming.versionName,
                            signatureHashes = outcome.incoming.signatureHashes.sorted(),
                            isOverwrite = true,
                            existingVersionName = outcome.existing.versionName,
                        ),
                    )
                }
                is PluginInstallService.InstallOutcome.Failed -> {
                    _state.value = _state.value.copy(message = outcome.reason)
                }
            }
        }
    }

    fun confirmOverwrite() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val file = staged
            val info = _state.value.pendingInstall
            if (file == null || info == null) return@launch
            val ok = PluginInstallService.confirmOverwrite(ctx, file, info.archiveName)
            staged = null
            _state.value = _state.value.copy(
                pendingInstall = null,
                message = if (ok) "已覆盖安装 ${info.packageName}" else "写入失败，请检查存储空间",
            )
            refresh()
        }
    }

    fun cancelOverwrite() {
        staged?.let { PluginInstallService.discard(it) }
        staged = null
        _state.value = _state.value.copy(pendingInstall = null)
    }

    /** 信任一个待处理插件 */
    fun trust(row: PendingRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // 信任的就是这套签名：身份由包名 + 签名集合构成，换签名必须重新信任
            val identity = com.lifeboard.server.plugin.PluginIdentity(
                packageName = row.packageName,
                signatureHashes = row.signatureHashes.toSet(),
            )
            withContext(Dispatchers.IO) { PluginManager.trust(ctx, identity) }
            _state.value = _state.value.copy(message = "已信任 ${row.packageName}")
            refresh()
        }
    }

    /** 撤销信任：停止调度，但保留包与数据 */
    fun revokeTrust(row: PluginRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.revokeTrust(ctx, row.pluginId) }
            _state.value = _state.value.copy(message = "已停止使用 ${row.displayName}")
            refresh()
        }
    }

    /** 卸载：删包并撤销信任，保留凭据与私有数据（用户可能只是想重装） */
    fun uninstall(row: PluginRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.uninstall(ctx, row.pluginId) }
            _state.value = _state.value.copy(message = "已卸载 ${row.displayName}")
            refresh()
        }
    }

    /** 删除一个还没信任的包 */
    fun deletePending(row: PendingRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.uninstallPending(ctx, row.archiveName) }
            _state.value = _state.value.copy(message = "已删除 ${row.archiveName}")
            refresh()
        }
    }

    /** 清除某插件的全部凭据，需要重新登录 */
    fun clearCredentials(row: PluginRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val count = withContext(Dispatchers.IO) { CredentialVault.clearPlugin(ctx, row.pluginId) }
            _state.value = _state.value.copy(
                message = if (count > 0) "已清除 ${row.displayName} 的 $count 项凭据" else "该插件没有已保存的凭据",
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

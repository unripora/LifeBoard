package com.lifeboard.app.ui.plugin

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.plugin.api.AuthState
import com.lifeboard.plugin.api.InputField
import com.lifeboard.plugin.api.LoginResult
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
    /** 插件是否需要登录才能出数（有凭据槽位就认为需要） */
    val needsLogin: Boolean = false,
    /** 登录态文案，如「已登录：138****1234」；未登录时为空 */
    val loginText: String = "",
    /** 是否需要用户去登录（未登录或已过期） */
    val needsLoginAction: Boolean = false,
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
    /** 有值表示正在登录，UI 应展示插件要求填的表单 */
    val login: LoginUi? = null,
    /** 一次性提示，展示后由 UI 调 [PluginViewModel.consumeMessage] 清掉 */
    val message: String? = null,
)

/**
 * 插件登录进行中的状态。
 *
 * [fields] 由插件给出，宿主不知道这些字段是什么含义——可能是账号密码，
 * 也可能是验证码。UI 只负责按 [InputField.secret] 决定要不要掩码。
 *
 * @property busy 上一轮提交还在进行，此时禁用「提交」按钮
 * @property error 上一轮的失败原因，显示在表单上方
 */
data class LoginUi(
    val pluginId: String,
    val displayName: String,
    val prompt: String,
    val fields: List<com.lifeboard.plugin.api.InputField>,
    val captchaImage: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** 用户在表单里已经输入的值，键是字段名 */
    val values: Map<String, String> = emptyMap(),
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
            toRow(ctx, plugin, healthBySource)
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

    private suspend fun toRow(
        ctx: Application,
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

        // 有凭据槽位就认为需要登录。为空说明插件不需要账号，
        // 此时不该在界面上给它一个点了没用的登录按钮
        val needsLogin = plugin.plugin.credentialSpec.slots.isNotEmpty()
        val auth = if (needsLogin && plugin.trusted) {
            runCatching { PluginManager.authState(ctx, plugin.pluginId) }
                .getOrElse { AuthState.LoggedOut }
        } else {
            null
        }

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
            needsLogin = needsLogin,
            loginText = authText(auth),
            needsLoginAction = needsLogin && (auth == null ||
                auth is AuthState.LoggedOut ||
                auth is AuthState.Expired),
        )
    }

    private fun authText(auth: AuthState?): String = when (auth) {
        null -> ""
        is AuthState.LoggedIn ->
            auth.accountName?.let { "已登录：$it" } ?: "已登录"
        is AuthState.Expired -> auth.message ?: "登录已过期"
        AuthState.LoggedOut -> "未登录"
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

    // ---- 登录 ----

    /**
     * 开始登录一个插件。
     *
     * 宿主不知道这个插件要什么字段，所以先空手问一轮：插件会返回
     * [LoginResult.NeedInput] 告诉我们「要填什么」，也可能是直接成功（凭据还在）。
     */
    fun startLogin(row: PluginRow) {
        submitLogin(row.pluginId, row.displayName, emptyMap())
    }

    /** 用户改了表单里某个字段的值 */
    fun updateLoginValue(name: String, value: String) {
        val current = _state.value.login ?: return
        _state.value = _state.value.copy(
            login = current.copy(values = current.values + (name to value), error = null),
        )
    }

    /** 提交当前表单 */
    fun submitLoginForm() {
        val current = _state.value.login ?: return
        submitLogin(current.pluginId, current.displayName, current.values)
    }

    fun cancelLogin() {
        _state.value = _state.value.copy(login = null)
    }

    /**
     * 发起一轮登录，并处理插件返回的三种结果。
     *
     * [inputs] 为空表示这是第一轮，插件应该告诉我们它要什么字段。
     */
    private fun submitLogin(
        pluginId: String,
        displayName: String,
        inputs: Map<String, String>,
    ) {
        viewModelScope.launch {
            // 已有表单时先标记 busy：登录要发网络请求，不能让人连点提交
            _state.value.login?.let { current ->
                _state.value = _state.value.copy(login = current.copy(busy = true, error = null))
            }

            val ctx = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                PluginManager.login(ctx, pluginId, inputs)
            }

            when (result) {
                is LoginResult.Success -> {
                    _state.value = _state.value.copy(login = null, message = "$displayName 登录成功")
                    refresh()
                }

                is LoginResult.NeedInput -> {
                    // 保留用户已填的值：验证码这轮要重填，但账号密码不该让他再输一遍
                    val kept = _state.value.login?.values.orEmpty()
                        .filterKeys { name -> result.fields.any { it.name == name } }
                    _state.value = _state.value.copy(
                        login = LoginUi(
                            pluginId = pluginId,
                            displayName = displayName,
                            prompt = result.prompt,
                            fields = result.fields,
                            captchaImage = result.captchaImage,
                            values = kept,
                        ),
                    )
                }

                is LoginResult.Failure -> {
                    val form = _state.value.login
                    _state.value = if (form == null) {
                        // 第一轮就失败：此时没有表单可显示错误，只能走提示条，
                        // 否则用户点了登录却什么都看不到
                        _state.value.copy(message = "$displayName 登录失败：${result.message}")
                    } else {
                        _state.value.copy(login = form.copy(busy = false, error = result.message))
                    }
                }
            }
        }
    }

    /** 登出：让插件清掉自己名下的凭据 */
    fun logout(row: PluginRow) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { PluginManager.logout(ctx, row.pluginId) }
            _state.value = _state.value.copy(message = "已退出 ${row.displayName}")
            refresh()
        }
    }
}

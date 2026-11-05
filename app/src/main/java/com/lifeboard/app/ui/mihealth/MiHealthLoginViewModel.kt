package com.lifeboard.app.ui.mihealth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeboard.server.mihealth.MiHealthAuth
import com.lifeboard.server.mihealth.MiHealthCredentialStore
import com.lifeboard.server.mihealth.MiHealthCredentials
import com.lifeboard.server.mihealth.MiHealthDiagnostics
import com.lifeboard.server.mihealth.MiHealthOverview
import com.lifeboard.server.mihealth.MiHealthOverviewReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登录页当前处于哪个阶段 */
enum class LoginStage {
    /** 输账号密码 */
    CREDENTIALS,

    /** 输图片验证码 */
    CAPTCHA,

    /** 输二次验证码（短信/邮箱） */
    TWO_FACTOR,

    /** 粘贴已有凭据导入 */
    IMPORT,

    /** 登录成功 */
    DONE,
}

data class MiHealthLoginState(
    val stage: LoginStage = LoginStage.CREDENTIALS,
    val account: String = "",
    val password: String = "",
    val captchaInput: String = "",
    val twoFactorInput: String = "",
    /** 图片验证码地址，[LoginStage.CAPTCHA] 时展示 */
    val captchaUrl: String = "",
    /** 二次验证渠道说明，如「短信 138****1234」 */
    val twoFactorHint: String = "",
    /** 导入表单里粘贴的凭据文本 */
    val importText: String = "",
    /** 导入时用户自己填的显示名称（可为空） */
    val importName: String = "",
    val busy: Boolean = false,
    val error: String = "",
    /** 是否已登录（进入页面时读取） */
    val loggedIn: Boolean = false,
) {
    /** 账号密码是否填全，决定登录按钮可点 */
    val canSubmitCredentials: Boolean
        get() = account.isNotBlank() && password.isNotBlank() && !busy
}

/**
 * 小米账号登录页的状态管理。
 *
 * 登录是多阶段交互（账号密码 → 可能验证码 → 可能二次验证），
 * 每次需要用户补输入时，[MiHealthAuth] 返回对应的 Need* 结果，
 * 这里把阶段切过去，等用户填完再继续。
 */
class MiHealthLoginViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        // 已登录就直接进「已登录」阶段：之前只设置了 loggedIn 标记却仍停在
        // CREDENTIALS，导致明明登录过、每次进页面还是看到登录表单
        run {
            val cred = MiHealthCredentialStore.load(app)
            val loggedIn = cred?.isValid() == true
            MiHealthLoginState(
                stage = if (loggedIn) LoginStage.DONE else LoginStage.CREDENTIALS,
                loggedIn = loggedIn,
                // 已存的凭据带着上次登录的账号，进页面就能显示「登的是谁」
                account = cred?.account.orEmpty(),
            )
        },
    )
    val state: StateFlow<MiHealthLoginState> = _state.asStateFlow()

    private val auth by lazy { MiHealthAuth() }

    /** 2FA 会话，必须原样带回去，否则会话断开 */
    private var pending2FA: MiHealthAuth.PendingTwoFactor? = null

    /** 验证码会话，取图与提交必须共用，否则验证码对不上 */
    private var pendingCaptcha: MiHealthAuth.PendingCaptcha? = null

    /** 已登录时展示的数据概览 */
    private val _overview = MutableStateFlow<MiHealthOverview?>(null)
    val overview: StateFlow<MiHealthOverview?> = _overview.asStateFlow()

    private val _overviewLoading = MutableStateFlow(false)
    val overviewLoading: StateFlow<Boolean> = _overviewLoading.asStateFlow()

    /**
     * 数据读取诊断。
     *
     * 这台设备 logcat 完全为空，接口失败时只能靠界面显示来定位。
     * 内容包含凭据字段长度和每个数据族的请求结果。
     */
    private val _diag = MutableStateFlow("")
    val diag: StateFlow<String> = _diag.asStateFlow()

    fun runDiagnostics() {
        viewModelScope.launch {
            _diag.value = withContext(Dispatchers.IO) {
                MiHealthDiagnostics.run(getApplication())
            }
        }
    }

    init {
        // 进页面时若已登录，直接拉一次数据，用户不用再点任何按钮
        if (_state.value.loggedIn) refreshOverview()
    }

    /** 拉取小米健康的今日数据概览 */
    fun refreshOverview() {
        if (_overviewLoading.value) return
        _overviewLoading.value = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                MiHealthOverviewReader(getApplication()).load()
            }
            _overview.value = data
            _overviewLoading.value = false
        }
    }

    fun onAccountChange(v: String) = _state.update { it.copy(account = v, error = "") }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = "") }
    fun onCaptchaChange(v: String) = _state.update { it.copy(captchaInput = v, error = "") }
    fun onTwoFactorChange(v: String) = _state.update { it.copy(twoFactorInput = v, error = "") }

    /** 提交账号密码 */
    fun submitCredentials() {
        val s = _state.value
        if (!s.canSubmitCredentials) return
        _state.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            val result = auth.login(s.account, s.password)
            handle(result)
        }
    }

    /** 提交图片验证码 */
    fun submitCaptcha() {
        val s = _state.value
        val pending = pendingCaptcha ?: return
        if (s.captchaInput.isBlank() || s.busy) return
        _state.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            val result = auth.loginWithCaptcha(pending, s.captchaInput.trim())
            handle(result)
        }
    }

    /** 提交二次验证码 */
    fun submitTwoFactor() {
        val s = _state.value
        val pending = pending2FA ?: return
        if (s.twoFactorInput.isBlank() || s.busy) return
        _state.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            val result = auth.complete2FA(pending, s.twoFactorInput.trim())
            handle(result)
        }
    }

    /** 验证码看不清，用同一个会话换一张图（不能重跑账号密码，那会换掉会话） */
    fun refreshCaptcha() {
        val pending = pendingCaptcha
        if (pending == null) {
            // 会话丢了（比如页面被回收），只能退回密码步骤重新触发
            _state.update { it.copy(error = "验证码会话已失效，请重新登录") }
            submitCredentials()
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = "", captchaInput = "") }
        viewModelScope.launch {
            val url = auth.refreshCaptcha(pending)
            _state.update {
                it.copy(
                    busy = false,
                    captchaUrl = url,
                    error = if (url.isBlank()) "换图失败，请重试" else "",
                )
            }
        }
    }

    /** 退出登录 */
    fun logout() {
        MiHealthCredentialStore.clear(getApplication())
        _state.value = MiHealthLoginState(loggedIn = false)
    }

    /** 切到「粘贴凭据导入」表单 */
    fun openImport() = _state.update {
        it.copy(stage = LoginStage.IMPORT, error = "", importText = "", importName = "")
    }

    /** 退回账号密码表单 */
    fun cancelImport() = _state.update {
        it.copy(stage = LoginStage.CREDENTIALS, error = "", importText = "", importName = "")
    }

    fun onImportTextChange(v: String) = _state.update { it.copy(importText = v, error = "") }

    fun onImportNameChange(v: String) = _state.update { it.copy(importName = v, error = "") }

    /**
     * 解析并导入粘贴的凭据。
     *
     * 支持两种格式：
     * 1. JSON（`config.json` 那种，字段名 `ssecurity` / `service_token` / `cuser_id`）
     * 2. 每行一个值，顺序固定：ssecurity / serviceToken / cUserId / passToken
     *
     * 这条入口存在的理由：小米对密码登录有风控和短信次数限制，
     * 用户手上已经有可用凭据时不该被卡在登录页。
     */
    fun submitImport() {
        val s = _state.value
        if (s.importText.isBlank() || s.busy) return
        _state.update { it.copy(busy = true, error = "") }
        viewModelScope.launch {
            val cred = withContext(Dispatchers.IO) {
                MiHealthImport.parse(s.importText, s.importName)
            }
            if (cred == null) {
                _state.update { it.copy(busy = false, error = "格式不对：需要 ssecurity 与 serviceToken") }
                return@launch
            }
            val saved = save(cred)
            _state.update {
                it.copy(
                    stage = if (saved) LoginStage.DONE else LoginStage.IMPORT,
                    busy = false,
                    loggedIn = saved,
                    account = cred.account,
                    importText = "",
                    importName = "",
                    error = if (saved) "" else "凭据保存失败，请重试",
                )
            }
            if (saved) refreshOverview()
        }
    }

    private fun handle(result: MiHealthAuth.LoginResult) {
        when (result) {
            is MiHealthAuth.LoginResult.Success -> {
                val saved = save(result.credentials)
                _state.update {
                    it.copy(
                        stage = LoginStage.DONE,
                        busy = false,
                        // 以落盘结果为准：写不进 Keystore 时不能显示「已登录」，
                        // 否则退出页面后主界面又会变回未登录
                        loggedIn = saved,
                        account = result.credentials.account,
                        password = "",
                        captchaInput = "",
                        twoFactorInput = "",
                        error = if (saved) "" else "凭据保存失败，请重试",
                    )
                }
                if (saved) refreshOverview()
            }

            is MiHealthAuth.LoginResult.NeedCaptcha -> {
                pendingCaptcha = result.pending
                _state.update {
                    it.copy(
                        stage = LoginStage.CAPTCHA,
                        busy = false,
                        captchaUrl = result.captchaUrl,
                        captchaInput = "",
                        error = if (result.captchaUrl.isBlank()) "验证码图片加载失败，请点「换一张」" else "",
                    )
                }
            }

            is MiHealthAuth.LoginResult.Need2FA -> {
                pending2FA = result.pending
                _state.update {
                    it.copy(
                        stage = LoginStage.TWO_FACTOR,
                        busy = false,
                        twoFactorHint = result.hint.ifBlank {
                            if (result.method == "Email") "邮箱验证码" else "短信验证码"
                        },
                        twoFactorInput = "",
                        error = "",
                    )
                }
            }

            is MiHealthAuth.LoginResult.Failure -> _state.update {
                it.copy(busy = false, error = result.message)
            }
        }
    }

    private fun save(credentials: MiHealthCredentials): Boolean =
        MiHealthCredentialStore.save(getApplication(), credentials)
}

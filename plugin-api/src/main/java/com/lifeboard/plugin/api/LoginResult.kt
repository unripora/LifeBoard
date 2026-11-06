package com.lifeboard.plugin.api

/**
 * 登录结果。
 *
 * 登录**流程**由插件自己走，宿主不参与，只知道「进行到哪一步」以及「需要用户填什么」。
 *
 * ## 为什么要带中间态
 *
 * 小米健康的登录必须经过图片验证码，还可能触发短信/邮箱二次验证，
 * 两者都不是插件能在后台静默解决的——必须让用户看见图、输入码。
 *
 * 早期版本只有 [Success] / [Failure]，注释写着「中间态由插件自己的界面表达」。
 * 那句话默认了插件能自带 UI，但宿主没有 UI 桥接层，插件也没有 Activity。
 * 结果就是这类登录**根本无法完成**。
 *
 * 现在的做法：插件只描述「要用户填什么」（[NeedInput]），宿主用通用表单渲染，
 * 填完的值原样传回 [SourcePlugin.login]。契约里不出现任何 UI 类型，
 * 只多了一层纯数据的问答协议。
 */
sealed class LoginResult {

    /** 登录成功，凭据已写入 [PluginContext.credentials] */
    data object Success : LoginResult()

    /**
     * 还差用户输入才能继续，调用方拿到后应展示表单并用用户填的值重新调用登录。
     *
     * 一次只问一项，因为后一项往往要等前一项提交后才知道存不存在
     * （小米是先密码、才判断要不要验证码、再判断要不要二次验证码）。
     *
     * @property prompt 面向用户的说明，如「需要输入图片验证码」
     * @property fields 本次要填的字段
     * @property captchaImage 可选的验证码图片，`data:image/...;base64,...` 形式，
     *   宿主直接当图片显示。非空时 UI 应把图放在输入框上方
     */
    data class NeedInput(
        val prompt: String,
        val fields: List<InputField>,
        val captchaImage: String? = null,
    ) : LoginResult()

    /**
     * 登录失败。
     *
     * [message] 会**直接展示给用户**，用于排障（如「账号或密码错误」「服务端返回 502」），
     * 因此插件不得把凭据内容拼进这里。
     */
    data class Failure(val message: String) : LoginResult()
}

/**
 * 一次登录交互中要用户填的一个字段。
 *
 * 字段的 **name 必须与 [SourcePlugin.credentialSpec] 里某个 [CredentialSpec.Slot.name] 一致**，
 * 或者以 `otp` 之类的一次性名字出现（这类值插件只在该次登录中使用，不落盘）。
 * 宿主据此判断是否要用掩码显示、要不要提示「会被保存」。
 *
 * @property secret 是否按敏感内容显示（掩码）。密码、验证码都应设 true
 */
data class InputField(
    val name: String,
    val displayName: String,
    val secret: Boolean = false,
    /** 输入框提示，如「6 位数字」 */
    val hint: String = "",
)

package com.lifeboard.plugin.api

/**
 * 插件声明自己需要哪些凭据槽位。
 *
 * 只用于展示与清理提示：宿主据此告诉用户「清除这个插件的凭据会影响哪些项」，
 * **不参与读写逻辑**——真正的存取是插件自行调用的 [CredentialStore]，
 * 槽位名由插件自己决定，宿主不校验、不预建。
 *
 * 允许为空：有些插件只在内存里持有会话，或干脆不需要登录。
 *
 * @property slots 本插件会用到的槽位
 */
data class CredentialSpec(
    val slots: List<Slot> = emptyList(),
) {
    /**
     * 一个凭据槽位的展示信息。
     *
     * @property name 槽位名，与 [CredentialStore] 里使用的 slot 一致
     * @property displayName 展示名，如「访问令牌」
     * @property required 是否必需；为 false 表示可留空（如可选的双因子令牌）
     * @property secret 是否按敏感内容显示。密码框需要掩码，
     *   否则用户在满屏可见的输入框里打密码，旁边有人就能看走
     */
    data class Slot(
        val name: String,
        val displayName: String,
        val required: Boolean = true,
        val secret: Boolean = false,
    )

    companion object {
        /** 不需要任何凭据的插件用它 */
        val NONE: CredentialSpec = CredentialSpec()
    }
}

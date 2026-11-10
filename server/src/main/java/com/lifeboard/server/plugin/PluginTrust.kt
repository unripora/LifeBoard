package com.lifeboard.server.plugin

/**
 * 插件签名与信任规则。
 *
 * 全部是纯函数：输入是已经解析好的签名指纹和版本记录，不碰 PackageManager、不碰文件系统。
 * 这样「谁能被加载」这条规则可以用 JVM 单测逐条钉死，而不是靠连真机点界面来验证。
 *
 * ## 这个机制到底防什么
 *
 * 防的是「你以为装的是 A，实际被换成了 B」：
 * - 包名相同、签名不同 → 拒绝（防替换）
 * - 版本倒退 → 拒绝（防降级到有已知漏洞的旧版）
 * - 从未信任过的包 → 进待信任列表，由用户决定
 *
 * **不防「插件作者本人作恶」**：他用自己的签名发布恶意版本，签名校验完全通过。
 * 因此信任模型是**准入与防替换机制，不是安全机制**；
 * 插件与本应用同进程同权限，没有沙箱。产品文案不得写成「安全隔离」。
 */
object PluginTrust {

    /**
     * 判定一个包能否被加载。
     *
     * 顺序有意为之，先排掉「根本不该出现」的情况，再谈是否已信任：
     * 1. 无签名 → [TrustVerdict.Unsigned]，不给信任入口
     * 2. 有已安装记录但签名变了或版本倒退 → [TrustVerdict.Rejected]
     * 3. 身份已信任 → [TrustVerdict.Trusted]
     * 4. 其余 → [TrustVerdict.Untrusted]，等用户决定
     *
     * @param info 待判定的包
     * @param state 用户信任状态与已安装版本记录
     */
    fun evaluate(info: PluginArchiveInfo, state: TrustState): TrustVerdict {
        if (!info.isSigned) return TrustVerdict.Unsigned

        // 换了签名：包名还是那个包名，但已经不是同一个作者的东西了
        val installed = state.installedVersions[info.packageName]
        if (installed != null) {
            if (installed.signatureHashes != info.signatureHashes.toSet()) {
                return TrustVerdict.Rejected(
                    "签名与已安装版本不一致，可能是被替换过的包",
                )
            }
            if (info.versionCode < installed.versionCode) {
                return TrustVerdict.Rejected(
                    "版本号 ${info.versionCode} 低于已安装的 ${installed.versionCode}，拒绝降级安装",
                )
            }
        }

        return if (info.identity.key in state.trustedKeys) {
            TrustVerdict.Trusted
        } else {
            TrustVerdict.Untrusted(info.identity)
        }
    }

    /**
     * 更新签名一致性校验：新版签名集合必须与旧版**完全相同**。
     *
     * 这里曾经用「包含」来兼容证书轮换（新集合是旧集合的超集），
     * 但加载阶段的 [evaluate] 要求严格相等：同一件事两个结论，
     * 结果是「安装期放行、加载期拒绝」——用户装成功了却发现插件用不了。
     *
     * 本版本统一成严格相等：不做签名轮换，换签名一律要求重新安装并重新信任。
     * 轮换的迁移规则（信任身份与凭据归属怎么跟着换）没有设计清楚之前，
     * 不能只在校验层放宽。
     *
     * @return null 表示合法，否则返回可展示的原因
     */
    fun checkUpdateSignature(
        previous: Set<String>,
        current: Set<String>,
    ): String? = when {
        current == previous -> null
        else -> "新版本的签名与已安装版本不一致，拒绝更新"
    }
}

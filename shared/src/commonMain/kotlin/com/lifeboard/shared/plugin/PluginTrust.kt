package com.lifeboard.shared.plugin

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
     * @param info 待判定的包。用接口类型而非具体包信息：APK 与 JS 插件的
     *   包格式不同，但「身份 + 签名指纹 + 版本」这三件事是共同的，
     *   信任规则必须对两种插件给出同一套结论，否则用户会看到两套标准
     * @param state 用户信任状态与已安装版本记录
     */
    fun evaluate(info: PluginPackageInfo, state: TrustState): TrustVerdict {
        if (!info.isSigned) return TrustVerdict.Unsigned

        val installed = state.installedVersions[info.packageName]
        if (installed != null) {
            if (installed.signatureHashes != info.signatureHashes.toSet()) {
                // 签名不一致的含义要看包格式：
                //
                // APK 的签名是**证书**，换了证书就是换了发布者，必须人工介入，
                // 这类包不该被自动信任。
                //
                // JS 插件的「签名」是**包内容哈希**（见 JsPluginManifest.computeContentHash），
                // 内容一改哈希就变。把它当不可恢复的拒绝会走到死路：
                // 用户既装不上新版，也删不掉旧包，只能卸载整个应用。
                // 因此这里退化成「需要重新确认」——让用户看到变化后再决定是否信任。
                return if (info.isContentAddressed) {
                    TrustVerdict.Untrusted(info.identity)
                } else {
                    TrustVerdict.Rejected("签名与已安装版本不一致，可能是被替换过的包")
                }
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
}

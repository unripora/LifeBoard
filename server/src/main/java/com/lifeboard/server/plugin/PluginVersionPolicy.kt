package com.lifeboard.server.plugin

/**
 * 插件版本裁决结果。
 *
 * 与 [TrustVerdict] 分开是因为两件事独立发生：
 * 签名决定「这个包是不是我信任的那个作者」，版本决定「该不该用这个包覆盖已装的」。
 */
sealed class VersionVerdict {

    /** 全新安装，或同版本重装 */
    data object Install : VersionVerdict()

    /** 合法升级，用新包覆盖 */
    data object Upgrade : VersionVerdict()

    /**
     * 拒绝安装。
     *
     * @property reason 展示给用户的具体原因
     */
    data class Rejected(val reason: String) : VersionVerdict()
}

/**
 * 安装前的版本与签名裁决。
 *
 * 不碰文件系统，纯函数，便于单测。
 */
object PluginVersionPolicy {

    /**
     * 裁决能否用 [incoming] 覆盖 [installed]。
     *
     * @param installed 已安装记录，null 表示首次安装
     * @param incoming 待安装的包
     */
    fun evaluate(
        installed: InstalledVersion?,
        incoming: PluginArchiveInfo,
    ): VersionVerdict {
        if (installed == null) return VersionVerdict.Install

        // 换签名一律拒绝：这正是「你以为装的是 A，实际换成 B」的场景
        PluginTrust.checkUpdateSignature(installed.signatureHashes, incoming.signatureHashes.toSet())
            ?.let { return VersionVerdict.Rejected(it) }

        if (incoming.versionCode < installed.versionCode) {
            return VersionVerdict.Rejected(
                "版本号 ${incoming.versionCode} 低于已安装的 ${installed.versionCode}，拒绝降级安装",
            )
        }

        return if (incoming.versionCode > installed.versionCode) {
            VersionVerdict.Upgrade
        } else {
            VersionVerdict.Install
        }
    }
}

package com.lifeboard.server.plugin

/**
 * 从 `.ext` 包里读出来的插件元数据。
 *
 * 这里只放**不依赖 Android API 的部分**，Android 侧的 `getPackageArchiveInfo` 负责
 * 把结果填进来。拆开是为了让签名比对、信任判定、版本校验这些规则能用 JVM 单测覆盖——
 * 它们一旦写错，要么把未信任的包放进来，要么把已装好的插件判成不可用。
 *
 * @property packageName 插件包名，如 `dev.foo.mihealth`
 * @property versionCode 版本号，用于防降级
 * @property versionName 版本名，仅用于展示
 * @property pluginId 插件自己声明的 id（[com.lifeboard.plugin.api.SourcePlugin.id]）。
 *   加载前不知道，从 dex 里实例化后才填得出来，因此这里可为空：
 *   **信任与版本校验不依赖它**，只依赖包名与签名
 * @property signatureHashes 签名证书指纹（SHA-256，大写十六进制），按 APK 实际顺序。
 *   可能多于一个：多签名包在轮换证书期间会同时带新旧两个签名
 */
data class PluginArchiveInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signatureHashes: List<String>,
    val pluginId: String? = null,
) {
    /** 有没有带签名。无签名的包一律不收 */
    val isSigned: Boolean get() = signatureHashes.isNotEmpty()

    /**
     * 身份指纹：用户信任的就是「这个包名下的这套签名」。
     *
     * 用排序后再拼接，避免同一套签名因 APK 内部顺序不同被当成不同身份；
     * 这是 [PluginIdentity] 的唯一生成入口，禁止别处自行拼接。
     */
    val identity: PluginIdentity
        get() = PluginIdentity(packageName, signatureHashes.sorted().toSet())
}

/**
 * 插件身份。
 *
 * 为什么身份不含版本号：Mihon 把信任绑在 `包名:版本号:签名` 上，结果是**每次更新都要用户再信任一次**。
 * 本项目的取舍是——身份只认包名 + 签名集合，版本单独记录用于防降级与展示。
 * 这样同一作者用同一签名发新版可以直接升级，而「换了签名」仍然必须重新信任。
 *
 * @property packageName 包名
 * @property signatureHashes 签名指纹集合（无序）
 */
data class PluginIdentity(
    val packageName: String,
    val signatureHashes: Set<String>,
) {
    /** 写进信任存储的稳定字符串，同样是唯一拼接入口 */
    val key: String
        get() = "$packageName|${signatureHashes.sorted().joinToString(",")}"
}

/**
 * 信任判定的输入：用户已信任的身份与版本记录。
 *
 * @property trustedKeys 已信任的 [PluginIdentity.key] 集合
 * @property installedVersions 已安装插件的版本记录，key 为包名。
 *   用于防降级：签名没变但版本倒退，说明装的是旧包，可能是被替换
 */
data class TrustState(
    val trustedKeys: Set<String>,
    val installedVersions: Map<String, InstalledVersion> = emptyMap(),
)

/**
 * 已安装插件的版本记录。
 *
 * @property versionCode 当前已安装的版本号
 * @property signatureHashes 安装时的签名指纹，用于**更新时校验签名一致性**
 */
data class InstalledVersion(
    val versionCode: Long,
    val signatureHashes: Set<String>,
)

/**
 * 一次信任判定的结论。
 *
 * 三态而不是布尔：UI 需要区分「没签名」（根本不该出现在列表里）、
 * 「签名了但用户还没信任」（弹警告让用户决定）、「已信任」（直接加载）。
 * 用布尔会把前两种压成同一个「不可用」，用户不知道该做什么。
 */
sealed class TrustVerdict {

    /** 已信任，可以加载 */
    data object Trusted : TrustVerdict()

    /** 没签名，拒绝加载且不提供信任入口 */
    data object Unsigned : TrustVerdict()

    /**
     * 有签名但用户尚未信任。
     *
     * @property identity 待信任的身份，UI 据此展示「信任哪个包名下的哪套签名」
     */
    data class Untrusted(val identity: PluginIdentity) : TrustVerdict()

    /**
     * 身份已信任过，但本次的包与已安装记录不一致，属于可疑替换。
     *
     * 具体两种：签名变了，或版本倒退了。
     *
     * @property reason 展示给用户的具体原因
     */
    data class Rejected(val reason: String) : TrustVerdict()
}
